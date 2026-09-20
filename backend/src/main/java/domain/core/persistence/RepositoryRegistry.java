package domain.core.persistence;

import domain.core.bootstrap.BootstrapValidationException;
import domain.core.bootstrap.MetadataSnapshot;
import domain.core.bootstrap.MetadataSnapshotProvider;
import domain.core.ddd.annotations.TypeId;
import jakarta.annotation.PostConstruct;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.DependsOn;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.core.support.RepositoryFactoryInformation;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Реестр {@link AggregateRepository}-инстансов по {@code typeId}.
 *
 * <p>В {@code @PostConstruct} сканирует все {@link Repository}-bean'ы, ищет интерфейс,
 * аннотированный {@link TypeId}, и сопоставляет typeId → repo + repo-proxy-class → typeId.
 *
 * <p><b>Fail-fast'ы.</b> Каждый агрегат имеет ровно один инстанс репозитория
 * одного typeId — это инвариант системы, а не предположение. Регистр enforce'ит:
 * <ul>
 *   <li>каждый {@code @TypeId}-агрегат имеет хотя бы один {@code @TypeId}-repo-интерфейс;</li>
 *   <li>каждый typeId связан ровно с одним repo-интерфейсом (два разных интерфейса
 *       с одинаковым {@code @TypeId(N)} — fatal error);</li>
 *   <li>в цепочке super-интерфейсов repo'а — ровно одна {@code @TypeId}-аннотация
 *       (для надёжного резолва из AOP);</li>
 *   <li>{@code @TypeId} на repo и на сущности совпадают.</li>
 * </ul>
 */
@Component
@DependsOn("metadataBootstrapper")
public class RepositoryRegistry {

    private final ApplicationContext ctx;
    private final MetadataSnapshotProvider snapshots;

    private final Map<Long, AggregateRepository<?, ?>> byTypeId = new HashMap<>();
    private final Map<Class<?>, Long> typeIdByEntity = new HashMap<>();
    private final Map<Long, Class<?>> repoInterfaceByTypeId = new HashMap<>();
    /** Карта (класс proxy/таргета repo) → typeId, для O(1) lookup'а из AOP без хождения по interface-дереву. */
    private final Map<Class<?>, Long> typeIdByRepoBeanClass = new HashMap<>();

    public RepositoryRegistry(ApplicationContext ctx, MetadataSnapshotProvider snapshots) {
        this.ctx = ctx;
        this.snapshots = snapshots;
    }

    @PostConstruct
    public void init() {
        MetadataSnapshot snap = snapshots.get();
        Map<String, RepositoryFactoryInformation> infos =
                ctx.getBeansOfType(RepositoryFactoryInformation.class);

        for (var entry : infos.entrySet()) {
            RepositoryFactoryInformation<?, ?> info = entry.getValue();
            Class<?> repoInterface = info.getRepositoryInformation().getRepositoryInterface();
            Class<?> domainType = info.getRepositoryInformation().getDomainType();

            long typeId = resolveUniqueTypeIdFromInterfaces(repoInterface);
            if (typeId < 0) continue;     // нет @TypeId — не agg repo

            // entity-side check
            TypeId entTid = domainType.getAnnotation(TypeId.class);
            if (entTid == null || entTid.value() != typeId) {
                throw new BootstrapValidationException(
                        "Repository " + repoInterface.getName() + " has @TypeId(" + typeId +
                                ") but entity " + domainType.getName() +
                                " has @TypeId(" + (entTid == null ? "<missing>" : entTid.value()) + ")");
            }
            // FAIL-FAST: дубликат typeId на разных repo-интерфейсах
            if (byTypeId.containsKey(typeId)) {
                throw new BootstrapValidationException(
                        "Duplicate repository for typeId=" + typeId +
                                ": already registered " + repoInterfaceByTypeId.get(typeId).getName() +
                                ", now found " + repoInterface.getName() +
                                ". Один агрегат — один репозиторий.");
            }
            Object bean = ctx.getBean(repoInterface);
            if (!(bean instanceof AggregateRepository<?, ?> repo)) {
                throw new BootstrapValidationException(
                        "Repository " + repoInterface.getName() +
                                " must extend AggregateRepository (got " +
                                ClassUtils.getQualifiedName(bean.getClass()) + ")");
            }
            byTypeId.put(typeId, repo);
            typeIdByEntity.put(domainType, typeId);
            repoInterfaceByTypeId.put(typeId, repoInterface);
            // Регистрация и по самому интерфейсу, и по proxy-классу (это разные Class-объекты)
            typeIdByRepoBeanClass.put(repoInterface, typeId);
            typeIdByRepoBeanClass.put(bean.getClass(), typeId);
        }

        // Каждый @TypeId-агрегат должен иметь repo
        for (var desc : snap.allAggregates()) {
            if (!repoInterfaceByTypeId.containsKey(desc.typeId())) {
                throw new BootstrapValidationException(
                        "No @AggregateRepository found for typeId=" + desc.typeId() +
                                " (" + desc.javaClass().getName() + ")");
            }
        }
    }

