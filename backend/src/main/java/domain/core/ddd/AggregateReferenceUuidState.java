package domain.core.ddd;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

/**
 * Mapping-class для {@code AggregateReferenceUuidUserType}: native UUID-колонка для targetIdRaw.
 *
 * <p>Реально аннотирован {@code @Embeddable} с геттерами/сеттерами — это требование
 * Hibernate 6 для {@code CompositeUserType.embeddable()}.
 *
 */
@Embeddable
public class AggregateReferenceUuidState {

    @Column(name = "target_type_id", nullable = false)
    private long targetTypeId;

    @JdbcTypeCode(SqlTypes.UUID)
    @Column(name = "target_id_raw", nullable = false)
    private UUID targetIdRaw;

    public long getTargetTypeId() { return targetTypeId; }
    public void setTargetTypeId(long v) { this.targetTypeId = v; }

    public UUID getTargetIdRaw() { return targetIdRaw; }
    public void setTargetIdRaw(UUID v) { this.targetIdRaw = v; }
}
