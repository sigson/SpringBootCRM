package domain.core.eventing;

import domain.core.ddd.annotations.DomainEvent;
import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Сканирует все {@link DomainEvent}-аннотированные классы и валидирует уникальность
 * {@code stableName}. Уникальность критична для эволюции схемы Kafka-payload'ов.
 *
 */
@Component
public class DomainEventRegistry implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(DomainEventRegistry.class);

    private final Map<String, Class<?>> byStableName = new HashMap<>();

    @Override
    public void afterSingletonsInstantiated() {
        try (ScanResult sr = new ClassGraph()
                .acceptPackages("domain", "app")
                .enableClassInfo()
                .enableAnnotationInfo()
                .scan()) {
            for (var ci : sr.getClassesWithAnnotation(DomainEvent.class.getName())) {
                Class<?> cls = ci.loadClass();
                DomainEvent ann = cls.getAnnotation(DomainEvent.class);
                String key = ann.stableName() + "@v" + ann.version();
                Class<?> prev = byStableName.put(key, cls);
                if (prev != null) {
                    throw new IllegalStateException(
                            "Duplicate @DomainEvent stableName=" + ann.stableName() +
                                    ", version=" + ann.version() + ": " + cls + ", " + prev);
                }
            }
        }
        log.info("DomainEventRegistry: scanned {} domain events", byStableName.size());
    }

    public Set<String> registeredKeys() {
        return new HashSet<>(byStableName.keySet());
    }
}