    /**
     * Walk'ает все super-интерфейсы repo'а и проверяет, что аннотация {@link TypeId}
     * присутствует РОВНО НА ОДНОМ интерфейсе. Это даёт надёжный резолв для AOP:
     * не нужно гадать «какой именно из super'ов несёт typeId».
     *
     * @return найденный typeId или -1, если {@code @TypeId} нигде в иерархии нет
     */
    private static long resolveUniqueTypeIdFromInterfaces(Class<?> repoInterface) {
        Set<Class<?>> annotated = new HashSet<>();
        collectTypeIdInterfaces(repoInterface, annotated);
        if (annotated.isEmpty()) return -1;
        if (annotated.size() > 1) {
            StringBuilder sb = new StringBuilder();
            for (var c : annotated) sb.append(c.getName()).append(" ");
            throw new BootstrapValidationException(
                    "Repository " + repoInterface.getName() +
                            " has @TypeId on multiple super-interfaces: " + sb.toString().trim() +
                            ". @TypeId должен быть ровно на одном интерфейсе цепочки.");
        }
        return annotated.iterator().next().getAnnotation(TypeId.class).value();
    }

    private static void collectTypeIdInterfaces(Class<?> c, Set<Class<?>> out) {
        if (c == null) return;
        if (c.isAnnotationPresent(TypeId.class) && AggregateRepository.class.isAssignableFrom(c)) {
            out.add(c);
        }
        for (Class<?> i : c.getInterfaces()) collectTypeIdInterfaces(i, out);
        if (c.getSuperclass() != null) collectTypeIdInterfaces(c.getSuperclass(), out);
    }

    public AggregateRepository<?, ?> byTypeId(long typeId) {
        return byTypeId.get(typeId);
    }

    public long typeIdOf(Class<?> entityClass) {
        Long t = typeIdByEntity.get(entityClass);
        if (t == null) throw new IllegalStateException("No repo for entity " + entityClass);
        return t;
    }

    public Class<?> repoInterfaceFor(Class<?> entityClass) {
        return repoInterfaceByTypeId.get(typeIdOf(entityClass));
    }

    public Class<?> repoInterfaceByTypeId(long typeId) {
        return repoInterfaceByTypeId.get(typeId);
    }

    /**
     * Прямой lookup typeId по proxy/classes repo'а — используется
     * {@link AccessFilterActivator} вместо хождения по interface-дереву.
     *
     * @return typeId или -1, если этот класс не зарегистрирован
     */
    public long typeIdByRepoBeanClass(Class<?> repoBeanClass) {
        Long t = typeIdByRepoBeanClass.get(repoBeanClass);
        if (t != null) return t;
        // Walk вверх по proxy-иерархии (на случай прокси-цепочек)
        for (Class<?> i : repoBeanClass.getInterfaces()) {
            Long x = typeIdByRepoBeanClass.get(i);
            if (x != null) return x;
        }
        return -1;
    }
}
