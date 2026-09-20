package app.springbootcrm.metadata;

import app.springbootcrm.navigation.ToolRegistry;
import app.springbootcrm.reference.ReferenceAggregate;
import app.springbootcrm.user.User;

import domain.core.access.AccessLevel;
import domain.core.bootstrap.AggregateDescriptor;
import domain.core.bootstrap.FieldDescriptor;
import domain.core.bootstrap.MetadataSnapshot;
import domain.core.bootstrap.MetadataSnapshotProvider;
import domain.core.ddd.AggregateReference;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Реестр UI-описаний агрегатов — тонкий фасад над {@link MetadataSnapshot}.
 *
 * <p><b>Принцип:</b> описания полей НЕ дублируются вручную. Реестр в {@code @PostConstruct}
 * берёт из {@code MetadataSnapshot} список агрегатов и их полей, для каждого ищет
 * {@link UiAggregate} (обязательная) и {@link UiField} (опциональная) и строит
 * результирующую карту. Всё остальное — kind поля, refTypeId, readonly-флаг — выводится
 * из системных метаданных (Java-тип, {@code @FieldId.defaultAccess}, {@code @ValidAggregateRef}).
 *
 * <p>Drift-зон нет: добавление нового поля = добавление {@code @FieldId} (+ опционально
 * {@code @UiField}); реестр подхватит автоматически.
 */
@Component
@DependsOn("metadataBootstrapper")
public class TypeRegistry {

    private final MetadataSnapshotProvider snapshots;
    private final app.springbootcrm.navigation.ToolRegistry tools;

    private final Map<Long, TypeDescriptor> byTypeId = new LinkedHashMap<>();
    private final Map<String, TypeDescriptor> bySlug = new LinkedHashMap<>();

    public TypeRegistry(MetadataSnapshotProvider snapshots,
                        app.springbootcrm.navigation.ToolRegistry tools) {
        this.snapshots = snapshots;
        this.tools = tools;
    }

    @PostConstruct
    public void init() {
        MetadataSnapshot snap = snapshots.get();
        List<TypeDescriptor> all = new ArrayList<>();
        for (AggregateDescriptor agg : snap.allAggregates()) {
            UiAggregate ui = agg.javaClass().getAnnotation(UiAggregate.class);
            if (ui == null) continue;     // не UI-агрегат — пропускаем
            all.add(buildDescriptor(agg, ui, snap));
        }
        // Сортируем по pluralLabel для стабильного порядка в UI
        all.sort(Comparator.comparing(TypeDescriptor::pluralLabel));
        for (TypeDescriptor d : all) {
            byTypeId.put(d.typeId(), d);
            bySlug.put(d.slug(), d);
        }

        // Свободные контроллеры (SQL Workbench, генерация данных) — полноправные
        // участники системы типов с зарезервированным typeId и пометкой
        // representation=NONSTANDARD. У них нет fields/generic-apiBase: их
        // представление обязан дать фронтовый хук по typeId, иначе — 404.
        for (app.springbootcrm.navigation.ToolRegistry.ToolNode t : tools.all()) {
            TypeDescriptor d = freeControllerDescriptor(t);
            byTypeId.put(d.typeId(), d);
            bySlug.put(d.slug(), d);
        }
    }

    /** Строит {@link TypeDescriptor} для свободного контроллера (вне датамодели). */
    private static TypeDescriptor freeControllerDescriptor(
            app.springbootcrm.navigation.ToolRegistry.ToolNode t) {
        return new TypeDescriptor(
                t.typeId(),
                t.key(),                 // slug = ключ инструмента
                t.label(),               // singularLabel
                t.label(),               // pluralLabel
                t.icon(),
                "",                      // apiBase: нет generic-CRUD (своя REST-логика)
                false,                   // isReference
                false,                   // isDocument
                false,                   // userCreatable
                t.adminOnly(),
                false,                   // isTabularPart
                null,                    // ownerTypeId
                "",                      // ownerListPath
                "",                      // displayPattern
                List.of(),               // fields: нет стандартных реквизитов
                REPRESENTATION_NONSTANDARD);
    }

