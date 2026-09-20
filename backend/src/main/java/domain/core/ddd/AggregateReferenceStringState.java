package domain.core.ddd;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Embeddable
public class AggregateReferenceStringState {

    @Column(name = "target_type_id", nullable = false)
    private long targetTypeId;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "target_id_raw", nullable = false)
    private String targetIdRaw;

    public long getTargetTypeId() { return targetTypeId; }
    public void setTargetTypeId(long v) { this.targetTypeId = v; }

    public String getTargetIdRaw() { return targetIdRaw; }
    public void setTargetIdRaw(String v) { this.targetIdRaw = v; }
}
