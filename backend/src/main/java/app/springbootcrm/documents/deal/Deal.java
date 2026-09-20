package app.springbootcrm.documents.deal;

import app.springbootcrm.catalogs.customer.Customer;
import app.springbootcrm.catalogs.dealstage.DealStage;
import app.springbootcrm.catalogs.discount.Discount;
import app.springbootcrm.catalogs.leadsource.LeadSource;
import app.springbootcrm.metadata.AggregateClassification;
import app.springbootcrm.reference.ReferenceAggregate;

import app.springbootcrm.metadata.UiAggregate;
import app.springbootcrm.metadata.UiField;
import app.springbootcrm.reference.Reference;
import domain.core.access.DefaultAccess;
import domain.core.ddd.AbstractAuditedNoAclAggregate;
import domain.core.ddd.AggregateReference;
import domain.core.ddd.AggregateReferenceUuidUserType;
import domain.core.ddd.annotations.AccessChecked;
import domain.core.ddd.annotations.FieldId;
import domain.core.ddd.annotations.TypeId;
import domain.core.ddd.annotations.ValidAggregateRef;
import domain.core.validation.NumberRange;
import domain.core.validation.Required;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CompositeType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Ремонтна норма (TER01D03, typeId=4104).
 *
 * <p>Норма часу/витрат на ремонт у розрізі <b>типу ремонту</b> ({@link LeadSource})
 * та <b>типу робітника</b> — посади/ставки ({@link DealStage}). Ці зв'язки виражені
 * повноцінними {@link AggregateReference} (typeId+id, з runtime-перевіркою
 * {@code @ValidAggregateRef}) — як і в усьому комплексі.
 *
 * <p>На відміну від {@code LeadSource}/{@code Discount}/{@code DealStage}
 * (довідники з {@code code}+{@code name}), норма <b>не</b> є довідником: у неї немає
 * найменування, а identity — це сам набір (тип ремонту × ставка). Тому вона успадковує
 * {@link AbstractAuditedAggregate} напряму (аудит/версія/per-instance ACL) і несе власний
 * {@code code} (для людинозчитуваного посилання та генерації через {@link Reference}).
 *
 * <p>{@code defaultRepoAccess=READ_ONLY}: читати може будь-який автентифікований
 * користувач; створювати/редагувати/видаляти — лише з грантом WRITE_INSERT/WRITE_UPDATE
 * на typeId 4104 (або admin).
 */
@Entity
@Table(name = "deals")
@TypeId(value = Deal.TYPE_ID, defaultRepoAccess = DefaultAccess.READ_ONLY)
@Reference(prefix = "DEA", codeWidth = 9, singularName = "deal")
@AccessChecked(strict = true)
@UiAggregate(
        slug = "deals",
        singularLabel = "Deal",
        pluralLabel = "Deals",
        iconHint = "🤝",
        displayPattern = "{code}",
        apiBase = "/api/deals",
        userCreatable = true,
        // Deal — це РЕГІСТР (успадковує AbstractAuditedAggregate напряму,
        // не реалізує ReferenceAggregate, не має обов'язкового name). Класифікація
        // тепер виводиться автоматично з ієрархії типів (AggregateClassification),
        // тож це значення лише документує намір і не є авторитетним.
        isReference = false
)
public class Deal extends AbstractAuditedNoAclAggregate<UUID> {

    public static final long TYPE_ID = 4104L;

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** Людинозчитуваний код норми. INIT_ONCE: задається на створенні, далі незмінний. */
    @FieldId(value = 4104_10L, defaultAccess = DefaultAccess.INIT_ONCE)
    @UiField(label = "Code", kind = UiField.UiFieldKind.CODE, order = 10, required = true)
    @Required
    @Column(name = "code", nullable = false, length = 50)
    private String code;

    /** Тип ремонту (поточний/середній/капітальний). FK → lead_sources (4101). */
    @Embedded
    @CompositeType(AggregateReferenceUuidUserType.class)
    @AttributeOverrides({
            @AttributeOverride(name = "targetTypeId",
                    column = @Column(name = "lead_source_type_id", nullable = false)),
            @AttributeOverride(name = "targetIdRaw",
                    column = @Column(name = "lead_source_id", nullable = false))
    })
    @ValidAggregateRef(targets = {LeadSource.class}, idType = UUID.class)
    @FieldId(value = 4104_11L, defaultAccess = DefaultAccess.READ_WRITE)
    @UiField(label = "Lead source", kind = UiField.UiFieldKind.REF, order = 20, required = true)
    private AggregateReference<LeadSource, UUID> leadSource;