    public List<TypeDescriptor> all()                  { return new ArrayList<>(byTypeId.values()); }
    public TypeDescriptor byTypeId(long typeId)        { return byTypeId.get(typeId); }
    public TypeDescriptor bySlug(String slug)          { return bySlug.get(slug); }

    // -------- Build --------

    private TypeDescriptor buildDescriptor(AggregateDescriptor agg, UiAggregate ui,
                                           MetadataSnapshot snap) {
        // Класифікація «довідник vs регістр» виводиться з ієрархії типів
        // (реалізація ReferenceAggregate), а НЕ з ручного прапорця
        // @UiAggregate.isReference() — див. AggregateClassification.
        boolean isReference = AggregateClassification.isReference(agg.javaClass());
        // Собираем field-descriptors. Включение в UI-реестр — opt-in через @UiField.
        // Поля без @UiField (служебные: access-метрика, назначенные роли, password-хеш)
        // имеют @FieldId для access-контроля, но в UI не показываются.
        List<UiFieldDescriptor> fields = new ArrayList<>();
        FieldDescriptor createdByFd = null;
        FieldDescriptor updatedByFd = null;
        for (FieldDescriptor fd : agg.fields()) {
            // Audit-поля createdBy/updatedBy (REF на пользователя) — НЕ показываем как
            // обычные поля, но запоминаем, чтобы добавить их как скрытые фильтруемые
            // колонки «Автор»/«Корректировка».
            if (fd.parentChain().length == 0) {
                String rawName = fd.rawField().getName();
                if ("createdBy".equals(rawName)) { createdByFd = fd; continue; }
                if ("updatedBy".equals(rawName)) { updatedByFd = fd; continue; }
            }
            // Не показываем в UI системные поля с parent-chain (вложенные @Embedded) и audit-поля
            if (fd.parentChain().length > 0) continue;
            if (isAuditField(fd.rawField())) {
                // Исключение: audit-поле, явно помеченное @UiField(auditTableColumn=true),
                // surface'ится как read-only табличная колонка (см. buildFieldDescriptor).
                UiField au = fd.rawField().getAnnotation(UiField.class);
                if (au == null || !au.auditTableColumn()) continue;
            }

            UiField uif = fd.rawField().getAnnotation(UiField.class);
            if (uif == null) continue;     // opt-in: без @UiField — пропускаем
            UiFieldDescriptor d = buildFieldDescriptor(fd, uif, snap);
            if (d != null) fields.add(d);
        }

        // Справочники получают скрытые по умолчанию, но фильтруемые колонки
        // «Автор» (createdBy) и «Корректировка» (updatedBy). Это REF на тип пользователя —
        // UI резолвит их через DisplayResolver и позволяет фильтровать (как любую
        // reference-колонку). hiddenInTable=true → не показываются сразу, но доступны
        // через «⚙️ Колонки» и в фильтрах.
        if (isReference) {
            UiFieldDescriptor author = buildAuditRefField(
                    createdByFd, "createdBy", "Author", 1000);
            if (author != null) fields.add(author);
            UiFieldDescriptor editor = buildAuditRefField(
                    updatedByFd, "updatedBy", "Modified by", 1001);
            if (editor != null) fields.add(editor);
        }

        // «Дата запису» (createdAt) — системный audit-реквизит. Сам field живёт во
        // фреймворк-слое (domain.core.ddd.AbstractAuditedAggregate) и НЕ несёт
        // @FieldId/@UiField (иначе core-слой зависел бы от app-слоя — инверсия зависимостей),
        // поэтому подаём его синтетически, как createdBy/updatedBy выше. Поле read-only,
        // видно в списке (справочники сразу, прочие — через «⚙️ Колонки»), отсутствует в
        // форме редактирования. DTO отдаёт его как ISO Instant.
        if (domain.core.ddd.AbstractAuditedNoAclAggregate.class.isAssignableFrom(agg.javaClass())) {
            boolean visible = isReference;   // справочники показывают сразу; прочие — opt-in через колонки
            fields.add(new UiFieldDescriptor(
                    "createdAt", "Record date", FieldKind.DATE,
                    null, List.of(),
                    /* required */ false, /* readOnly */ true, /* creatableOnly */ false,
                    /* hiddenInTable */ !visible, /* hiddenInForm */ true,
                    /* maxLength */ null, /* placeholder */ null,
                    /* description */ "System audit field: record creation date",
                    1002, /* anyReference */ false));
        }
        // Синтетическое write-only поле пароля (метаданные-only). Позволяет
        // обобщить типы с паролем (User) на generic-редактор: поле рендерится как
        // PASSWORD-контрол, не показывается в таблице, и его принимает CRUD-контроллер
        // в теле запроса (ключ {@code password}).
        // НЕ является @FieldId-реквизитом → не затрагивает persistence/serialization/access.
        if (ui.passwordField() != null && !ui.passwordField().isEmpty()) {
            fields.add(new UiFieldDescriptor(
                    "password", ui.passwordField(), FieldKind.PASSWORD,
                    null, List.of(),
                    ui.passwordRequiredOnCreate(),   // required (UI-уровень; при edit'е опционально — на фронте)
                    false,                            // readOnly
                    false,                            // creatableOnly (пароль редактируется и при обновлении)
                    true,                             // hiddenInTable
                    false,                            // hiddenInForm
                    200, null,
                    "Password (at least 6 characters). When editing, leave empty to keep the current one.",
                    45, /* anyReference */ false));
        }

        // Стабильная сортировка по order, затем по имени
        fields.sort(Comparator.<UiFieldDescriptor,Integer>comparing(d -> d.order)
                .thenComparing(d -> d.name));
        // Убираем order из public-результата
        List<FieldOut> publicFields = new ArrayList<>(fields.size());
        for (UiFieldDescriptor d : fields) publicFields.add(d.toOut());

        boolean adminOnly = isAdminOnly(agg.defaultRepoAccess());
        Long ownerTypeId = snap.tabularOwnerTypeId(agg.typeId());
        return new TypeDescriptor(
                agg.typeId(),
                ui.slug(),
                ui.singularLabel(),
                ui.pluralLabel(),
                ui.iconHint(),
                ui.apiBase(),
                isReference,
                AggregateClassification.isDocument(agg.javaClass()),
                ui.userCreatable(),
                adminOnly,
                ownerTypeId != null,
                ownerTypeId,
                ui.ownerListPath(),
                ui.displayPattern(),
                Collections.unmodifiableList(publicFields),
                REPRESENTATION_STANDARD);
    }

