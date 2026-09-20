package domain.core.access;

import domain.core.bootstrap.AggregateDescriptor;
import domain.core.bootstrap.FieldDescriptor;
import domain.core.bootstrap.MetadataSnapshotProvider;
import domain.core.ddd.AbstractAggregate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/**
 * Композиция эффективного {@link AccessLevel} для (typeId, fieldId, instance, ctx).
 *
 * <p>Каноническая формула:
 * <pre>
 *  effectiveField(user, typeId, fieldId, instance) =
 *        defaultFromFieldId(typeId, fieldId)                    // base (из @FieldId)
 *     ∩  globalGrants.evaluate(typeId, fieldId, user)            // field-level overrides из конфига
 *     ∩  instance.ownAccess.effectiveLevel(user)                 // instance ACL
 *     ∪  user.adminRootBypass(typeId)                            // bypass-слой
 *
 *  effectiveRepo(user, typeId) =
 *        agg.defaultRepoAccess()
 *     ∪  AccessLevel(user.globalFlags | user.typeFlags[typeId], ANY)
 * </pre>
 *
 * <p>String-ролей нет: per-type права декларируются исключительно через
 * {@code user.typeFlags[typeId]} = OR-комбинация {@link AccessFlags}-битов.
 */
@Component
public class AccessResolver {

    private final MetadataSnapshotProvider snapshots;
    private final UserAccessProvider users;

    public AccessResolver(MetadataSnapshotProvider snapshots, UserAccessProvider users) {
        this.snapshots = snapshots;
        this.users = users;
    }

    public AccessLevel resolve(long typeId, long fieldId,
                               @Nullable AbstractAggregate<?> instance,
                               AccessContext ctx) {
        var snap = snapshots.get();
        AggregateDescriptor agg = snap.aggregate(typeId);
        FieldDescriptor fd = agg.field(fieldId);
        if (fd == null) {
            return AccessLevel.HIDDEN;     // unknown field — deny
        }
        AccessMetric um = users.metricFor(ctx);

        AccessLevel base   = fd.defaultAccess();
        AccessLevel global = snap.globalGrants().evaluate(typeId, fieldId, um);
        AccessLevel inst   = instance == null
                ? AccessLevel.READ_WRITE
                : effectiveOwnAccessFor(instance.ownAccess(), um, typeId, instance.getId(), ctx);

        AccessLevel plain = base.intersect(global).intersect(inst);

        int bypassFlags = um.globalFlags() | um.typeFlags().getOrDefault(typeId, 0);
        // Автопроброс: для табличной части права пользователя к владельцу
        // дополняют его права к самой табличной части (см. AbstractTabularPart).
        Long ownerTypeId = snap.tabularOwnerTypeId(typeId);
        if (ownerTypeId != null) {
            bypassFlags |= um.typeFlags().getOrDefault(ownerTypeId, 0);
        }
        if (bypassFlags == 0) return plain;
        return plain.union(new AccessLevel(bypassFlags, WriteMode.ANY));
    }

    public AccessLevel resolveRepository(long typeId, AccessContext ctx) {
        AccessMetric um = users.metricFor(ctx);
        var snap = snapshots.get();
        AccessLevel typeDefault = snap.aggregate(typeId).defaultRepoAccess();

        int userFlags = um.globalFlags() | um.typeFlags().getOrDefault(typeId, 0);
        // Автопроброс прав владельца на его табличную часть (repo-level).
        Long ownerTypeId = snap.tabularOwnerTypeId(typeId);
        if (ownerTypeId != null) {
            userFlags |= um.typeFlags().getOrDefault(ownerTypeId, 0);
        }
        return typeDefault.union(new AccessLevel(userFlags, WriteMode.ANY));
    }

    /** Шорткат для bypass-проверок и hash'а. */
    public AccessMetric userMetricFor(AccessContext ctx) {
        return users.metricFor(ctx);
    }

    /**
     * Эффективный уровень из per-instance ACL: <b>кто именно</b> может писать этот
     * экземпляр.
     *
     * <p>Whitelist ведётся с двух сторон, и обе имеют смысл:
     * <ul>
     *   <li><b>со стороны экземпляра</b> — {@code instance.ownAccess().instanceWriteAcl()}
     *       перечисляет субъектов в виде {@code typeId субъекта → id субъектов}
     *       («писать эту запись могут вот эти пользователи»);</li>
     *   <li><b>со стороны пользователя</b> — {@code userMetric.instanceWriteAcl()}
     *       перечисляет цели в виде {@code typeId цели → id экземпляров}
     *       («этому пользователю выдано право на вот эти записи»); такой whitelist
     *       приходит из роли и переживает {@code AccessMetricPayload.union}.</li>
     * </ul>
     * Совпадения достаточно с любой стороны: обе записи выражают одно и то же
     * разрешение, просто заведённое из разных мест.
     *
     * <p><b>Пустой ACL не ограничивает.</b> Отсутствие whitelist'а означает «построчных
     * ограничений нет», а не «запрещено всем»: иначе включение per-instance ACL на
     * одном агрегате закрыло бы запись во всех остальных.
     *
     * <p>Ограничение только на запись: уровень сужается до {@link AccessLevel#READ_ONLY},
     * а не до {@link AccessLevel#HIDDEN} — поле называется {@code instanceWriteAcl},
     * видимость оно не регулирует. Сужение применяется пересечением, поэтому
     * admin/root-bypass (он добавляется union'ом уже после) продолжает работать.
     */
    static AccessLevel effectiveOwnAccessFor(@Nullable AccessMetric ownAccess,
                                             @Nullable AccessMetric userMetric,
                                             long typeId,
                                             @Nullable Object instanceId,
                                             @Nullable AccessContext ctx) {
        // ACL на экземпляре не задан — построчных ограничений у него нет.
        if (ownAccess == null || ownAccess.instanceWriteAcl().isEmpty()) {
            return AccessLevel.READ_WRITE;
        }

        // Системный контекст пишет всегда: bootstrap, миграции и фоновые задачи не
        // являются субъектом доступа и в whitelist'ах не значатся. Полагаться на
        // bypass-флаги здесь нельзя — системная метрика бывает пустой.
        if (ctx != null && ctx.isSystem()) return AccessLevel.READ_WRITE;

        if (ctx != null && ctx.principalRef() != null) {
            var principal = ctx.principalRef();
            var allowed = ownAccess.instanceWriteAcl().get(principal.targetTypeId());
            if (allowed != null && allowed.contains(principal.targetIdRaw())) {
                return AccessLevel.READ_WRITE;
            }
        }

        if (userMetric != null && instanceId != null) {
            var granted = userMetric.instanceWriteAcl().get(typeId);
            if (granted != null && granted.contains(String.valueOf(instanceId))) {
                return AccessLevel.READ_WRITE;
            }
        }

        return AccessLevel.READ_ONLY;
    }
}
