package app.springbootcrm.metadata;

import app.springbootcrm.reference.Reference;
import app.springbootcrm.reference.ReferenceAggregate;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * UI-описание агрегата, дополняющее системные {@code @TypeId}/{@code @FieldId}.
 *
 * <p>Принцип «один источник правды»: типов и полей не описываем повторно —
 * это уже сделано через {@code @TypeId} (typeId, defaultRepoAccess) и {@code @FieldId}
 * (id, defaultAccess). Здесь добавляется только то, что нужно UI и не выводимо
 * из {@code MetadataSnapshot}:
 * <ul>
 *   <li>{@code slug} — URL-friendly идентификатор, {@code apiBase} — REST-префикс;</li>
 *   <li>{@code singularLabel} / {@code pluralLabel} — для меток в интерфейсе;</li>
 *   <li>{@code iconHint} — emoji-icon для дашборда (опционально);</li>
 *   <li>{@code displayPattern} — шаблон строки отображения (например, {@code "{code} — {name}"}).</li>
 * </ul>
 *
 * <p>{@link app.springbootcrm.reference.Reference} — отдельная аннотация для правил генерации кода.
 * {@link TypeRegistry} сканирует все агрегаты с этой аннотацией и публикует
 * соответствующие {@link TypeRegistry.TypeDescriptor}'ы.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface UiAggregate {

    String slug();

    String singularLabel();
    String pluralLabel();

    String iconHint() default "📁";

    /** Шаблон строки для display-проекций, поддерживает {@code {fieldName}}-плейсхолдеры. */
    String displayPattern();

    /** REST-эндпоинт для CRUD-операций над типом (например, {@code "/api/users"}). */
    String apiBase();

    /** Может ли обычный пользователь создавать инстансы этого типа (UI-подсказка). */
    boolean userCreatable() default true;

    /**
     * @deprecated Поділ «довідник vs регістр» визначається ієрархією типів
     * (реалізація {@link app.springbootcrm.reference.ReferenceAggregate}, див.
     * {@link AggregateClassification#isReference(Class)}); цей прапорець ігнорується.
     */
    boolean isReference() default true;

    /**
     * Лейбл синтетического <b>write-only</b> поля пароля. Если не пустой —
     * {@link TypeRegistry} добавляет в метаданные поле {@code password}
     * ({@code kind=PASSWORD}, {@code hiddenInTable=true}), которое generic-редактор
     * рендерит как контрол пароля. Это <i>чисто UI-метаданные</i>: поле НЕ является
     * {@code @FieldId}-реквизитом сущности, поэтому не затрагивает persistence/
     * serialization/access — на бэкенде его принимает CRUD-контроллер в теле запроса
     * ({@code password}). Пустая строка (default) — поля пароля нет.
     */
    String passwordField() default "";

    /**
     * Обязателен ли пароль при создании (для синтетического {@link #passwordField()}).
     * При редактировании пароль всегда опционален (пусто = «не менять»).
     */
    boolean passwordRequiredOnCreate() default true;

    /**
     * Для <b>табличных частей</b> ({@code @TabularPart}) — шаблон owner-scoped
     * REST-пути для списка/создания строк, с плейсхолдером {@code {ownerId}}
     * (например, {@code "/api/users/{ownerId}/discounts"}). Generic-рендер ТЧ
     * (в форме владельца) использует этот путь для GET/POST, а
     * {@link #apiBase()}{@code /{id}} — для PUT/DELETE отдельной строки. Пустая
     * строка (default) — у ТЧ нет owner-scoped рендера (рендерится только как
     * самостоятельный раздел, если вообще виден).
     */
    String ownerListPath() default "";
}