    private UiFieldDescriptor buildFieldDescriptor(FieldDescriptor fd, UiField uif,
                                                   MetadataSnapshot snap) {
        // Предусловие: uif != null (фильтруется в buildDescriptor)
        Field raw = fd.rawField();
        String name = raw.getName();
        String label = uif.label().isEmpty() ? name : uif.label();
        int order = uif.order();

        UiField.UiFieldKind annKind = uif.kind();
        FieldKind kind = (annKind == UiField.UiFieldKind.AUTO) ? inferKind(fd) : map(annKind);

        // Union-ссылки: собираем ВСЕ допустимые typeId'ы (фильтруя неразрешённые).
        List<Long> refTypeIds = new ArrayList<>();
        if (fd.isAggregateReference()) {
            for (long t : fd.referencedTypeIds()) if (t > 0) refTypeIds.add(t);
        }

        // Any-reference (маркер AnyReference): конкретных целей нет — раскрываем для пикера
        // все UI-типы с подходящим id-типом, чтобы фронт предложил выбор среди всех типов.
        boolean anyReference = fd.isAggregateReference() && fd.isAnyReference();
        if (anyReference) {
            domain.core.ddd.annotations.ValidAggregateRef var =
                    raw.getAnnotation(domain.core.ddd.annotations.ValidAggregateRef.class);
            Class<?> idType = (var != null) ? var.idType() : java.util.UUID.class;
            refTypeIds = selectableTypeIdsForIdType(snap, idType);
        }

        Long refTypeId = refTypeIds.isEmpty() ? null : refTypeIds.get(0);   // первый/единственный целевой typeId
        if (kind == FieldKind.REF && refTypeIds.isEmpty() && !anyReference) {
            // Auto-inferred REF без явных referencedTypeIds — нерезолвимо, пропускаем.
            // (any-reference сохраняем даже при пустом перечне — фронт раскроет по флагу.)
            return null;
        }

        // readOnly в узком смысле: нельзя ни вставить, ни обновить (полностью
        // иммутабельное поле — audit-метрика и т.п.).
        boolean noInsert = !fd.defaultAccess().canInsert();
        boolean noUpdate = !fd.defaultAccess().canUpdate(null);
        boolean readOnly = noUpdate && noInsert;
        // INIT_ONCE: запись только на insert (MODIFY_EMPTY). На update менять нельзя,
        // но на форме СОЗДАНИЯ поле редактируемо.
        boolean initOnce = fd.defaultAccess().mode() == domain.core.access.WriteMode.MODIFY_EMPTY;
        // «Только при создании»: insert разрешён, update — нет (INIT_ONCE или
        // READ_ONLY-поля, задаваемые только на insert). Generic-редактор делает
        // такие поля редактируемыми в форме создания и read-only в редактировании.
        boolean creatableOnly = !readOnly && (initOnce || noUpdate) && !noInsert;

        // Осторожно с обеими ветками тернарника: явный boxing на обе ветки, иначе
        // Java promote'ит результат до int и бросает NPE при unboxing'е null'а.
        Integer maxLength;
        if (uif.maxLength() > 0) {
            maxLength = Integer.valueOf(uif.maxLength());
        } else {
            maxLength = inferMaxLength(raw);     // может вернуть null — это валидно
        }
        String placeholder = uif.placeholder().isEmpty() ? null : uif.placeholder();
        String description = uif.description().isEmpty() ? null : uif.description();
        boolean hiddenInTable = uif.hiddenInTable();
        boolean hiddenInForm  = uif.hiddenInForm();
        boolean required = uif.required();

        // auditTableColumn нормализует семантику «лише у списку, read-only»:
        // видимо в таблице, скрыто в форме, неизменяемо — независимо от прочих атрибутов.
        if (uif.auditTableColumn()) {
            hiddenInTable = false;
            hiddenInForm = true;
            readOnly = true;
            creatableOnly = false;
        }

        return new UiFieldDescriptor(name, label, kind, refTypeId, refTypeIds,
                required, readOnly, creatableOnly,
                hiddenInTable, hiddenInForm,
                maxLength, placeholder, description, order, anyReference);
    }

