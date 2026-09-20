package domain.core.persistence;

import domain.core.bootstrap.MetadataSnapshotProvider;
import jakarta.annotation.PostConstruct;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

/**
 * Bootstrap-bean, заполняющий static {@code APP_CTX} в {@link AggregateLifecycleListener}'е
 * и {@link SafeToString#init} — для JPA-listener'ов, которые не получают DI через Spring.
 */
@Component
public class LifecycleProcessorBootstrap {

    private final ApplicationContext ctx;
    private final MetadataSnapshotProvider snapshots;

    public LifecycleProcessorBootstrap(ApplicationContext ctx,
                                        MetadataSnapshotProvider snapshots) {
        this.ctx = ctx;
        this.snapshots = snapshots;
    }

    @PostConstruct
    public void init() {
        AggregateLifecycleListener.init(ctx);
        SafeToString.init(snapshots);
    }
}
