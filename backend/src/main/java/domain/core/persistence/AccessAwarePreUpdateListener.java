package domain.core.persistence;

import domain.core.access.AccessContextHolder;
import domain.core.access.AccessFlags;
import domain.core.access.AccessLevel;
import domain.core.access.AccessResolver;
import domain.core.access.PermissionRequirement;
import domain.core.access.StructuredAccessDeniedException;
import domain.core.access.WriteMode;
import domain.core.bootstrap.AggregateDescriptor;
import domain.core.bootstrap.FieldDescriptor;
import domain.core.bootstrap.MetadataSnapshot;
import domain.core.bootstrap.MetadataSnapshotProvider;
import domain.core.ddd.AbstractAggregate;
import domain.core.ddd.annotations.AccessChecked;
import org.hibernate.event.spi.PreUpdateEvent;
import org.hibernate.event.spi.PreUpdateEventListener;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Objects;

/**
 * Field-level + repo-level защита UPDATE'а.
 *
 * <p>Как и PreInsert, бросает {@link StructuredAccessDeniedException} с
 * {@link PermissionRequirement}-описанием — UI показывает, какой бит/поле запрещены.
 */
public class AccessAwarePreUpdateListener implements PreUpdateEventListener {

    private final ObjectProvider<AccessResolver> resolver;
    private final ObjectProvider<MetadataSnapshotProvider> snapshots;
    private final ObjectProvider<AccessContextHolder> holder;

    public AccessAwarePreUpdateListener(ObjectProvider<AccessResolver> resolver,
                                         ObjectProvider<MetadataSnapshotProvider> snapshots,
                                         ObjectProvider<AccessContextHolder> holder) {
        this.resolver = resolver;
        this.snapshots = snapshots;
        this.holder = holder;
    }

    @Override
    public boolean onPreUpdate(PreUpdateEvent ev) {
        if (!(ev.getEntity() instanceof AbstractAggregate<?> agg)) return false;

        AccessChecked ann = agg.getClass().getAnnotation(AccessChecked.class);
        boolean strict = ann == null || ann.strict();

        AccessContextHolder h = holder.getIfAvailable();
        var ctxOpt = h == null ? java.util.Optional.<domain.core.access.AccessContext>empty() : h.tryGet();

        if (ctxOpt.isEmpty()) {
            if (strict) {
                throw new StructuredAccessDeniedException(
                        "Редагування «" + agg.getClass().getSimpleName() +
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
        if (!repoLvl.canModify()) {
            throw new StructuredAccessDeniedException(
                    "Недостатньо прав для редагування записів цього типу",
                    PermissionRequirement.repo(typeId, AccessFlags.WRITE_UPDATE));
        }

        AggregateDescriptor desc = snap.aggregate(typeId);
        Object[] oldState = ev.getOldState();
        Object[] newState = ev.getState();
        String[] propNames = ev.getPersister().getPropertyNames();

        for (int i = 0; i < propNames.length; i++) {
            FieldDescriptor fd = desc.fieldByPropertyName(propNames[i]);
            if (fd == null) continue;
            if (AuditFieldDetector.isAuditManaged(fd.rawField())) continue;
            Object oldVal = oldState[i];
            Object newVal = newState[i];
            if (Objects.equals(oldVal, newVal)) continue;

            AccessLevel lvl = r.resolve(typeId, fd.fieldId(), agg, ctx);
            if (!lvl.canUpdate(oldVal)) {
                String reason = lvl.mode() == WriteMode.MODIFY_EMPTY
                        ? "Поле «" + fd.name() + "» неможна змінювати після створення"
                        : "Недостатньо прав для зміни поля «" + fd.name() + "»";
                throw new StructuredAccessDeniedException(
                        reason,
                        PermissionRequirement.field(typeId, fd.name(), AccessFlags.WRITE_UPDATE));
            }
        }
        return false;
    }
}
