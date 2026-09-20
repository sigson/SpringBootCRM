package app.springbootcrm.metadata;

import app.springbootcrm.access.AccessRole;
import app.springbootcrm.documents.activity.Activity;
import app.springbootcrm.documents.deal.Deal;
import app.springbootcrm.reference.AbstractReferenceAggregate;
import app.springbootcrm.registers.exchangerate.ExchangeRate;
import app.springbootcrm.reporting.ReferenceLookupViewSynchronizer;
import app.springbootcrm.user.User;

import app.springbootcrm.reference.ReferenceAggregate;

/**
 * Єдине джерело правди для класифікації агрегата «довідник vs регістр».
 *
 * <p>«Довідник» — це не ручний прапорець, а <i>факт дочірності</i> об'єднуючому типу
 * {@link ReferenceAggregate} (який гарантує типобезпечні {@code getCode()}/{@code getName()}).
 * Цей же критерій використовує {@code ReferenceProviderAutoRegistrar}
 * ({@code ReferenceAggregate.isAssignableFrom(cls)}), тож класифікація послідовна
 * в усій системі:
 * <ul>
 *   <li><b>довідники</b> — реалізують {@link ReferenceAggregate}
 *       (через {@code AbstractReferenceAggregate}, а також {@code User},
 *       {@code AccessRole});</li>
 *   <li><b>регістри</b> — будь-який інший агрегат
 *       ({@code AbstractAggregate}/{@code AbstractAuditedAggregate} без
 *       реалізації інтерфейсу: {@code Activity}, {@code ExchangeRate},
 *       {@code Deal}, …);</li>
 *   <li><b>табличні частини</b> — окрема гілка
 *       ({@code AbstractTabularPart}); їх відсікає {@code isTabularPart}, тож
 *       тут вони теж класифікуються як «не довідник».</li>
 * </ul>
 *
 * <p>Прапорець {@code @UiAggregate.isReference()} більше не є авторитетним —
 * метадані-конструктори ({@link TypeRegistry}, {@link MetadataGraphService},
 * {@code ReferenceLookupViewSynchronizer}) спираються виключно на цей метод.
 */
public final class AggregateClassification {

    private AggregateClassification() {}

    /**
     * {@code true}, якщо тип є довідником — тобто реалізує об'єднуючий інтерфейс
     * {@link ReferenceAggregate}. Класифікація виводиться з ієрархії типів
     * (метадані БД), а не з ручного прапорця анотації.
     *
     * @param javaClass Java-клас агрегата (наприклад, {@code agg.javaClass()})
     */
    public static boolean isReference(Class<?> javaClass) {
        return javaClass != null && ReferenceAggregate.class.isAssignableFrom(javaClass);
    }

    /**
     * Документ — агрегат из {@code app.springbootcrm.documents.*}: операционная запись
     * (сделка, активность), в отличие от справочника и от регистра.
     */
    public static boolean isDocument(Class<?> javaClass) {
        return javaClass != null
                && javaClass.getName().startsWith("app.springbootcrm.documents.");
    }
}