    /**
     * Все UI-типы (с {@code @UiAggregate}), id-класс которых совпадает с {@code idType} —
     * перечень для пикера any-reference поля. Табличные части исключаются (они не
     * выбираются как самостоятельные ссылки). Отсортировано по typeId для стабильности.
     */
    private List<Long> selectableTypeIdsForIdType(MetadataSnapshot snap, Class<?> idType) {
        List<Long> out = new ArrayList<>();
        for (AggregateDescriptor agg : snap.allAggregates()) {
            if (agg.javaClass().getAnnotation(UiAggregate.class) == null) continue;   // только UI-типы
            if (snap.tabularOwnerTypeId(agg.typeId()) != null) continue;              // ТЧ — не самостоятельная цель
            Class<?> idc = snap.idClassByTypeIdOrNull(agg.typeId());
            if (idc != null && idc.equals(idType)) out.add(agg.typeId());
        }
        out.sort(Comparator.naturalOrder());
        return out;
    }

    /**
     * Строит скрытую (по умолчанию) фильтруемую REF-колонку для audit-поля
     * (createdBy → «Автор», updatedBy → «Корректировка»).
     *
     * <p>{@code referencedTypeId} аудит-полей резолвится в MetadataBootstrapper
     * к typeId конкретного {@code UserAggregate}-наследника (как правило, 9001).
     * Если по какой-то причине не зарезолвлено (нет User-агрегата) — возвращаем
     * {@code null}, колонка не добавляется (graceful).
     *
     * <ul>
     *   <li>{@code kind = REF} → UI рендерит значение через DisplayResolver и
     *       строит reference-picker для фильтра;</li>
     *   <li>{@code hiddenInTable = true} → не показывается сразу, доступна через
     *       «⚙️ Колонки»;</li>
     *   <li>{@code hiddenInForm = true} → не редактируется в форме (audit-поле);</li>
     *   <li>{@code readOnly = true}.</li>
     * </ul>
     */
    private UiFieldDescriptor buildAuditRefField(FieldDescriptor fd,
                                                 String name, String label, int order) {
        if (fd == null) return null;
        long refTid = fd.referencedTypeId();
        if (refTid <= 0) return null;   // не зарезолвлено (нет User-агрегата)
        return new UiFieldDescriptor(
                name, label, FieldKind.REF, refTid, List.of(refTid),
                /* required */ false, /* readOnly */ true, /* creatableOnly */ false,
                /* hiddenInTable */ true, /* hiddenInForm */ true,
                /* maxLength */ null, /* placeholder */ null,
                /* description */ "System audit field",
                order, /* anyReference */ false);
    }

