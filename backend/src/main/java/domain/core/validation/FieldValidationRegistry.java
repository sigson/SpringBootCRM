package domain.core.validation;

import domain.core.bootstrap.AggregateDescriptor;
import domain.core.bootstrap.FieldDescriptor;
import domain.core.bootstrap.MetadataSnapshot;
import domain.core.bootstrap.MetadataSnapshotProvider;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.DependsOn;
import org.springframework.core.GenericTypeResolver;
import org.springframework.stereotype.Component;

import java.lang.annotation.Annotation;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <h2>Реестр валидационных ограничений на реквизитах сущностей.</h2>
 *
 * <p>На старте строит индекс {@code typeId -> поле -> List<FieldConstraint>}.
 * Какие аннотации являются ограничениями — определяется набором бинов
 * {@link FieldValidator}, которые Spring внедряет сюда списком: тип аннотации
 * берётся из параметра {@code A} валидатора ({@code FieldValidator<A>}).
 *
 * <p><b>Расширяемость без правок сервиса.</b> Новый вид валидации = новый
 * интерфейс-валидатор (default-метод {@code validate}) + бин. Этот класс,
 * {@code ValidationService} и {@code FieldConstraint} при этом не меняются —
 * валидатор подхватывается автоматически по типу его аннотации.
 */
@Component
@DependsOn("metadataBootstrapper")
public class FieldValidationRegistry {

    private final MetadataSnapshotProvider snapshots;
    private final List<FieldValidator<?>> validators;

    /** typeId -> (fieldName -> constraints). Неизменяем после init(). */
    private final Map<Long, Map<String, List<FieldConstraint>>> byTypeId = new LinkedHashMap<>();

    public FieldValidationRegistry(MetadataSnapshotProvider snapshots,
                                   List<FieldValidator<?>> validators) {
        this.snapshots = snapshots;
        this.validators = validators;
    }

    @PostConstruct
    @SuppressWarnings("unchecked")
    public void init() {
        // Тип аннотации (параметр A у FieldValidator<A>) -> сам валидатор.
        Map<Class<? extends Annotation>, FieldValidator<?>> byAnnotation = new HashMap<>();
        for (FieldValidator<?> v : validators) {
            Class<?> a = GenericTypeResolver.resolveTypeArgument(v.getClass(), FieldValidator.class);
            if (a != null) byAnnotation.put((Class<? extends Annotation>) a, v);
        }

        MetadataSnapshot snap = snapshots.get();
        for (AggregateDescriptor agg : snap.allAggregates()) {
            Map<String, List<FieldConstraint>> perField = new LinkedHashMap<>();
            for (FieldDescriptor fd : agg.fields()) {
                if (fd.parentChain().length > 0) continue;   // только top-level реквизиты
                List<FieldConstraint> constraints = FieldConstraint.fromField(fd.rawField(), byAnnotation);
                if (!constraints.isEmpty()) {
                    perField.put(fd.rawField().getName(), List.copyOf(constraints));
                }
            }
            if (!perField.isEmpty()) {
                byTypeId.put(agg.typeId(), Map.copyOf(perField));
            }
        }
    }

    /** Ограничения конкретного реквизита (пустой список, если их нет). */
    public List<FieldConstraint> forField(long typeId, String field) {
        Map<String, List<FieldConstraint>> m = byTypeId.get(typeId);
        if (m == null) return List.of();
        return m.getOrDefault(field, List.of());
    }

    /** Все реквизиты типа, на которых есть ограничения: fieldName -> constraints. */
    public Map<String, List<FieldConstraint>> forType(long typeId) {
        return byTypeId.getOrDefault(typeId, Map.of());
    }

    /** Есть ли вообще ограничения у типа. */
    public boolean hasConstraints(long typeId) {
        return byTypeId.containsKey(typeId);
    }
}
