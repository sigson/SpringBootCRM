package app.springbootcrm.metadata;

import app.springbootcrm.common.SqlRowFilter;
import app.springbootcrm.documents.activity.Activity;

import app.springbootcrm.reference.ReferenceAggregate;
import domain.core.ddd.AbstractAggregate;
import domain.core.persistence.AggregateRepository;
import domain.core.persistence.RepositoryRegistry;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Універсальна реалізація {@link ReferenceProvider}, придатна для <b>будь-якого</b>
 * бізнес-об'єкта — як довідника ({@link ReferenceAggregate}), так і регістру
 * (звичайний {@code AbstractAggregate} без code/name).
 *
 * <p>Один універсальний провайдер замість десятків рукописних {@code XxxReferenceProvider}:
 * {@code list} = {@code findAll} + підстроковий фільтр, {@code findById} = парсинг UUID +
 * {@code repo.findById}, {@code filterRepository} = сам репозиторій.
 *
 * <p><b>Довідники vs регістри.</b> Код/найменування читаються типобезпечно через
 * {@link ReferenceAggregate}, якщо сутність його реалізує (довідники). Регістри
 * (без code/name) представляються через {@code displayPattern}
 * ({@link ReferenceProvider#display}), а в picker'і фільтруються за будь-яким
 * рядковим полем. Серверна пагінація/фільтри ({@code listPaged}) працюють для
 * обох: відсутні в метамоделі колонки (code/name у регістрів) автоматично
 * пропускаються {@code SqlRowFilter}.
 *
 * <p>Інстанси цього класу <b>не оголошуються вручну</b> — їх реєструє
 * {@code ReferenceProviderAutoRegistrar} (хук {@code BeanDefinitionRegistryPostProcessor})
 * по одному на кожен {@code @TypeId @Entity}-агрегат. typeId та клас сутності
 * передаються конструктором, а сам репозиторій резолвиться <i>ліниво</i> з
 * {@link RepositoryRegistry} (typeId → {@code AggregateRepository}) при першому
 * зверненні — щоб уникнути залежностей від порядку ініціалізації бінів.
 *
 * @param <T> тип агрегату
 */
public class GenericReferenceProvider<T extends AbstractAggregate<?>>
        implements ReferenceProvider<T> {

    private final long typeId;
    private final Class<T> entityClass;
    private final RepositoryRegistry repositories;

    /** Лінивий кеш репозиторію (резолвиться при першому використанні). */
    private volatile AggregateRepository<T, ?> repoCache;

    public GenericReferenceProvider(long typeId, Class<T> entityClass,
                                    RepositoryRegistry repositories) {
        this.typeId = typeId;
        this.entityClass = entityClass;
        this.repositories = repositories;
    }

    @Override
    public long typeId() {
        return typeId;
    }

    /** Авто-провайдер — поступається будь-якому рукописному override'у. */
    @Override
    public boolean generated() {
        return true;
    }

    @SuppressWarnings("unchecked")
    private AggregateRepository<T, ?> repo() {
        AggregateRepository<T, ?> r = repoCache;
        if (r == null) {
            AggregateRepository<?, ?> found = repositories.byTypeId(typeId);
            if (found == null) {
                throw new IllegalStateException(
                        "No AggregateRepository for typeId=" + typeId +
                                " (" + entityClass.getName() + ") - the auto provider cannot work");
            }
            r = (AggregateRepository<T, ?>) found;
            repoCache = r;
        }
        return r;
    }

    @Override
    public JpaSpecificationExecutor<T> filterRepository() {
        return repo();
    }

    @Override
    public List<T> list(String query) {
        List<T> all = repo().findAll();
        if (query == null || query.isBlank()) return all;
        String q = query.toLowerCase();
        return all.stream().filter(e -> matches(e, q)).toList();
    }

    /**
     * Підстроковий матчинг для picker'а.
     * <ul>
     *   <li><b>Довідник</b> ({@link ReferenceAggregate}) — за code/name;</li>
     *   <li><b>Регістр</b> (без code/name) — за будь-яким рядковим полем сутності
     *       (напр. {@code title} у {@code Activity}).</li>
     * </ul>
     */
    private boolean matches(T e, String qLower) {
        if (e instanceof ReferenceAggregate r) {
            return containsIc(r.getCode(), qLower) || containsIc(r.getName(), qLower);
        }
        return anyStringFieldContains(e, qLower);
    }

    /** Рефлексія по всіх рядкових полях ієрархії — для регістрів без code/name. */
    private static boolean anyStringFieldContains(Object entity, String qLower) {
        for (Class<?> c = entity.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                if (f.getType() != String.class) continue;
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                try {
                    f.setAccessible(true);
                    if (containsIc((String) f.get(entity), qLower)) return true;
                } catch (ReflectiveOperationException ignored) {
                    // недоступне поле — пропускаємо
                }
            }
        }
        return false;
    }

    @Override
    public Optional<T> findById(String idRaw) {
        try {
            // Усі агрегати SpringBootCRM ідентифікуються UUID.
            UUID id = UUID.fromString(idRaw);
            return repo().findById(castId(id));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    @Override
    public String code(T entity) {
        if (entity instanceof ReferenceAggregate ref) return ref.getCode();
        return ReferenceProvider.super.code(entity);   // fallback: рефлексія по полю "code"
    }

    @Override
    public String name(T entity) {
        if (entity instanceof ReferenceAggregate ref) return ref.getName();
        return ReferenceProvider.super.name(entity);   // fallback: рефлексія по полю "name"
    }

    @SuppressWarnings("unchecked")
    private static <ID> ID castId(UUID id) {
        return (ID) id;
    }

    private static boolean containsIc(String s, String qLower) {
        return s != null && s.toLowerCase().contains(qLower);
    }
}
