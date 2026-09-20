package app.springbootcrm.reference;

import app.springbootcrm.access.AccessRole;
import app.springbootcrm.metadata.ReferenceProviderAutoRegistrar;
import app.springbootcrm.user.User;

/**
 * Маркер-інтерфейс: «цей агрегат — елемент довідника».
 *
 * <p>Це <b>об'єднуючий тип</b> для всіх довідкових сутностей. На відміну від
 * анотації {@link Reference} (яка несе метадані код-генерації — префікс, ширину),
 * цей інтерфейс задає <i>типобезпечний контракт</i>: будь-який довідник уміє
 * віддати свій {@code code} та {@code name} без рефлексії.
 *
 * <p><b>Навіщо інтерфейс, а не лише анотація.</b> Система авто-реєстрації
 * ({@code ReferenceProviderAutoRegistrar}) знаходить довідники <i>за фактом
 * дочірності</i> цьому інтерфейсу ({@code ReferenceAggregate.isAssignableFrom(cls)}) —
 * це і є «зіставлення типу з цільовим об'єднуючим типом». Жодних ручних списків
 * та реєстрацій: достатньо, щоб новий клас реалізував цей інтерфейс (зазвичай —
 * успадкувавши {@link AbstractReferenceAggregate}) і був {@code @TypeId @Entity}.
 *
 * <p>Реалізують:
 * <ul>
 *   <li>{@link AbstractReferenceAggregate} — база більшості довідників;</li>
 *   <li>{@code app.springbootcrm.access.AccessRole} — довідник, що успадковує
 *       {@code AbstractAuditedAggregate} напряму;</li>
 *   <li>{@code app.springbootcrm.user.User} — користувач теж є елементом довідника
 *       (успадковує {@code UserAggregate}).</li>
 * </ul>
 */
public interface ReferenceAggregate {

    /** Довідковий код (бізнес-identity). */
    String getCode();

    /** Найменування. */
    String getName();
}
