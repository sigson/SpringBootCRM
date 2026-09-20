package domain.core.persistence;

import domain.core.ddd.AbstractAggregate;
import domain.core.ddd.LifecyclePhase;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreRemove;
import jakarta.persistence.PreUpdate;
import org.springframework.context.ApplicationContext;

/**
 * JPA-listener, мостящий PrePersist/PreUpdate/PreRemove к {@link LifecycleProcessor}.
 *
 * <p>JPA не резолвит этот класс через DI — поэтому используется static {@code APP_CTX}-bridge,
 * заполняемый {@link LifecycleProcessorBootstrap}.
 *
 * <p>{@code IN_FLUSH} ThreadLocal используется {@code InFlushDatabaseAccessGuard}'ом для
 * блокировки БД-операций из доменных хуков на фазе PRE_FLUSH.
 *
 */
public class AggregateLifecycleListener {

    public static final ThreadLocal<Boolean> IN_FLUSH = new ThreadLocal<>();

    private static volatile ApplicationContext APP_CTX;

    public static void init(ApplicationContext ctx) {
        APP_CTX = ctx;
    }

    @PrePersist
    public void onPrePersist(Object e) {
        runPre(e, LifecyclePhase.CREATE);
    }

    @PreUpdate
    public void onPreUpdate(Object e) {
        runPre(e, LifecyclePhase.UPDATE);
    }

    @PreRemove
    public void onPreRemove(Object e) {
        runPre(e, LifecyclePhase.DELETE);
    }

    private void runPre(Object entity, LifecyclePhase phase) {
        if (!(entity instanceof AbstractAggregate<?> agg)) return;
        ApplicationContext ctx = APP_CTX;
        if (ctx == null) return;     // bootstrap window
        LifecycleProcessor lp = ctx.getBean(LifecycleProcessor.class);

        IN_FLUSH.set(Boolean.TRUE);
        try {
            lp.runPreFlush(agg, phase);
        } finally {
            IN_FLUSH.remove();
        }
    }
}
