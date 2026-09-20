package domain.core.ddd;

import domain.core.bootstrap.MetadataSnapshotProvider;
import org.springframework.stereotype.Component;

import java.io.Serializable;

/**
 * DI-фабрика {@link AggregateReference}'ов, резолвит typeId по Class.
 */
@Component
public class AggregateReferenceFactory {

    private final MetadataSnapshotProvider snapshots;

    public AggregateReferenceFactory(MetadataSnapshotProvider snapshots) {
        this.snapshots = snapshots;
    }

    public <T extends AbstractAggregate<ID>, ID extends Serializable>
           AggregateReference<T, ID> of(Class<T> targetClass, ID id) {
        long tid = snapshots.get().typeIdOf(targetClass);
        return AggregateReference.ofRaw(tid, IdCodec.encode(id));
    }

    /** Системный ref: targetIdRaw принимает значение «фиктивного» systemId типа {@code "__SYSTEM__"}. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public AggregateReference system(Class<? extends AbstractAggregate<?>> targetClass, String systemId) {
        long tid = snapshots.get().typeIdOf(targetClass);
        return AggregateReference.ofRaw(tid, systemId);
    }

    public AggregateReference<?, ?> ofRaw(long typeId, String idRaw) {
        return AggregateReference.ofRaw(typeId, idRaw);
    }
}