    /** Тип робітника (посада/ставка). FK → deal_stages (4102). */
    @Embedded
    @CompositeType(AggregateReferenceUuidUserType.class)
    @AttributeOverrides({
            @AttributeOverride(name = "targetTypeId",
                    column = @Column(name = "deal_stage_type_id", nullable = false)),
            @AttributeOverride(name = "targetIdRaw",
                    column = @Column(name = "deal_stage_id", nullable = false))
    })
    @ValidAggregateRef(targets = {DealStage.class}, idType = UUID.class)
    @FieldId(value = 4104_12L, defaultAccess = DefaultAccess.READ_WRITE)
    @UiField(label = "Deal stage", kind = UiField.UiFieldKind.REF, order = 30, required = true)
    private AggregateReference<DealStage, UUID> dealStage;

    /** Норма (год.). NUMERIC(10,2), невід'ємна. */
    @Embedded
    @CompositeType(AggregateReferenceUuidUserType.class)
    @AttributeOverrides({
            @AttributeOverride(name = "targetTypeId",
                    column = @Column(name = "customer_type_id", nullable = false)),
            @AttributeOverride(name = "targetIdRaw",
                    column = @Column(name = "customer_id", nullable = false))
    })
    @ValidAggregateRef(targets = {Customer.class}, idType = UUID.class)
    @FieldId(value = 4104_13L, defaultAccess = DefaultAccess.READ_WRITE)
    @UiField(label = "Customer", kind = UiField.UiFieldKind.REF, order = 40, required = true)
    private AggregateReference<Customer, UUID> customer;

    @FieldId(value = 4104_14L, defaultAccess = DefaultAccess.READ_WRITE)
    @UiField(label = "Amount", kind = UiField.UiFieldKind.NUMBER, order = 50, required = true)
    @Required
    @NumberRange(min = 0, message = "Amount cannot be negative")
    @Column(name = "amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @FieldId(value = 4104_15L, defaultAccess = DefaultAccess.READ_WRITE)
    @UiField(label = "Expected close date", kind = UiField.UiFieldKind.DATE, order = 60)
    @Column(name = "expected_close_date")
    private LocalDate expectedCloseDate;

    public Deal() {}

    public Deal(UUID id, String code,
                AggregateReference<LeadSource, UUID> leadSource,
                AggregateReference<DealStage, UUID> dealStage,
                AggregateReference<Customer, UUID> customer,
                BigDecimal amount,
                LocalDate expectedCloseDate) {
        this.id = id;
        this.code = code;
        this.leadSource = leadSource;
        this.dealStage = dealStage;
        this.customer = customer;
        this.amount = amount;
        this.expectedCloseDate = expectedCloseDate;
    }

    @Override public UUID getId() { return id; }

    public String getCode() { return code; }

    public AggregateReference<LeadSource, UUID> getLeadSource() { return leadSource; }
    public AggregateReference<DealStage, UUID> getDealStage() { return dealStage; }
    public AggregateReference<Customer, UUID> getCustomer() { return customer; }
    public BigDecimal getAmount() { return amount; }
    public LocalDate getExpectedCloseDate() { return expectedCloseDate; }

    public void setLeadSource(AggregateReference<LeadSource, UUID> r) { this.leadSource = r; }
    public void setDealStage(AggregateReference<DealStage, UUID> p) { this.dealStage = p; }
    public void setCustomer(AggregateReference<Customer, UUID> c) { this.customer = c; }
    public void setAmount(BigDecimal a) { this.amount = a; }
    public void setExpectedCloseDate(LocalDate d) { this.expectedCloseDate = d; }

    public void setCustomerId(UUID customerId) {
        this.customer = (customerId == null) ? null
                : AggregateReference.ofRaw(Customer.TYPE_ID, customerId.toString());
    }

    public UUID getCustomerId() {
        if (customer == null || customer.targetIdRaw() == null) return null;
        try { return UUID.fromString(customer.targetIdRaw()); }
        catch (IllegalArgumentException e) { return null; }
    }

    public void setLeadSourceId(UUID leadSourceId) {
        this.leadSource = (leadSourceId == null) ? null
                : AggregateReference.ofRaw(LeadSource.TYPE_ID, leadSourceId.toString());
    }

    public void setDealStageId(UUID dealStageId) {
        this.dealStage = (dealStageId == null) ? null
                : AggregateReference.ofRaw(DealStage.TYPE_ID, dealStageId.toString());
    }

    public UUID getLeadSourceId() {
        if (leadSource == null || leadSource.targetIdRaw() == null) return null;
        try { return UUID.fromString(leadSource.targetIdRaw()); }
        catch (IllegalArgumentException e) { return null; }
    }

    public UUID getDealStageId() {
        if (dealStage == null || dealStage.targetIdRaw() == null) return null;
        try { return UUID.fromString(dealStage.targetIdRaw()); }
        catch (IllegalArgumentException e) { return null; }
    }
}
