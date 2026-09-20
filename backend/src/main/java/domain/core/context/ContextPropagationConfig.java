package domain.core.context;

import domain.core.access.AccessContext;
import domain.core.access.AccessContextHolder;
import io.micrometer.context.ContextRegistry;
import io.micrometer.context.ThreadLocalAccessor;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Регистрирует {@link ThreadLocalAccessor} для {@link AccessContext} в Micrometer
 * {@link ContextRegistry}, чтобы snapshot'ы корректно работали с
 * {@code @Async}/Reactor/Virtual Threads.
 *
 */
@Configuration(proxyBeanMethods = false)
public class ContextPropagationConfig {

    private final AccessContextHolder holder;

    public ContextPropagationConfig(AccessContextHolder holder) {
        this.holder = holder;
    }

    @PostConstruct
    public void registerAccessor() {
        ContextRegistry.getInstance()
                .registerThreadLocalAccessor(new AccessContextAccessor(holder));
    }

    private static final class AccessContextAccessor implements ThreadLocalAccessor<AccessContext> {

        private final AccessContextHolder holder;

        AccessContextAccessor(AccessContextHolder holder) {
            this.holder = holder;
        }

        @Override public Object key() { return AccessContextHolder.CONTEXT_KEY; }

        @Override
        public AccessContext getValue() {
            return holder.rawGet();
        }

        @Override
        public void setValue(AccessContext value) {
            holder.rawSet(value);
        }

        @Override
        public void setValue() {
            holder.clear();
        }
    }
}
