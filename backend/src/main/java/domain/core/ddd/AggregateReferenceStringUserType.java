package domain.core.ddd;

/** UserType для {@code AggregateReference} со String-id. */
public class AggregateReferenceStringUserType extends AbstractAggregateReferenceUserType<String> {

    @Override protected Class<?> mappingEmbeddable() { return AggregateReferenceStringState.class; }
    @Override protected Class<String> idClass() { return String.class; }
}
