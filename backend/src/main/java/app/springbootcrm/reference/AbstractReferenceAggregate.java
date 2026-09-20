package app.springbootcrm.reference;

import app.springbootcrm.metadata.UiField;
import domain.core.access.DefaultAccess;
import domain.core.ddd.AbstractAuditedNoAclAggregate;
import domain.core.ddd.annotations.FieldId;
import domain.core.validation.CharPattern;
import domain.core.validation.Required;
import domain.core.validation.TextLength;
import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;

import java.util.UUID;

/**
 * База для всех справочников. Подсаживает два общих поля:
 * <ul>
 *   <li>{@code code} — строковый код. {@code READ_ONLY} (identity in business sense);</li>
 *   <li>{@code name} — наименование, {@code READ_WRITE}.</li>
 * </ul>
 *
 * <p>Field IDs 100, 101 — на абстрактном классе. {@link domain.core.bootstrap.MetadataBootstrapper}
 * освобождает абстрактные супертипы от namespacing'а ({@code <typeId>_<seq>L}) — конкретным
 * наследникам нельзя переопределять эти ID.
 */
@MappedSuperclass
public abstract class AbstractReferenceAggregate extends AbstractAuditedNoAclAggregate<UUID>
        implements ReferenceAggregate {

    // INIT_ONCE: код задаётся при СОЗДАНИИ (пользователь может принять
    // авто-сгенерированный или ввести свой) и неизменяем после — бизнес-identity.
    // READ_ONLY делал поле нередактируемым даже на форме создания (баг: «Код»
    // нельзя было отредактировать), а INIT_ONCE даёт canInsert()=true /
    // canUpdate(existing)=false — ровно нужную семантику.
    @FieldId(value = 100L, defaultAccess = DefaultAccess.INIT_ONCE)
    @UiField(label = "Code", kind = UiField.UiFieldKind.CODE, order = 10, required = true)
    @Required
    @TextLength(max = 50)
    @CharPattern(regex = "^[A-Za-z\\u0400-\\u04FF0-9._/-]+$",
                 message = "Code may contain only letters, digits and . _ - /")
    @Column(name = "code", nullable = false, length = 50)
    private String code;

    @FieldId(value = 101L, defaultAccess = DefaultAccess.READ_WRITE)
    @UiField(label = "Name", order = 20, required = true)
    @Required
    @TextLength(max = 200)
    @Column(name = "name", nullable = false, length = 200)
    private String name;

    protected AbstractReferenceAggregate() {}

    protected AbstractReferenceAggregate(String code, String name) {
        this.code = code;
        this.name = name;
    }

    public String getCode() { return code; }
    public String getName() { return name; }

    public void setName(String n) { this.name = n; }
}
