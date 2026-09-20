package domain.core.access;

import domain.core.bootstrap.MetadataSnapshotProvider;
import domain.core.ddd.AggregateReference;
import domain.core.ddd.AggregateReferenceFactory;
import domain.core.ddd.UserAggregate;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Singleton-cached системные contexts.
 *
 * <p>Инициализация — ленивая (через {@link #ensureInitialized()}), чтобы не зависеть от
 * порядка bean-init'а: bootstrap может ещё не закончиться к моменту создания этого бина.
 * Любой первый вызов после публикации snapshot'а сделает init корректно.
 */
@Component
public class SystemAccessContexts {

    private final MetadataSnapshotProvider snapshots;
    private final AccessResolver resolver;
    private final AccessKeyHasher hasher;
    private final AggregateReferenceFactory refs;
    private final UserAggregateClassProvider userClass;

    private volatile AccessContext readOnlyCached;
    private volatile AccessContext readWriteCached;
    private volatile AccessContext maxPrivilegesCached;

    private final ConcurrentHashMap<Long, AccessContext> readOnlyForTypeCache = new ConcurrentHashMap<>();

    public SystemAccessContexts(MetadataSnapshotProvider snapshots,
                                 AccessResolver resolver,
                                 AccessKeyHasher hasher,
                                 AggregateReferenceFactory refs,
                                 UserAggregateClassProvider userClass) {
        this.snapshots = snapshots;
        this.resolver = resolver;
        this.hasher = hasher;
        this.refs = refs;
        this.userClass = userClass;
    }

    public AccessContext systemReadOnly() {
        ensureInitialized();
        return readOnlyCached;
    }

    public AccessContext systemReadWrite() {
        ensureInitialized();
        return readWriteCached;
    }

    public AccessContext maxPrivileges() {
        ensureInitialized();
        return maxPrivilegesCached;
    }

    /** Узкий bypass: ROOT_READ ТОЛЬКО для конкретного typeId. */
    public AccessContext systemReadOnlyForType(long typeId) {
        return readOnlyForTypeCache.computeIfAbsent(typeId,
                tid -> build(SystemAuthentication.readOnlyFor(tid)));
    }

    private synchronized void ensureInitialized() {
        if (readOnlyCached != null) return;
        readOnlyCached      = build(SystemAuthentication.readOnly());
        readWriteCached     = build(SystemAuthentication.readWrite());
        maxPrivilegesCached = build(SystemAuthentication.maxPrivileged());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private AccessContext build(SystemAuthentication auth) {
        var snap = snapshots.get();
        Class<? extends UserAggregate<?>> uc = userClass.userClass();
        AggregateReference ref = refs.system(uc, AccessContext.SYSTEM_PRINCIPAL_ID);
        return new AccessContext(auth, (AggregateReference) ref,
                resolver, snap, ProjectionDirection.OUTBOUND, hasher.lightHash(auth));
    }
}
