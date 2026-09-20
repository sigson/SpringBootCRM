package domain.core.access;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import domain.core.bootstrap.MetadataSnapshotProvider;
import domain.core.ddd.AggregateReference;
import domain.core.ddd.UserAggregate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;

/**
 * Кеширующий резолвер {@link AccessMetric}, обёртывающий первый load в узкий
 * bootstrap-контекст {@code systemReadOnlyForType(userTypeId)} — это разрывает
 * потенциальный цикл «findById → AccessFilterActivator → metricFor → findById».
 *
 */
@Component
public class CaffeineUserAccessProvider implements UserAccessProvider {

    private static final Logger log = LoggerFactory.getLogger(CaffeineUserAccessProvider.class);

    private final ObjectProvider<UserAggregateLoader> loaderProvider;
    private final ObjectProvider<SystemAccessContexts> systemsProvider;
    private final ObjectProvider<MetadataSnapshotProvider> snapshotsProvider;
    private final AccessContextHolder holder;

    /** L1 кэш: до 10K user'ов, TTL 5 минут. Инвалидация — {@link #evictUser(String, String)}. */
    private final Cache<String, AccessMetric> cache = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(Duration.ofMinutes(5))
            .build();

    public CaffeineUserAccessProvider(ObjectProvider<UserAggregateLoader> loaderProvider,
                                       ObjectProvider<SystemAccessContexts> systemsProvider,
                                       ObjectProvider<MetadataSnapshotProvider> snapshotsProvider,
                                       AccessContextHolder holder) {
        this.loaderProvider = loaderProvider;
        this.systemsProvider = systemsProvider;
        this.snapshotsProvider = snapshotsProvider;
        this.holder = holder;
    }

    @Override
    public AccessMetric metricFor(AccessContext ctx) {
        if (ctx.isSystem()) {
            if (ctx.auth() instanceof SystemAuthentication sa) {
                return sa.synthesizedMetric();
            }
            return AccessMetric.empty();
        }
        AggregateReference<? extends UserAggregate<?>, ?> ref = ctx.principalRef();
        if (ref == null) return AccessMetric.empty();
        String cacheKey = ref.targetTypeId() + ":" + ref.targetIdRaw();
        return cache.get(cacheKey, k -> loadFromDb(ref));
    }

    @Override
    public void evictUser(String typeId, String idRaw) {
        cache.invalidate(typeId + ":" + idRaw);
    }

    @Override
    public void warmCache(long typeId, String idRaw, AccessMetric metric) {
        if (metric == null) return;
        cache.put(typeId + ":" + idRaw, metric);
    }

    private AccessMetric loadFromDb(AggregateReference<? extends UserAggregate<?>, ?> ref) {
        UserAggregateLoader loader = loaderProvider.getIfAvailable();
        if (loader == null) {
            log.warn("UserAggregateLoader not available — returning empty AccessMetric for {}", ref);
            return AccessMetric.empty();
        }
        SystemAccessContexts systems = systemsProvider.getIfAvailable();
        if (systems == null) {
            return loader.findByRef(ref).map(UserAggregate::getAccess).orElse(AccessMetric.empty());
        }
        long userTypeId = ref.targetTypeId();
        try (var ignored = holder.bind(systems.systemReadOnlyForType(userTypeId))) {
            return loader.findByRef(ref).map(UserAggregate::getAccess).orElse(AccessMetric.empty());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
