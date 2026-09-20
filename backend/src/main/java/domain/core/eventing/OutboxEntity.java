package domain.core.eventing;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Outbox-таблица для transactional event publishing'а.
 *
 * <p>В этом core'е — упрощённая сущность; реальный {@code OutboxRelay} (Kafka publisher)
 * не реализован — добавляется в production-проекте.
 *
 */
@Entity
@Table(name = "event_outbox", indexes = {
        @Index(name = "idx_outbox_published", columnList = "published_at"),
        @Index(name = "idx_outbox_aggref", columnList = "agg_type_id, agg_id_raw")
})
public class OutboxEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "agg_type_id", nullable = false)
    private long aggTypeId;

    @Column(name = "agg_id_raw", nullable = false)
    private String aggIdRaw;

    @Column(name = "event_type", nullable = false, length = 200)
    private String eventType;

    @Column(name = "stable_name", nullable = false, length = 100)
    private String stableName;

    @Column(name = "version", nullable = false)
    private int version;

    @Column(name = "payload_json", columnDefinition = "TEXT", nullable = false)
    private String payloadJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    public Long getId() { return id; }
    public long getAggTypeId() { return aggTypeId; }
    public void setAggTypeId(long v) { this.aggTypeId = v; }
    public String getAggIdRaw() { return aggIdRaw; }
    public void setAggIdRaw(String v) { this.aggIdRaw = v; }
    public String getEventType() { return eventType; }
    public void setEventType(String v) { this.eventType = v; }
    public String getStableName() { return stableName; }
    public void setStableName(String v) { this.stableName = v; }
    public int getVersion() { return version; }
    public void setVersion(int v) { this.version = v; }
    public String getPayloadJson() { return payloadJson; }
    public void setPayloadJson(String v) { this.payloadJson = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
    public Instant getPublishedAt() { return publishedAt; }
    public void setPublishedAt(Instant v) { this.publishedAt = v; }
}
