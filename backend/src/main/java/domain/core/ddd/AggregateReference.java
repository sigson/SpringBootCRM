package domain.core.ddd;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;

/**
 * Типизированная ссылка на агрегат-цель (typeId + raw-id), хранимая через
 * {@code AggregateReferenceXxxUserType} в двух колонках: {@code targetTypeId} (BIGINT)
 * и {@code targetIdRaw} (UUID/BIGINT/VARCHAR — по idType цели).
 *
 * <p>Compile-time гарантии целевого типа нет (стирание типов); защита — runtime:
 * обязательный {@code @ValidAggregateRef(target=..., idType=...)}, bootstrap-сверка
 * UserType с idType и {@code ValidAggregateRefValidator} (targetTypeId + decodeability).
 *
 * <p>Java-тип {@code targetIdRaw} — String для унифицированного API; native JDBC-колонку
 * обеспечивает UserType.
 */
@Embeddable
public final class AggregateReference<T extends AbstractAggregate<ID>,
                                      ID extends Serializable>
        implements Serializable {

    @Column(name = "ref_type_id", nullable = false)
    private long targetTypeId;

    @Column(name = "ref_id", nullable = false)
    private String targetIdRaw;

    /** JPA / framework-only constructor. */
    public AggregateReference() {}

    private AggregateReference(long typeId, String idRaw) {
        this.targetTypeId = typeId;
        this.targetIdRaw  = idRaw;
    }

    public static <T extends AbstractAggregate<ID>, ID extends Serializable>
           AggregateReference<T, ID> ofRaw(long typeId, String idRaw) {
        return new AggregateReference<>(typeId, idRaw);
    }

    public long targetTypeId()   { return targetTypeId; }
    public String targetIdRaw()  { return targetIdRaw; }

    // hibernate-only setters (для @AttributeOverride)
    public void setTargetTypeId(long v)   { this.targetTypeId = v; }
    public void setTargetIdRaw(String v)  { this.targetIdRaw  = v; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AggregateReference<?, ?> that)) return false;
        return targetTypeId == that.targetTypeId
            && Objects.equals(targetIdRaw, that.targetIdRaw);
    }

    @Override
    public int hashCode() {
        return Objects.hash(targetTypeId, targetIdRaw);
    }

    @Override
    public String toString() {
        return "AggregateReference[" + targetTypeId + ":" + targetIdRaw + "]";
    }
}