    private static FieldKind inferKind(FieldDescriptor fd) {
        Class<?> t = fd.rawField().getType();
        if (fd.isAggregateReference())                                 return FieldKind.REF;
        if (AggregateReference.class.isAssignableFrom(t))              return FieldKind.REF;
        if (Boolean.class.equals(t) || boolean.class.equals(t))        return FieldKind.BOOLEAN;
        if (Instant.class.isAssignableFrom(t))                         return FieldKind.DATETIME;
        if (LocalDate.class.isAssignableFrom(t))                       return FieldKind.DATE;
        if (Number.class.isAssignableFrom(t) || isPrimitiveNumber(t))  return FieldKind.NUMBER;
        if (BigDecimal.class.equals(t))                                return FieldKind.NUMBER;
        return FieldKind.TEXT;
    }

    private static boolean isPrimitiveNumber(Class<?> c) {
        return c == int.class || c == long.class || c == double.class
                || c == float.class || c == short.class || c == byte.class;
    }

    private static FieldKind map(UiField.UiFieldKind k) {
        return switch (k) {
            case AUTO     -> FieldKind.TEXT;
            case TEXT     -> FieldKind.TEXT;
            case CODE     -> FieldKind.CODE;
            case NUMBER   -> FieldKind.NUMBER;
            case BOOLEAN  -> FieldKind.BOOLEAN;
            case DATE     -> FieldKind.DATE;
            case DATETIME -> FieldKind.DATETIME;
            case TEXTAREA -> FieldKind.TEXTAREA;
            case EMAIL    -> FieldKind.EMAIL;
            case PASSWORD -> FieldKind.PASSWORD;
            case REF      -> FieldKind.REF;
        };
    }

    private static boolean isAuditField(Field f) {
        String n = f.getName();
        return n.equals("createdAt") || n.equals("updatedAt")
                || n.equals("createdBy") || n.equals("updatedBy")
                || n.equals("version")  || n.equals("ownAccess");
    }

    private static Integer inferMaxLength(Field f) {
        jakarta.persistence.Column col = f.getAnnotation(jakarta.persistence.Column.class);
        if (col != null && col.length() > 0 && col.length() < 4000) return col.length();
        return null;
    }

