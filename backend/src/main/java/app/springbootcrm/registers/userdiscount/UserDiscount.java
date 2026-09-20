package app.springbootcrm.registers.userdiscount;

import app.springbootcrm.metadata.UiAggregate;
import app.springbootcrm.metadata.UiField;
import app.springbootcrm.catalogs.discount.Discount;
import app.springbootcrm.user.User;
import domain.core.access.DefaultAccess;
import domain.core.ddd.AbstractTabularPart;
import domain.core.ddd.AggregateReference;
import domain.core.ddd.AggregateReferenceUuidUserType;
import domain.core.ddd.annotations.AccessChecked;
import domain.core.ddd.annotations.FieldId;
import domain.core.ddd.annotations.TabularPart;
import domain.core.ddd.annotations.TypeId;
import domain.core.ddd.annotations.ValidAggregateRef;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CompositeType;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Табличная часть агрегата «Пользователь» — демонстрация концепции <b>ТЧ</b>
 * (typeId=9201).
 *
 * <p>Каждая строка — это коэффициент пользователя: ссылка на справочник
 * {@link Discount} + числовое (float) значение. Набор строк жёстко связан с
 * конкретным пользователем через унаследованный от {@link AbstractTabularPart}
 * {@code ownerRef} (см. {@code @TabularPart(owner = User.class)}).
 *
 * <p>Как технический объект, ТЧ <b>не</b> имеет версии, аудита и стандартных
 * полей «Код/Наименование» — только владельца (унаследованный {@code ownerRef})
 * и объявленные здесь реквизиты-колонки.
 *
 * <p><b>Разграничение доступа.</b> ТЧ — полноценная пара «агрегат + репозиторий»
 * со своим {@code typeId=9201}, но гранты на неё выдавать не требуется: зная из
 * метаданных связь {@code 9201 -> User(9001)}, {@code AccessResolver} под капотом
 * пробрасывает на ТЧ флаги доступа, которые пользователь имеет к владельцу-User.
 * {@code defaultRepoAccess=HIDDEN} (deny-by-default) — без прав к User строки ТЧ
 * не видны и не редактируемы.
 */
@Entity
@Table(name = "user_discounts")
@TypeId(value = UserDiscount.TYPE_ID, defaultRepoAccess = DefaultAccess.HIDDEN)
@TabularPart(owner = User.class)
@AccessChecked(strict = true)
@UiAggregate(
        slug = "user-discounts",
        singularLabel = "User discount",
        pluralLabel = "User discounts",
        iconHint = "🧮",
        displayPattern = "{discount} = {limitPercent}",
        apiBase = "/api/user-discounts",
        userCreatable = false,
        isReference = false,
        ownerListPath = "/api/users/{ownerId}/discounts"
)
public class UserDiscount extends AbstractTabularPart<UUID> {

    public static final long TYPE_ID = 9201L;

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** Ссылка на справочник коэффициентов. */
    @Embedded
    @CompositeType(AggregateReferenceUuidUserType.class)
    @AttributeOverrides({
            @AttributeOverride(name = "targetTypeId",
                    column = @Column(name = "discount_type_id", nullable = false)),
            @AttributeOverride(name = "targetIdRaw",
                    column = @Column(name = "discount_id", nullable = false))
    })
    @ValidAggregateRef(targets = {Discount.class}, idType = UUID.class)
    @FieldId(value = 9201_10L, defaultAccess = DefaultAccess.READ_WRITE)
    @UiField(label = "Discount", kind = UiField.UiFieldKind.REF, order = 10, required = true)
    private AggregateReference<Discount, UUID> discount;

    /** Числовое значение коэффициента для данного пользователя. */
    @FieldId(value = 9201_11L, defaultAccess = DefaultAccess.READ_WRITE)
    @UiField(label = "Value", kind = UiField.UiFieldKind.NUMBER, order = 20, required = true)
    @Column(name = "limit_percent", nullable = false, precision = 18, scale = 6)
    private BigDecimal limitPercent;

    public UserDiscount() {}

    public UserDiscount(UUID id, UUID ownerUserId,
                           AggregateReference<Discount, UUID> discount,
                           BigDecimal limitPercent) {
        this.id = id;
        setOwner(User.TYPE_ID, ownerUserId);
        this.discount = discount;
        this.limitPercent = limitPercent;
    }

    @Override public UUID getId() { return id; }

    public AggregateReference<Discount, UUID> getDiscount() { return discount; }
    public BigDecimal getLimitPercent() { return limitPercent; }

    public void setDiscount(AggregateReference<Discount, UUID> c) { this.discount = c; }
    public void setDiscountId(UUID discountId) {
        this.discount = (discountId == null)
                ? null
                : AggregateReference.ofRaw(Discount.TYPE_ID, discountId.toString());
    }
    public void setLimitPercent(BigDecimal f) { this.limitPercent = f; }

    public UUID getDiscountId() {
        if (discount == null || discount.targetIdRaw() == null) return null;
        try { return UUID.fromString(discount.targetIdRaw()); }
        catch (IllegalArgumentException e) { return null; }
    }
}
