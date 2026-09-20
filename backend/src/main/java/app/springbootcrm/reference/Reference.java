package app.springbootcrm.reference;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Маркер: класс является «справочником» (Reference Dictionary).
 *
 * <p>Любой справочник имеет:
 * <ul>
 *   <li>UUID (через {@link AbstractReferenceAggregate} → {@code AbstractAggregate});</li>
 *   <li>Строковый код (поле {@code code}, генерируется через {@link CodeGenerator});</li>
 *   <li>Наименование (поле {@code name});</li>
 *   <li>Опциональный символьный префикс, добавляющийся к авто-генерируемому коду.</li>
 * </ul>
 *
 * <p>Семантика {@link #codeWidth()}:
 * <ul>
 *   <li><b>codeWidth = 0</b> — авто-генерация выключена. Код задаётся вручную
 *       (код вводится вручную в форме);</li>
 *   <li><b>codeWidth &gt; 0</b> — код = {@code prefix + leftPad(seq, codeWidth - prefix.length())}.
 *       Например: {@code @Reference(prefix="CUS", codeWidth=9)} даст
 *       {@code "CUS000001", "CUS000002"…}</li>
 * </ul>
 *
 * <p>Несмотря на наличие этой аннотации, использование CodeGenerator'а
 * <b>опционально</b> — сервис-CRUD'ы могут принимать готовый {@code code}
 * от клиента, а CodeGenerator вызывать только при отсутствии входного значения.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface Reference {

    /** Префикс к авто-генерируемому коду (например, {@code "CUS"}). Пустой = без префикса. */
    String prefix() default "";

    /**
     * Полная ширина кода (prefix + digits). При {@code 0} авто-генерация выключена.
     * При {@code N > prefix.length()}: numeric-часть = {@code N - prefix.length()}.
     */
    int codeWidth() default 0;

    /** Человекочитаемое название (для сообщений об ошибках/UI). */
    String singularName() default "record";
}
