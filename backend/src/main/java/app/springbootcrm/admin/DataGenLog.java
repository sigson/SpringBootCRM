package app.springbootcrm.admin;

import app.springbootcrm.metadata.UiAggregate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Журнальний запис про один згенерований об'єкт.
 *
 * <p>Це <b>не</b> бізнес-агрегат: немає {@code @TypeId}, {@code @UiAggregate} чи
 * {@code AggregateRepository} — звичайна технічна JPA-сутність, таблиця якої
 * створюється міграцією {@code V2__data_gen_log.sql}. Тому
 * {@code MetadataBootstrapper}/{@code RepositoryRegistry} її ігнорують
 * (вони сканують лише {@code @TypeId}-агрегати).
 *
 * <p>Кожен рядок фіксує пару {@code (typeId, recordId)} — за нею
 * {@link DataGenService#purge()} безпомилково знаходить і видаляє згенеровані
 * записи будь-якого типу, у т.ч. регістри без рядкових полів.
 */
@Entity
@Table(name = "data_gen_log")
public class DataGenLog {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "type_id", nullable = false)
    private long typeId;

    /** Сирий id згенерованого запису (UUID у вигляді рядка). */
    @Column(name = "record_id", nullable = false, length = 64)
    private String recordId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected DataGenLog() {}

    public DataGenLog(long typeId, String recordId) {
        this.id = UUID.randomUUID();
        this.typeId = typeId;
        this.recordId = recordId;
        this.createdAt = Instant.now();
    }

    public UUID getId()        { return id; }
    public long getTypeId()    { return typeId; }
    public String getRecordId(){ return recordId; }
    public Instant getCreatedAt() { return createdAt; }
}
