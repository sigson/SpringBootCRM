package domain.core.persistence;

import app.springbootcrm.catalogs.customer.Customer;

import domain.core.access.AccessContextHolder;
import domain.core.access.AccessFlags;
import domain.core.access.AccessLevel;
import domain.core.access.AccessResolver;
import domain.core.access.ClaimsExtractor;
import domain.core.access.UserClaim;
import domain.core.bootstrap.AccessFilterDef;
import domain.core.bootstrap.AggregateDescriptor;
import domain.core.bootstrap.FieldDescriptor;
import domain.core.bootstrap.MetadataSnapshot;
import domain.core.bootstrap.MetadataSnapshotProvider;
import domain.core.ddd.AbstractAggregate;
import domain.core.ddd.AggregateReference;
import org.hibernate.event.spi.PostLoadEvent;
import org.hibernate.event.spi.PostLoadEventListener;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.Set;

/**
 * POST_LOAD-проверка для cross-tenant load'ов через прямой {@code findById}.
 *
 * <p>Hibernate-фильтры применяются ТОЛЬКО к {@code from Entity ...}-запросам;
 * прямые {@code session.get(Customer.class, id)} с другого тенанта проходят мимо.
 * Этот listener — finальная защита.
 *
 * <p>Поведение зависит от {@link PostLoadModeHolder#current()}:
 * <ul>
 *   <li>{@link PostLoadMode#THROW} — кидает {@link AccessDeniedException} (для single-row API);</li>
 *   <li>{@link PostLoadMode#MARK_AND_DROP} — отмечает в {@link PostLoadDropMarker}.</li>
 * </ul>
 */
public class PostLoadAccessCheckListener implements PostLoadEventListener {

    private final ObjectProvider<MetadataSnapshotProvider> snapshots;
    private final ObjectProvider<AccessContextHolder> holder;
    private final ObjectProvider<ClaimsExtractor> claims;
    private final ObjectProvider<AccessResolver> resolver;
    private final ObjectProvider<PostLoadDropMarker> markerProvider;

    public PostLoadAccessCheckListener(ObjectProvider<MetadataSnapshotProvider> snapshots,
                                        ObjectProvider<AccessContextHolder> holder,
                                        ObjectProvider<ClaimsExtractor> claims,
                                        ObjectProvider<AccessResolver> resolver,
                                        ObjectProvider<PostLoadDropMarker> markerProvider) {
        this.snapshots = snapshots;
        this.holder = holder;
        this.claims = claims;
        this.resolver = resolver;
        this.markerProvider = markerProvider;
    }

    @Override
    public void onPostLoad(PostLoadEvent ev) {
        if (!(ev.getEntity() instanceof AbstractAggregate<?> agg)) return;

        AccessContextHolder h = holder.getIfAvailable();
        var ctxOpt = h == null ? java.util.Optional.<domain.core.access.AccessContext>empty() : h.tryGet();
        if (ctxOpt.isEmpty()) return;     // нет контекста — пропускаем (другие листенеры fail'нут)
        var ctx = ctxOpt.get();
        if (ctx.isSystemMaxPrivileged()) return;

        MetadataSnapshot snap;
        long typeId;
        try {
            snap = snapshots.getObject().get();
            typeId = snap.typeIdOf(agg.getClass());
        } catch (IllegalStateException e) {
            return;
        }

        // Bootstrap-режим: ROOT_READ для конкретного типа = пропуск.
        AccessResolver r = resolver.getObject();
        var um = r.userMetricFor(ctx);
        int g = AccessFlags.expand(um.globalFlags());
        int t = AccessFlags.expand(um.typeFlags().getOrDefault(typeId, 0));
        // Табличная часть: ADMIN_READ владельца действует и на её строки.
        Long ownerTypeId = snap.tabularOwnerTypeId(typeId);
        if (ownerTypeId != null) {
            t |= AccessFlags.expand(um.typeFlags().getOrDefault(ownerTypeId, 0));
        }
        if (((g | t) & AccessFlags.ROOT_READ) != 0 && ctx.isBootstrapForType(typeId)) return;

        AggregateDescriptor desc = snap.aggregate(typeId);
        AccessLevel repoLvl = r.resolveRepository(typeId, ctx);
        if (!repoLvl.canRead()) {
            applyOrThrow(agg, "Repository typeId=" + typeId + " not readable");
            return;
        }

        // Bypass instance-level (Hibernate filters / ownAccess): ADMIN_READ обходит
        // row-level фильтры по контракту AccessFlags (см. javadoc AccessFlags:
        // «ADMIN_READ — обходит instance-level»). Это симметрично с
        // AccessFilterActivator, который пропускает SQL-фильтры для admin/root.
        // Без этого admin/root видит строки в списке (MARK_AND_DROP пропускает их
        // через bypass активатора), но падал бы на прямом findById (THROW).
        if (((g | t) & AccessFlags.ADMIN_READ) != 0) return;

        // Filter-проверка: для каждого @AccessFiltered проверяем, что значение filter-поля
        // соответствует одному из claim'ов пользователя.
        List<AccessFilterDef> filters = snap.filtersForTypeId(typeId);
        if (!filters.isEmpty()) {
            ClaimsExtractor ce = claims.getObject();
            for (AccessFilterDef f : filters) {
                FieldDescriptor fd = desc.fieldByName(f.filterField());
                if (fd == null) continue;
                Object val = fd.read(agg);
                if (val == null) continue;
                Set<String> allowed = ce.extract(ctx.auth(), f.userClaim().jwtName);
                if (allowed.isEmpty()) {
                    applyOrThrow(agg, "PostLoad: empty claim '" + f.userClaim().jwtName + "'");
                    return;
                }
                String filterValue = extractRefValue(val);
                if (!allowed.contains(filterValue)) {
                    applyOrThrow(agg, "PostLoad: cross-tenant " + agg.getClass().getSimpleName() +
                            " — value=" + filterValue + " not in claim '" + f.userClaim().jwtName + "'");
                    return;
                }
            }
        }
    }

    private void applyOrThrow(Object entity, String msg) {
        if (PostLoadModeHolder.current() == PostLoadMode.THROW) {
            throw new AccessDeniedException(msg);
        }
        PostLoadDropMarker m = markerProvider.getIfAvailable();
        if (m != null) m.mark(entity);
    }

    private String extractRefValue(Object v) {
        if (v instanceof AggregateReference<?, ?> ref) return ref.targetIdRaw();
        return String.valueOf(v);
    }
}
