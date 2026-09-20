package domain.core.ddd;

import java.util.UUID;

/** UserType для {@code AggregateReference} с UUID-id. */
public class AggregateReferenceUuidUserType extends AbstractAggregateReferenceUserType<UUID> {

    @Override protected Class<?> mappingEmbeddable() { return AggregateReferenceUuidState.class; }
    @Override protected Class<UUID> idClass() { return UUID.class; }
}
