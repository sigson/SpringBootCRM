package domain.core.ddd;

import domain.core.access.DefaultAccess;
import domain.core.ddd.annotations.FieldId;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;
import org.hibernate.annotations.CompositeType;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.io.Serializable;
import java.time.Instant;

/**
 * База для <b>аудируемых, но НЕ ACL-несущих</b> агрегатов — прежде всего справочников.
 *
 * <p>Несёт стандартные поля бизнес-агрегата: {@code version} (optimistic locking),
 * {@code createdAt/updatedAt} (Spring Data Auditing) и
 * {@code createdBy/updatedBy} ({@code @FieldId(2)}/{@code @FieldId(3)}).
 *
 * <p>В отличие от {@link AbstractAuditedAggregate}, здесь сознательно нет per-instance ACL:
 * видимость/изменяемость справочников регулируется на уровне типа ({@code @TypeId
 * defaultRepoAccess} + type-flag-гранты), а не построчно. {@code ownAccess()} наследует
 * пустой дефолт, поэтому instance-проверки — no-op, а колонка {@code own_access} не материализуется.
 * Per-instance ACL добавляет только {@link AbstractAuditedAggregate}.
 *
 * <p>Field ID 2, 3 объявлены на абстрактном супертипе ({@code MetadataBootstrapper}
 * освобождает абстрактные классы от namespacing'а); наследники их не переопределяют.
 */
@MappedSuperclass
@EntityListeners({AuditingEntityListener.class})
public abstract class AbstractAuditedNoAclAggregate<ID extends Serializable>
        extends AbstractAggregate<ID> {

    @Version
    private long version;

    @CreatedDate
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private Instant updatedAt;

    @Embedded
    @CompositeType(AggregateReferenceUuidUserType.class)
    @AttributeOverrides({
            @AttributeOverride(name = "targetTypeId", column = @Column(name = "created_by_type_id", updatable = false)),
            @AttributeOverride(name = "targetIdRaw",  column = @Column(name = "created_by_id",      updatable = false))
    })
    @CreatedBy
    @FieldId(value = 2, defaultAccess = DefaultAccess.READ_ONLY)
    @SuppressWarnings("rawtypes")
    private AggregateReference createdBy;

    @Embedded
    @CompositeType(AggregateReferenceUuidUserType.class)
    @AttributeOverrides({
            @AttributeOverride(name = "targetTypeId", column = @Column(name = "updated_by_type_id")),
            @AttributeOverride(name = "targetIdRaw",  column = @Column(name = "updated_by_id"))
    })
    @LastModifiedBy
    @FieldId(value = 3, defaultAccess = DefaultAccess.READ_ONLY)
    @SuppressWarnings("rawtypes")
    private AggregateReference updatedBy;

    public long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    @Override
    @SuppressWarnings("rawtypes")
    public AggregateReference getCreatedBy() { return createdBy; }

    @Override
    @SuppressWarnings("rawtypes")
    public AggregateReference getUpdatedBy() { return updatedBy; }
}
