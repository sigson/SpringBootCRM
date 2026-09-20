package app.springbootcrm.catalogs.dealstage;

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
@Table(name = "deal_stages")
@TypeId(value = DealStage.TYPE_ID, defaultRepoAccess = DefaultAccess.READ_ONLY)
@Reference(prefix = "STG", codeWidth = 9, singularName = "deal stage")
@AccessChecked(strict = true)
@UiAggregate(
        slug = "deal-stages",
        singularLabel = "Deal stage",
        pluralLabel = "Deal stages",
        iconHint = "📈",
        displayPattern = "{name}",
        apiBase = "/api/deal-stages",
        userCreatable = false,
        isReference = true
)
public class DealStage extends AbstractReferenceAggregate {

    public static final long TYPE_ID = 4102L;

    @Id @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @FieldId(value = 4102_10L, defaultAccess = DefaultAccess.READ_WRITE)
    @UiField(label = "Probability, %", kind = UiField.UiFieldKind.NUMBER, order = 30, required = true)
    @Required
    @NumberRange(min = 0, message = "Only non-negative values are allowed")
    @Column(name = "probability", nullable = false, precision = 10, scale = 2)
    private BigDecimal probability;

    public DealStage() {}
    public DealStage(UUID id, String code, String name, BigDecimal probability) {
        super(code, name);
        this.id = id;
        this.probability = probability;
    }

    @Override public UUID getId() { return id; }
    public BigDecimal getProbability() { return probability; }
    public void setProbability(BigDecimal r) { this.probability = r; }
}
