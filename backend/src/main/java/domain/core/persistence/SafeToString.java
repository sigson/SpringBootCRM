package domain.core.persistence;

import domain.core.access.AccessContext;
import domain.core.access.AccessFlags;
import domain.core.access.AccessLevel;
import domain.core.access.AccessMetric;
import domain.core.access.WriteMode;
import domain.core.bootstrap.AggregateDescriptor;
import domain.core.bootstrap.FieldDescriptor;
import domain.core.bootstrap.MetadataSnapshot;
import domain.core.bootstrap.MetadataSnapshotProvider;
import domain.core.ddd.AbstractAggregate;
import org.springframework.security.access.AccessDeniedException;

/**
 * Утилита безопасного {@code toString}'а / debugDump'а для {@link AbstractAggregate}.
 *
 * <p>{@link #render} — null-safe; ВСЕГДА маскирует {@code HIDDEN}/{@code WRITE_ONLY} поля,
 * даже для root-пользователей (это логи; root-debugger использует {@link #debugRender}).
 *
 * <p>{@link #debugRender} — полное представление БЕЗ маскирования; требует {@code ROOT_READ}
 * (глобальный или per-type). Иначе бросает {@link AccessDeniedException}.
 *
 */
public final class SafeToString {

    private SafeToString() {}

    private static volatile MetadataSnapshotProvider PROVIDER;

    public static void init(MetadataSnapshotProvider p) {
        PROVIDER = p;
    }

    public static String render(AbstractAggregate<?> agg) {
        if (agg == null) return "null";
        MetadataSnapshotProvider p = PROVIDER;
        if (p == null) return identity(agg);
        try {
            MetadataSnapshot snap = p.staticGet().orElse(null);
            if (snap == null) return identity(agg);
            long typeId = snap.typeIdOf(agg.getClass());
            AggregateDescriptor desc = snap.aggregate(typeId);
            StringBuilder sb = new StringBuilder(agg.getClass().getSimpleName())
                    .append('{').append("id=").append(agg.getId());
            for (FieldDescriptor fd : desc.fields()) {
                if (fd.parentFieldId() != -1) continue;     // только top-level
                if (fd.defaultAccess() == AccessLevel.HIDDEN
                        || fd.defaultAccess().mode() == WriteMode.WRITE_ONLY) {
                    sb.append(", ").append(fd.shortName()).append("=***");
                } else {
                    sb.append(", ").append(fd.shortName()).append('=').append(fd.read(agg));
                }
            }
            sb.append('}');
            return sb.toString();
        } catch (Exception e) {
            return identity(agg);
        }
    }

    public static String debugRender(AbstractAggregate<?> agg, AccessContext ctx) {
        if (agg == null) return "null";
        MetadataSnapshotProvider p = PROVIDER;
        if (p == null) throw new IllegalStateException("MetadataSnapshot not yet built");
        MetadataSnapshot snap = p.get();
        long typeId = snap.typeIdOf(agg.getClass());

        AccessMetric um = ctx.resolver().userMetricFor(ctx);
        int g = AccessFlags.expand(um.globalFlags());
        int t = AccessFlags.expand(um.typeFlags().getOrDefault(typeId, 0));
        boolean rootRead = ((g & AccessFlags.ROOT_READ) != 0)
                || ((t & AccessFlags.ROOT_READ) != 0);
        if (!rootRead) {
            throw new AccessDeniedException("debugDump requires ROOT_READ for typeId=" + typeId);
        }

        AggregateDescriptor desc = snap.aggregate(typeId);
        StringBuilder sb = new StringBuilder(agg.getClass().getSimpleName())
                .append('{').append("id=").append(agg.getId());
        for (FieldDescriptor fd : desc.fields()) {
            if (fd.parentFieldId() != -1) continue;
            sb.append(", ").append(fd.shortName()).append('=').append(fd.read(agg));
        }
        sb.append('}');
        return sb.toString();
    }

    private static String identity(Object o) {
        return o.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(o));
    }
}
