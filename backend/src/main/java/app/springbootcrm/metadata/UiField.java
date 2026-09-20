package app.springbootcrm.metadata;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * UI-описание поля агрегата, дополняющее системное {@code @FieldId}.
 *
 * <p>{@code @FieldId} отвечает за systemic концерны: id поля, доступ, тип Java.
 * {@code @UiField} — за всё, что нужно UI и невыводимо из системной метадаты:
 * label, kind (если автоматический вывод недостаточен), порядок отображения, hint'ы.
 *
 * <p>Если аннотации нет — поле всё равно попадает в {@link TypeRegistry.FieldOut}
 * с label из {@code rawField.getName()} и kind, выведенным из Java-типа
 * (см. {@link TypeRegistry#inferKind}).
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface UiField {

    /** Человекочитаемая метка (например, «Електронна адреса»). */
    String label();

    /**
     * Тип отображения. Если {@link UiFieldKind#AUTO} (по умолчанию) —
     * TypeRegistry выведет kind из Java-типа поля.
     */
    UiFieldKind kind() default UiFieldKind.AUTO;

    /** Порядок отображения в таблице/форме (asc). */
    int order() default 100;

    /** Не показывать в табличном представлении (списке). */
    boolean hiddenInTable() default false;

    /** Не показывать в форме редактирования. */
    boolean hiddenInForm() default false;

    /**
     * Аудиторський реквізит, який показується лише у списку. На відміну від звичайного
     * {@code hiddenInForm=true}+{@code hiddenInTable=false}, знімає відсіювання audit-полів
     * у {@code TypeRegistry.isAuditField(...)} (інакше поле взагалі не потрапляє в метадані).
     * {@code true} жорстко означає {@code readOnly=true}, {@code hiddenInForm=true},
     * {@code hiddenInTable=false} незалежно від решти атрибутів.
     */
    boolean auditTableColumn() default false;

    /** Максимальная длина для текстовых полей (если не выводится из {@code @Column.length}). */
    int maxLength() default -1;

    String placeholder() default "";
    String description() default "";

    /** Является ли поле обязательным (UI-уровень). */
    boolean required() default false;

    enum UiFieldKind {
        AUTO, TEXT, CODE, NUMBER, BOOLEAN, DATE, DATETIME, TEXTAREA, EMAIL, PASSWORD, REF
    }
}
