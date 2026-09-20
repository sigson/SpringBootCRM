package domain.core.audit;

import domain.core.access.AccessContextHolder;
import domain.core.access.SystemAccessContexts;
import domain.core.ddd.AggregateReference;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

import java.util.Optional;

/** AuditorAware: principalRef для @CreatedBy/@LastModifiedBy. */
@Configuration
@EnableJpaAuditing(auditorAwareRef = "principalRefAuditor")
public class AuditingConfig {

    private final AccessContextHolder holder;
    private final SystemAccessContexts systems;

    public AuditingConfig(AccessContextHolder holder, SystemAccessContexts systems) {
        this.holder = holder;
        this.systems = systems;
    }

    @org.springframework.context.annotation.Bean
    public AuditorAware<AggregateReference<?, ?>> principalRefAuditor() {
        return () -> {
            var ctx = holder.tryGet().orElse(systems.systemReadOnly());
            @SuppressWarnings({"rawtypes", "unchecked"})
            AggregateReference ref = (AggregateReference) ctx.principalRef();
            return Optional.ofNullable(ref);
        };
    }
}
