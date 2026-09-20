package domain.core.persistence;

import domain.core.access.AccessContext;
import domain.core.access.AccessContextHolder;
import domain.core.access.AccessFlags;
import domain.core.access.AccessLevel;
import domain.core.access.AccessResolver;
import domain.core.access.ClaimsExtractor;
import domain.core.access.SystemAccessContexts;
import domain.core.bootstrap.AccessFilterDef;
import domain.core.bootstrap.MetadataSnapshot;
import domain.core.bootstrap.MetadataSnapshotProvider;
import domain.core.ddd.IdCodec;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.hibernate.Filter;
import org.hibernate.Session;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.io.Closeable;
import java.io.IOException;
import java.util.Set;

/**
 * AOP-аспект, активирующий Hibernate-фильтры (для {@code @AccessFiltered}-агрегатов)
 * на каждый read-метод {@link AggregateRepository}.
 *
 * <p>Резолв typeId — через {@link RepositoryRegistry#typeIdByRepoBeanClass(Class)} (O(1)),
 * без хождения по interface-дереву. Уникальность гарантирована fail-fast'ом в реестре.
 *
 */
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
public class AccessFilterActivator {

    @PersistenceContext
    private EntityManager em;

    private final MetadataSnapshotProvider snapshots;
    private final AccessContextHolder holder;
    private final AccessResolver resolver;
    private final ClaimsExtractor claims;
    private final SystemAccessContexts systemContexts;
    private final RepositoryRegistry repoRegistry;

    public AccessFilterActivator(MetadataSnapshotProvider snapshots,
                                  AccessContextHolder holder,
                                  AccessResolver resolver,
                                  ClaimsExtractor claims,
                                  SystemAccessContexts systemContexts,
                                  RepositoryRegistry repoRegistry) {
        this.snapshots = snapshots;
        this.holder = holder;
        this.resolver = resolver;
        this.claims = claims;
        this.systemContexts = systemContexts;
        this.repoRegistry = repoRegistry;
    }

    @Around(
        "this(domain.core.persistence.AggregateRepository) && " +
        "(execution(* find*(..)) || execution(* count(..)) || execution(* count*(..)) " +
        "|| execution(* exists*(..)) || execution(* search*(..)))"
    )
    public Object aroundReadOps(ProceedingJoinPoint pjp) throws Throwable {
        long typeId = repoRegistry.typeIdByRepoBeanClass(pjp.getTarget().getClass());
        if (typeId < 0) {
            return pjp.proceed();   // unknown — пропускаем
        }

        // Если контекста нет — fail-closed: ставим systemReadOnly и применяем filter'ы
        // с sentinel-значениями (deny-all). Это безопасный default.
        AccessContext ctx = holder.tryGet().orElse(null);
        boolean weCreatedSystemCtx = false;
        Closeable ctxRestore = null;
        if (ctx == null) {
            ctx = systemContexts.systemReadOnly();
            ctxRestore = holder.bind(ctx);
            weCreatedSystemCtx = true;
        }
        try {
            return runWithFilters(pjp, typeId, ctx);
        } finally {
            if (weCreatedSystemCtx && ctxRestore != null) {
                try { ctxRestore.close(); } catch (IOException ignored) {}
            }
        }
    }

    private Object runWithFilters(ProceedingJoinPoint pjp, long typeId, AccessContext ctx) throws Throwable {
        MetadataSnapshot snap = snapshots.get();
        var filters = snap.filtersForTypeId(typeId);
        Session session = em.unwrap(Session.class);

        // Repo-level read
        AccessLevel repoLvl = resolver.resolveRepository(typeId, ctx);
        if (!repoLvl.canRead()) {
            throw new AccessDeniedException("Repository typeId=" + typeId + " not readable");
        }

        // Bypass: ADMIN_READ (global или per-type) — пропуск всех instance-level
        // filter'ов. Согласно контракту AccessFlags, ADMIN_READ обходит row-level
        // (Hibernate filters / ownAccess); ROOT_* его имплицируют через expand().
        // Симметрично с PostLoadAccessCheckListener.
        var um = resolver.userMetricFor(ctx);
        int g = AccessFlags.expand(um.globalFlags());
        int t = AccessFlags.expand(um.typeFlags().getOrDefault(typeId, 0));
        // Табличная часть: ADMIN_READ владельца тоже снимает row-level фильтры с ТЧ.
        Long ownerTypeId = snap.tabularOwnerTypeId(typeId);
        if (ownerTypeId != null) {
            t |= AccessFlags.expand(um.typeFlags().getOrDefault(ownerTypeId, 0));
        }
        boolean bypassFilters = ((g | t) & AccessFlags.ADMIN_READ) != 0;

        String methodName = ((MethodSignature) pjp.getSignature()).getMethod().getName();
        PostLoadMode mode = (methodName.equals("findById") || methodName.startsWith("getOne")
                || methodName.startsWith("getReferenceById"))
                ? PostLoadMode.THROW
                : PostLoadMode.MARK_AND_DROP;

        Closeable mh = PostLoadModeHolder.bind(mode);
        try {
            if (!bypassFilters && !filters.isEmpty()) {
                for (AccessFilterDef f : filters) {
                    Set<String> values = claims.extract(ctx.auth(), f.userClaim().jwtName);
                    Class<? extends java.io.Serializable> idClass = snap.idClassByTypeId(f.referencedTypeId());
                    Filter filter = session.enableFilter(f.filterName());
                    if (values.isEmpty()) {
                        Object sentinel = IdCodec.denyAllSentinel(idClass);
                        filter.setParameterList("ids", java.util.List.of(sentinel));
                    } else {
                        java.util.List<Object> typed = new java.util.ArrayList<>(values.size());
                        for (String v : values) {
                            try { typed.add(IdCodec.decode(v, idClass)); }
                            catch (RuntimeException e) { /* invalid claim — игнор */ }
                        }
                        if (typed.isEmpty()) typed.add(IdCodec.denyAllSentinel(idClass));
                        filter.setParameterList("ids", typed);
                    }
                }
            }
            return pjp.proceed();
        } finally {
            for (AccessFilterDef f : filters) {
                try { session.disableFilter(f.filterName()); } catch (Exception ignored) {}
            }
            try { mh.close(); } catch (IOException ignored) {}
        }
    }
}