    private static boolean isAdminOnly(AccessLevel typeDefault) {
        // READ_ONLY на repo + admin для модификаций = adminOnly-семантика
        return !typeDefault.canModify() && !typeDefault.canInsert();
    }

    // -------- Public-facing records --------

    public record TypeDescriptor(
            long typeId,
            String slug,
            String singularLabel,
            String pluralLabel,
            String iconHint,
            String apiBase,
            boolean isReference,
            /** Документ — агрегат из {@code app.springbootcrm.documents.*}. */
            boolean isDocument,
            boolean userCreatable,
            boolean adminOnly,
            boolean isTabularPart,
            Long ownerTypeId,
            /** Owner-scoped путь списка/создания строк ТЧ (с {@code {ownerId}}), либо "" . */
            String ownerListPath,
            String displayPattern,
            List<FieldOut> fields,
            /**
             * Вид представления типа:
             * <ul>
             *   <li>{@code "STANDARD"} — обычный агрегат: список и окно генерируются
             *       из {@link #fields} (либо переопределяются по {@code typeId} на фронте);</li>
             *   <li>{@code "NONSTANDARD"} — свободный контроллер (SQL Workbench,
             *       генерация данных): гарантированно нестандартное представление,
             *       у него нет {@code fields}/generic-{@code apiBase}. Фронтенд обязан
             *       иметь зарегистрированный хук представления по {@code typeId};
             *       иначе открытие типа даёт модалку 404.</li>
             * </ul>
             */
            String representation
    ) {}

    public static final String REPRESENTATION_STANDARD = "STANDARD";
    public static final String REPRESENTATION_NONSTANDARD = "NONSTANDARD";

    /** Public-shape для одного поля; сохраняет совместимость с frontend's FieldDescriptor. */
    public record FieldOut(
            String name,
            String label,
            FieldKind kind,
            /** Legacy: первый/единственный целевой typeId (для моно-ссылок). */
            Long refTypeId,
            /** Полный перечень целевых typeId'ов: 1 для моно-ссылки, ≥2 для union. */
            List<Long> refTypeIds,
            boolean required,
            boolean readOnly,
            /**
             * Поле можно задать ТОЛЬКО при создании (insert разрешён, update — нет:
             * INIT_ONCE / READ_ONLY-с-insert). Generic-редактор делает такое поле
             * редактируемым в форме создания ({@code id == null}) и read-only в форме
             * редактирования. Если {@code true}, {@code readOnly} относится только к
             * режиму редактирования.
             */
            boolean creatableOnly,
            boolean hiddenInTable,
            boolean hiddenInForm,
            Integer maxLength,
            String placeholder,
            String description,
            /**
             * {@code true} — поле помечено маркером {@code AnyReference}: union-ссылка
             * на любой тип. UI распознаёт это как сигнал предложить выбор среди всех
             * подходящих типов ({@link #refTypeIds} уже раскрыт под полный перечень).
             */
            boolean anyReference
    ) {}

    public enum FieldKind {
        TEXT, CODE, NUMBER, BOOLEAN, DATE, DATETIME, TEXTAREA, EMAIL, PASSWORD,
        /** Ссылка на другой агрегат. */
        REF
    }

    /** Внутренняя структура с order'ом для сортировки. */
    private record UiFieldDescriptor(
            String name, String label, FieldKind kind, Long refTypeId, List<Long> refTypeIds,
            boolean required, boolean readOnly, boolean creatableOnly,
            boolean hiddenInTable, boolean hiddenInForm,
            Integer maxLength, String placeholder, String description,
            int order, boolean anyReference
    ) {
        FieldOut toOut() {
            return new FieldOut(name, label, kind, refTypeId,
                    refTypeIds == null ? List.of() : refTypeIds,
                    required, readOnly, creatableOnly, hiddenInTable, hiddenInForm,
                    maxLength, placeholder, description, anyReference);
        }
    }
}
