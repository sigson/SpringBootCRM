package domain.core.eventing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import domain.core.ddd.AggregateRef;
import domain.core.ddd.DomainEventEnvelope;
import domain.core.ddd.annotations.DomainEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Записывает доменное событие в outbox в той же TX, что и изменение агрегата.
 *
 * <p>{@code @Transactional(MANDATORY)} — гарантия, что метод не создаёт новую TX случайно.
 * Сериализация — Jackson; в production'е добавить {@code @DomainEvent.stableName}/.{@code version}
 * проверку через {@link DomainEventRegistry}.
 *
 */
@Component
public class OutboxWriter {

    private final OutboxJpaRepository repo;
    private final ObjectMapper mapper;

    public OutboxWriter(OutboxJpaRepository repo, ObjectMapper mapper) {
        this.repo = repo;
        this.mapper = mapper;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void write(DomainEventEnvelope evt) {
        OutboxEntity row = new OutboxEntity();
        AggregateRef ref = evt.aggregateRef();
        row.setAggTypeId(ref.typeId());
        row.setAggIdRaw(ref.idRaw());
        row.setEventType(evt.getClass().getName());
        DomainEvent ann = evt.getClass().getAnnotation(DomainEvent.class);
        row.setStableName(ann == null ? evt.getClass().getSimpleName() : ann.stableName());
        row.setVersion(ann == null ? 1 : ann.version());
        try {
            row.setPayloadJson(mapper.writeValueAsString(evt));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Outbox serialization failed", e);
        }
        row.setCreatedAt(Instant.now());
        repo.save(row);
    }
}
