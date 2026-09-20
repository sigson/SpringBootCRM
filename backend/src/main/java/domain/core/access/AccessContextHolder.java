package domain.core.access;

import org.springframework.stereotype.Component;

import java.io.Closeable;
import java.io.IOException;
import java.util.Optional;

/**
 * ThreadLocal-держатель {@link AccessContext} с {@code Closeable}-binding'ом.
 *
 * <p>Используется через try-with-resources:
 * <pre>{@code
 *     try (var ignored = holder.bind(ctx)) {
 *         // работа в этом контексте
 *     }   // автоматически восстанавливает предыдущий ctx
 * }</pre>
 *
 * <p>Для {@code @Async}/Reactor/VT-пропагации используется {@code Micrometer ContextSnapshot}
 * (см. {@code ContextPropagationConfig}).
 */
@Component
public class AccessContextHolder {

    private static final ThreadLocal<AccessContext> CTX = new ThreadLocal<>();
    public static final String CONTEXT_KEY = "ddd.access.context";

    public Optional<AccessContext> tryGet() {
        return Optional.ofNullable(CTX.get());
    }

    public AccessContext getOrThrow() {
        AccessContext c = CTX.get();
        if (c == null) {
            throw new IllegalStateException(
                    "AccessContext not bound on this thread. " +
                            "Ensure ContextSnapshot.captureAll() is used for off-thread propagation, " +
                            "or wrap the call in holder.bind(SystemAccessContexts.X(...)).");
        }
        return c;
    }

    public Closeable bind(AccessContext ctx) {
        AccessContext prev = CTX.get();
        CTX.set(ctx);
        return new Restorer(prev);
    }

    public void clear() {
        CTX.remove();
    }

    public AccessContext rawGet() {
        return CTX.get();
    }

    public void rawSet(AccessContext c) {
        if (c == null) CTX.remove(); else CTX.set(c);
    }

    /** Восстанавливает предыдущий ctx на close(). */
    private static final class Restorer implements Closeable {
        private final AccessContext prev;
        Restorer(AccessContext prev) { this.prev = prev; }
        @Override public void close() throws IOException {
            if (prev == null) CTX.remove(); else CTX.set(prev);
        }
    }
}
