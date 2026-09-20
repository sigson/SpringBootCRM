package domain.core.ddd;

import domain.core.access.DefaultAccess;
import domain.core.ddd.annotations.FieldId;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.MappedSuperclass;
import org.hibernate.annotations.CompositeType;

import java.io.Serializable;
import java.util.UUID;

/**
 * База для <b>табличных частей</b> — технических объектов, каждая строка которых
 * связана с конкретным экземпляром агрегата-владельца.
 *
 * <p>Табличная часть — частный случай регистра ({@link AbstractAggregate}): такая же
 * таблица без версионности/автора/«Код-Наименование», но с обязательной ссылкой
 * {@code ownerRef} на владельца. Поэтому отдельной ветки иерархии нет — класс наследует
 * тот же lean-корень и лишь добавляет ссылку, сохраняя единый контракт для listener'ов
 * и репозиториев.
 *
 * <p>Права наследуются от владельца: зная его тип из {@code @TabularPart},
 * {@code AccessResolver} дополняет права к {@code typeId} ТЧ правами к {@code typeId}
 * владельца — отдельные гранты на ТЧ не нужны.
 *
 * <p>{@code ownerRef} хранится {@link AggregateReferenceUuidUserType} в колонках
 * {@code owner_ref_type_id} (BIGINT) + {@code owner_ref_id} (UUID). Конкретный тип
 * владельца разный у разных ТЧ, декларируется {@code @TabularPart(owner=...)} и резолвится
 * {@code MetadataBootstrapper}'ом — поэтому поле намеренно объявлено без {@code @ValidAggregateRef}.
 *
 * <p>Field ID {@code 4} зарезервирован за {@code ownerRef} (1 — ownAccess, 2 — createdBy,
 * 3 — updatedBy на {@link AbstractAuditedAggregate}). Абстрактные супертипы освобождены
 * от namespacing'а {@code <typeId>_<seq>L}.
 *
 * @param <ID> тип суррогатного id строки (обычно {@code UUID}).
 */
@MappedSuperclass
public abstract class AbstractTabularPart<ID extends Serializable>
        extends AbstractAggregate<ID> {

    /**
     * Ссылка на агрегат-владелец конкретного набора строк. Обязательна и
     * неизменяема после создания ({@link DefaultAccess#INIT_ONCE}) — строка ТЧ не
     * может «переехать» к другому владельцу.
     */
    @Embedded
    @CompositeType(AggregateReferenceUuidUserType.class)
    @AttributeOverrides({
            @AttributeOverride(name = "targetTypeId",
                    column = @Column(name = "owner_ref_type_id", nullable = false)),
            @AttributeOverride(name = "targetIdRaw",
                    column = @Column(name = "owner_ref_id", nullable = false))
    })
    @FieldId(value = 4, defaultAccess = DefaultAccess.INIT_ONCE)
    @SuppressWarnings("rawtypes")
    private AggregateReference ownerRef;

    protected AbstractTabularPart() {}

    /** Ссылка на агрегат-владелец (id + typeId). */
    @SuppressWarnings("rawtypes")
    public AggregateReference getOwnerRef() { return ownerRef; }

    @SuppressWarnings("rawtypes")
    public void setOwnerRef(AggregateReference ref) { this.ownerRef = ref; }

    /** Удобное назначение владельца по (typeId, uuid). */
    public void setOwner(long ownerTypeId, UUID ownerId) {
        this.ownerRef = (ownerId == null)
                ? null
                : AggregateReference.ofRaw(ownerTypeId, ownerId.toString());
    }

    /** Сырой id владельца ({@code null}, если ссылка не задана). */
    public String getOwnerIdRaw() {
        return ownerRef == null ? null : ownerRef.targetIdRaw();
    }

    /** typeId владельца ({@code -1}, если ссылка не задана). */
    public long getOwnerTypeId() {
        return ownerRef == null ? -1L : ownerRef.targetTypeId();
    }

    /** Удобный разбор id владельца в {@link UUID} ({@code null}, если невозможно). */
    public UUID getOwnerId() {
        String raw = getOwnerIdRaw();
        if (raw == null) return null;
        try { return UUID.fromString(raw); }
        catch (IllegalArgumentException e) { return null; }
    }
}
