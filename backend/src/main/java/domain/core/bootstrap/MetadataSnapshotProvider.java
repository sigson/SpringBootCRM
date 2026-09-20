package domain.core.bootstrap;

import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * DI-обёртка над {@link MetadataSnapshot} с {@code Optional} static-fallback.
 *
 * <p>{@link #get()} бросает {@link IllegalStateException} если snapshot ещё не построен —
 * это сигнализирует о попытке использовать DDD-слой до окончания bootstrap'а.
 *
 * <p>{@link #staticGet()} возвращает {@link Optional} — для безопасной работы в
 * bootstrap-окне (например, из {@link domain.core.persistence.SafeToString}).
 *
 */
@Component
public class MetadataSnapshotProvider {

    private static volatile MetadataSnapshot STATIC_REF;

    private volatile MetadataSnapshot snapshot;

    public MetadataSnapshot get() {
        MetadataSnapshot s = snapshot;
        if (s == null) {
            throw new IllegalStateException(
                    "MetadataSnapshot not yet built. Bootstrap not complete?");
        }
        return s;
    }

    public Optional<MetadataSnapshot> staticGet() {
        return Optional.ofNullable(STATIC_REF);
    }

    public void publish(MetadataSnapshot s) {
        this.snapshot = s;
        STATIC_REF = s;
    }
}
