package app.springbootcrm.catalogs.product;

import app.springbootcrm.catalogs.customer.Customer;

import app.springbootcrm.metadata.UiAggregate;
import domain.core.validation.NumberRange;
import domain.core.ddd.annotations.FieldId;
import app.springbootcrm.metadata.UiField;
import app.springbootcrm.reference.AbstractReferenceAggregate;
import app.springbootcrm.reference.Reference;
import domain.core.access.DefaultAccess;
import domain.core.ddd.annotations.AccessChecked;
import domain.core.ddd.annotations.TypeId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;

import java.util.UUID;

/**
 * Тип технологічного обладнання (TER01D02, typeId=4002).
 *
 * <p>Довідник «код + найменування» (як {@code Customer}, TER01D01).
 * Системні {@code RECORD_DATE}/{@code AUTHOR_ID} не дублюються власними полями —
 * їх роль виконують стандартні audit-поля
 * {@code AbstractAuditedAggregate} ({@code createdAt}/{@code updatedAt},
 * {@code createdBy}/{@code updatedBy}), які подаються у списку синтетичними
 * колонками «Дата запису» та «Автор»/«Корректировка».
 *
 * <p>{@code defaultRepoAccess=READ_ONLY}: читати може будь-який автентифікований
 * користувач; «ведення» (додавання/зміна/видалення) — за грантом
 * WRITE_INSERT/WRITE_UPDATE на typeId 4002 (або admin).
 *
 * <p>Унікальність по {@code CODE} та {@code NAME} забезпечується на
 * рівні БД (UNIQUE-обмеження) та сервісної валідації.
 */
@Entity
@Table(name = "products")
@TypeId(value = Product.TYPE_ID, defaultRepoAccess = DefaultAccess.READ_ONLY)
@Reference(prefix = "PRD", codeWidth = 9, singularName = "product")
@AccessChecked(strict = true)
@UiAggregate(
        slug = "products",
        singularLabel = "Product",
        pluralLabel = "Products",
        iconHint = "📦",
        displayPattern = "{code} — {name}",
        apiBase = "/api/products",
        userCreatable = true,
        isReference = true
)
public class Product extends AbstractReferenceAggregate {

    public static final long TYPE_ID = 4002L;

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @FieldId(value = 4002_10L, defaultAccess = DefaultAccess.READ_WRITE)
    @UiField(label = "SKU", order = 30, maxLength = 40)
    @Column(name = "sku", length = 40)
    private String sku;

    @FieldId(value = 4002_11L, defaultAccess = DefaultAccess.READ_WRITE)
    @UiField(label = "Unit price", kind = UiField.UiFieldKind.NUMBER, order = 40)
    @NumberRange(min = 0, message = "Unit price cannot be negative")
    @Column(name = "unit_price", precision = 12, scale = 2)
    private BigDecimal unitPrice;

    public Product() {}

    public Product(UUID id, String code, String name, String sku, BigDecimal unitPrice) {
        super(code, name);
        this.id = id;
        this.sku = sku;
        this.unitPrice = unitPrice;
    }

    @Override public UUID getId() { return id; }

    public String getSku()            { return sku; }
    public BigDecimal getUnitPrice()  { return unitPrice; }

    public void setSku(String sku)                  { this.sku = sku; }
    public void setUnitPrice(BigDecimal unitPrice)  { this.unitPrice = unitPrice; }
}
