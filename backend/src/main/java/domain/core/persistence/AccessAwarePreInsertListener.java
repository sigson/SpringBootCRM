package domain.core.persistence;

import domain.core.access.AccessContextHolder;
import domain.core.access.AccessFlags;
import domain.core.access.AccessLevel;
import domain.core.access.AccessResolver;
import domain.core.access.PermissionRequirement;
import domain.core.access.StructuredAccessDeniedException;
import domain.core.bootstrap.AggregateDescriptor;
import domain.core.bootstrap.FieldDescriptor;
import domain.core.bootstrap.MetadataSnapshot;
import domain.core.bootstrap.MetadataSnapshotProvider;
import domain.core.ddd.AbstractAggregate;
import domain.core.ddd.annotations.AccessChecked;
import org.hibernate.event.spi.PreInsertEvent;
import org.hibernate.event.spi.PreInsertEventListener;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Field-level + repo-level защита INSERT'а.
 *
 * <p>Алгоритм:
 * <ol>
 *   <li>resolveRepository → {@code canInsert()}: если у пользователя нет {@code WRITE_INSERT}
 *       (или admin/root) на тип — отказ через {@link PermissionRequirement#repo};</li>
 *   <li>для каждого нового непустого поля resolve → {@code canInsert()} с учётом
 *       field-level {@code @FieldId.defaultAccess}.</li>
 * </ol>
 *
 * <p>Все точки отказа бросают {@link StructuredAccessDeniedException} с точным
 * требованием, которое фронтенд показывает в центральной модалке.
 */
public class AccessAwarePreInsertListener implements PreInsertEventListener {

    private final ObjectProvider<AccessResolver> resolver;
    private final ObjectProvider<MetadataSnapshotProvider> snapshots;
    private final ObjectProvider<AccessContextHolder> holder;

    public AccessAwarePreInsertListener(ObjectProvider<AccessResolver> resolver,
                                         ObjectProvider<MetadataSnapshotProvider> snapshots,
                                         ObjectProvider<AccessContextHolder> holder) {
        this.resolver = resolver;
        this.snapshots = snapshots;
        this.holder = holder;
    }

    @Override
    public boolean onPreInsert(PreInsertEvent ev) {
        if (!(ev.getEntity() instanceof AbstractAggregate<?> agg)) return false;

        AccessChecked ann = agg.getClass().getAnnotation(AccessChecked.class);
        boolean strict = ann == null || ann.strict();

        AccessContextHolder h = holder.getIfAvailable();
        var ctxOpt = h == null ? java.util.Optional.<domain.core.access.AccessContext>empty() : h.tryGet();

        if (ctxOpt.isEmpty()) {
            if (strict) {
                throw new StructuredAccessDeniedException(
                        "Створення «" + agg.getClass().getSimpleName() +
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
        if (!repoLvl.canInsert()) {
            throw new StructuredAccessDeniedException(
                    "Недостатньо прав для створення записів цього типу",
                    PermissionRequirement.repo(typeId, AccessFlags.WRITE_INSERT));
        }

        Object[] newState = ev.getState();
        String[] propNames = ev.getPersister().getPropertyNames();
        AggregateDescriptor desc = snap.aggregate(typeId);

        for (int i = 0; i < propNames.length; i++) {
            FieldDescriptor fd = desc.fieldByPropertyName(propNames[i]);
            if (fd == null) continue;
            if (AuditFieldDetector.isAuditManaged(fd.rawField())) continue;
            Object newVal = newState[i];
            if (newVal == null) continue;

            AccessLevel lvl = r.resolve(typeId, fd.fieldId(), agg, ctx);
            if (!lvl.canInsert()) {
                throw new StructuredAccessDeniedException(
                        "Недостатньо прав для запису у поле «" + fd.name() + "»",
                        PermissionRequirement.field(typeId, fd.name(), AccessFlags.WRITE_INSERT));
            }
        }
        return false;
    }
}
