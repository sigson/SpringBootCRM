package app.springbootcrm.metadata;

import domain.core.web.TypeLabelResolver;
import org.springframework.stereotype.Component;

/**
 * Bridge {@link TypeRegistry} → {@link TypeLabelResolver}-SPI из ядра.
 *
 * <p>Зависимость направлена правильно: {@code app.springbootcrm} знает про {@code domain.core},
 * не наоборот. Этот bean подхватывается {@code ErrorEnvelopeAdvice} через
 * {@code ObjectProvider<TypeLabelResolver>} и используется для обогащения
 * {@code PermissionRequirement.typeLabel} в JSON-ответах.
 */
@Component
public class TypeRegistryTypeLabelResolver implements TypeLabelResolver {

    private final TypeRegistry registry;

    public TypeRegistryTypeLabelResolver(TypeRegistry registry) {
        this.registry = registry;
    }

    @Override
    public String labelFor(long typeId) {
        TypeRegistry.TypeDescriptor td = registry.byTypeId(typeId);
        return td == null ? null : td.singularLabel();
    }
}
