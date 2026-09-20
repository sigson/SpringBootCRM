package domain.core.ddd;

/** UserType для {@code AggregateReference} с Long-id. */
public class AggregateReferenceLongUserType extends AbstractAggregateReferenceUserType<Long> {

    @Override protected Class<?> mappingEmbeddable() { return AggregateReferenceLongState.class; }
    @Override protected Class<Long> idClass() { return Long.class; }
}
