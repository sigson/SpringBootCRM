package app.springbootcrm.catalogs.discount;

import app.springbootcrm.metadata.UiAggregate;
import app.springbootcrm.metadata.UiField;
import app.springbootcrm.reference.AbstractReferenceAggregate;
import app.springbootcrm.reference.Reference;
import domain.core.access.DefaultAccess;
import domain.core.ddd.annotations.AccessChecked;
import domain.core.ddd.annotations.FieldId;
import domain.core.ddd.annotations.TypeId;
import domain.core.validation.NumberRange;
import domain.core.validation.Required;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "discounts")
@TypeId(value = Discount.TYPE_ID, defaultRepoAccess = DefaultAccess.READ_ONLY)
@Reference(prefix = "DSC", codeWidth = 9, singularName = "discount")
@AccessChecked(strict = true)
@UiAggregate(
        slug = "discounts",
        singularLabel = "Discount",
        pluralLabel = "Discounts",
        iconHint = "🏷️",
        displayPattern = "{name}",
        apiBase = "/api/discounts",
        userCreatable = false,
        isReference = true
)
public class Discount extends AbstractReferenceAggregate {

    public static final long TYPE_ID = 4103L;

    @Id @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @FieldId(value = 4103_10L, defaultAccess = DefaultAccess.READ_WRITE)
    @UiField(label = "Value", kind = UiField.UiFieldKind.NUMBER, order = 30, required = true)
    @Required
    @NumberRange(min = 0.1, max = 1000, minInclusive = false, maxInclusive = false)
    @Column(name = "percent_value", nullable = false, precision = 10, scale = 4)
    private BigDecimal value;

    public Discount() {}
    public Discount(UUID id, String code, String name, BigDecimal value) {
        super(code, name);
        this.id = id;
        this.value = value;
    }

    @Override public UUID getId() { return id; }
    public BigDecimal getValue() { return value; }
    public void setValue(BigDecimal v) { this.value = v; }
}
