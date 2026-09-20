package domain.core.persistence;

import domain.core.access.AccessContextHolder;
import domain.core.access.AccessFlags;
import domain.core.access.AccessLevel;
import domain.core.access.AccessResolver;
import domain.core.access.PermissionRequirement;
import domain.core.access.StructuredAccessDeniedException;
import domain.core.bootstrap.MetadataSnapshot;
import domain.core.bootstrap.MetadataSnapshotProvider;
import domain.core.ddd.AbstractAggregate;
import domain.core.ddd.annotations.AccessChecked;
import org.hibernate.event.spi.PreDeleteEvent;
import org.hibernate.event.spi.PreDeleteEventListener;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Repo-level защита DELETE'а — симметрично PreInsert/PreUpdate.
 * Бросает {@link StructuredAccessDeniedException} с {@link PermissionRequirement}-описанием.
 */
public class AccessAwarePreDeleteListener implements PreDeleteEventListener {

    private final ObjectProvider<AccessResolver> resolver;
    private final ObjectProvider<MetadataSnapshotProvider> snapshots;
    private final ObjectProvider<AccessContextHolder> holder;

    public AccessAwarePreDeleteListener(ObjectProvider<AccessResolver> resolver,
                                         ObjectProvider<MetadataSnapshotProvider> snapshots,
                                         ObjectProvider<AccessContextHolder> holder) {
        this.resolver = resolver;
        this.snapshots = snapshots;
        this.holder = holder;
    }

    @Override
    public boolean onPreDelete(PreDeleteEvent ev) {
        if (!(ev.getEntity() instanceof AbstractAggregate<?> agg)) return false;

        AccessChecked ann = agg.getClass().getAnnotation(AccessChecked.class);
        boolean strict = ann == null || ann.strict();

        AccessContextHolder h = holder.getIfAvailable();
        var ctxOpt = h == null ? java.util.Optional.<domain.core.access.AccessContext>empty() : h.tryGet();

        if (ctxOpt.isEmpty()) {
            if (strict) {
                throw new StructuredAccessDeniedException(
                        "Видалення «" + agg.getClass().getSimpleName() +
                                "» потребує автентифікації",
                        PermissionRequirement.global(AccessFlags.READ));
            }
            return false;
        }
        var ctx = ctxOpt.get();
        if (ctx.isSystemMaxPrivileged()) return false;

        MetadataSnapshot snap = snapshots.getObject().get();
        long typeId;
        try {
            typeId = snap.typeIdOf(agg.getClass());
        } catch (IllegalStateException e) {
            return false;
        }
        AccessResolver r = resolver.getObject();

        AccessLevel repoLvl = r.resolveRepository(typeId, ctx);
        if (!repoLvl.canDelete()) {
            throw new StructuredAccessDeniedException(
                    "Недостатньо прав для видалення записів цього типу",
                    PermissionRequirement.repo(typeId, AccessFlags.WRITE_UPDATE));
        }
        return false;
    }
}
