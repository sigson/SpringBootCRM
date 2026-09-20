package domain.core.persistence;

import domain.core.ddd.AbstractAggregate;
import domain.core.ddd.LifecyclePhase;
import domain.core.ddd.annotations.DomainCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Сканирует beans на наличие {@link DomainCallback}-методов, валидирует их сигнатуры,
 * и при вызовах из {@link LifecycleProcessor}'а резолвит bean каждый раз заново через
 * {@code applicationContext.getBean(beanName)} — это поддерживает {@code @Transactional}-прокси.
 *
 */
@Component
public class CallbackDispatcher implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(CallbackDispatcher.class);

    private final ApplicationContext ctx;
    private final Map<Long, List<CallbackEntry>> registry = new HashMap<>();

    public CallbackDispatcher(ApplicationContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void afterSingletonsInstantiated() {
        for (String beanName : ctx.getBeanDefinitionNames()) {
            Class<?> beanClass;
            try {
                beanClass = ctx.getType(beanName);
            } catch (Exception e) {
                continue;
            }
            if (beanClass == null) continue;
            ReflectionUtils.doWithMethods(beanClass, m -> {
                DomainCallback ann = m.getAnnotation(DomainCallback.class);
                if (ann == null) return;
                validate(m);
                CallbackEntry entry = new CallbackEntry(beanName, m, ann);
                registry.computeIfAbsent(ann.typeId(), k -> new ArrayList<>()).add(entry);
            });
        }
        log.info("CallbackDispatcher: registered {} callbacks across {} types",
                registry.values().stream().mapToInt(List::size).sum(),
                registry.size());
    }

    public void dispatch(AbstractAggregate<?> agg, long typeId,
                         LifecyclePhase phase, DomainCallback.Phase callbackPhase) {
        List<CallbackEntry> list = registry.get(typeId);
        if (list == null || list.isEmpty()) return;
        for (CallbackEntry entry : list) {
            DomainCallback c = entry.ann();
            if (c.phase() != callbackPhase) continue;
            if (!matchesKind(c.kind(), phase)) continue;
            Object bean = ctx.getBean(entry.beanName());
            try {
                entry.method().setAccessible(true);
                if (entry.method().getParameterCount() == 1) {
                    entry.method().invoke(bean, agg);
                } else if (entry.method().getParameterCount() == 2) {
                    entry.method().invoke(bean, agg, phase);
                } else {
                    entry.method().invoke(bean);
                }
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("DomainCallback failed: " + entry, e);
            }
        }
    }

    private boolean matchesKind(DomainCallback.Kind k, LifecyclePhase p) {
        return k == DomainCallback.Kind.ANY || k.name().equals(p.name());
    }

    private static void validate(Method m) {
        Class<?>[] params = m.getParameterTypes();
        if (params.length == 0) return;
        if (params.length > 2) {
            throw new IllegalStateException("@DomainCallback must have 0,1,or 2 params: " + m);
        }
        if (params.length >= 1 && !AbstractAggregate.class.isAssignableFrom(params[0])) {
            throw new IllegalStateException(
                    "@DomainCallback first param must be AbstractAggregate-subclass: " + m);
        }
        if (params.length == 2 && params[1] != LifecyclePhase.class) {
            throw new IllegalStateException(
                    "@DomainCallback second param (if present) must be LifecyclePhase: " + m);
        }
    }

    private record CallbackEntry(String beanName, Method method, DomainCallback ann) {}
}
