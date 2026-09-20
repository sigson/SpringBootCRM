package app.springbootcrm.reference;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Простая infrastructure-сущность для атомарной генерации последовательных кодов.
 *
 * <p>НЕ агрегат — никаких {@link domain.core.ddd.AbstractAggregate}, ownAccess,
 * audit-полей; просто счётчик per-typeId.
 */
@Entity
@Table(name = "reference_sequences")
public class ReferenceSequence {

    @Id
    @Column(name = "type_id")
    private long typeId;

    @Column(name = "next_seq", nullable = false)
    private long nextSeq;

    public ReferenceSequence() {}

    public ReferenceSequence(long typeId, long nextSeq) {
        this.typeId = typeId;
        this.nextSeq = nextSeq;
    }

    public long getTypeId()   { return typeId; }
    public long getNextSeq()  { return nextSeq; }
    public void setNextSeq(long n) { this.nextSeq = n; }
}
