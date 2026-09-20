package domain.core.ddd;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Embeddable
public class AggregateReferenceLongState {

    @Column(name = "target_type_id", nullable = false)
    private long targetTypeId;

    @JdbcTypeCode(SqlTypes.BIGINT)
    @Column(name = "target_id_raw", nullable = false)
    private Long targetIdRaw;

    public long getTargetTypeId() { return targetTypeId; }
    public void setTargetTypeId(long v) { this.targetTypeId = v; }

    public Long getTargetIdRaw() { return targetIdRaw; }
    public void setTargetIdRaw(Long v) { this.targetIdRaw = v; }
}
