package app.springbootcrm.registers.exchangerate;

import app.springbootcrm.metadata.UiAggregate;
import app.springbootcrm.metadata.UiField;
import domain.core.access.DefaultAccess;
import domain.core.ddd.AbstractAggregate;
import domain.core.ddd.annotations.AccessChecked;
import domain.core.ddd.annotations.FieldId;
import domain.core.ddd.annotations.TypeId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Регистр «Курс валют» — демонстрация концепции <b>свободного регистра</b>
 * (typeId=9202).
 *
 * <p>Это полноценный бизнес-объект датамодели, но <b>технический</b>: наследует
 * lean-корень {@link AbstractAggregate} напрямую, поэтому
 * <ul>
 *   <li>не имеет стандартных полей «Код/Наименование»;</li>
 *   <li>не версионируется ({@code @Version} отсутствует);</li>
 *   <li>не имеет автора и дат создания/редактирования (аудит выключен);</li>
 *   <li>содержит только то, что объявил программист: дату, валюту и значение курса.</li>
 * </ul>
 *
 * <p>Доступ к регистру разграничивается так же, как к любой паре
 * «агрегат + репозиторий»: чтобы читать/добавлять/изменять записи, пользователь
 * должен иметь соответствующие флаги {@code AccessFlags} на {@code typeId=9202}
 * (либо admin/root-байпас). {@code defaultRepoAccess=READ_ONLY} — читать может
 * любой аутентифицированный пользователь, писать — только с явным грантом
 * {@code WRITE_*} на 9202.
 */
@Entity
@Table(name = "exchange_rates")
@TypeId(value = ExchangeRate.TYPE_ID, defaultRepoAccess = DefaultAccess.READ_ONLY)
@AccessChecked(strict = true)
@UiAggregate(
        slug = "exchange-rates",
        singularLabel = "Exchange rate",
        pluralLabel = "Exchange rates",
        iconHint = "💱",
        displayPattern = "{currencyCode} — {rate}",
        apiBase = "/api/exchange-rates",
        userCreatable = false,
        isReference = false
)
public class ExchangeRate extends AbstractAggregate<UUID> {

    public static final long TYPE_ID = 9202L;

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @FieldId(value = 9202_10L, defaultAccess = DefaultAccess.READ_WRITE)
    @UiField(label = "Date", kind = UiField.UiFieldKind.DATE, order = 10, required = true)
    @Column(name = "rate_date", nullable = false)
    private LocalDate rateDate;

    @FieldId(value = 9202_11L, defaultAccess = DefaultAccess.READ_WRITE)
    @UiField(label = "Currency", order = 20, required = true)
    @Column(name = "currency_code", nullable = false, length = 10)
    private String currencyCode;

    @FieldId(value = 9202_12L, defaultAccess = DefaultAccess.READ_WRITE)
    @UiField(label = "Rate", kind = UiField.UiFieldKind.NUMBER, order = 30, required = true)
    @Column(name = "rate", nullable = false, precision = 18, scale = 6)
    private BigDecimal rate;

    public ExchangeRate() {}

    public ExchangeRate(UUID id, LocalDate rateDate, String currencyCode, BigDecimal rate) {
        this.id = id;
        this.rateDate = rateDate;
        this.currencyCode = currencyCode;
        this.rate = rate;
    }

    @Override public UUID getId() { return id; }

    public LocalDate getRateDate() { return rateDate; }
    public String getCurrencyCode()    { return currencyCode; }
    public BigDecimal getRate()    { return rate; }

    public void setRateDate(LocalDate d) { this.rateDate = d; }
    public void setCurrencyCode(String c)    { this.currencyCode = c; }
    public void setRate(BigDecimal r)    { this.rate = r; }
}
