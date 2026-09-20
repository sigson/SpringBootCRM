package app.springbootcrm.reports;

import app.springbootcrm.metadata.UiAggregate;
import app.springbootcrm.metadata.UiField;
import app.springbootcrm.reference.AbstractReferenceAggregate;
import app.springbootcrm.reference.Reference;
import com.fasterxml.jackson.databind.JsonNode;
import domain.core.access.DefaultAccess;
import domain.core.ddd.annotations.AccessChecked;
import domain.core.ddd.annotations.FieldId;
import domain.core.ddd.annotations.TypeId;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * <h2>Справочник отчётов (typeId=9500) — хранилище схем компоновки данных.</h2>
 *
 * <p>Аналог объекта «Отчёт» в 1С: именованный элемент справочника, который держит
 * в себе всё, что нужно для построения отчёта, — и ничего сверх того:
 *
 * <ul>
 *   <li>{@code scheme} — <b>схема компоновки</b>: наборы данных (упакованная строка с
 *       несколькими запросами — см. {@code QueryPackCodec} в модуле Workbench'а),
 *       связи наборов, вычисляемые поля, ресурсы и параметры;</li>
 *   <li>{@code settings} — <b>настройки компоновки по умолчанию</b>: структура отчёта
 *       (группировки/таблицы), выбранные поля, отбор, порядок, условное оформление,
 *       параметры вывода;</li>
 *   <li>{@code templates} — <b>макеты</b> отчёта (оформление областей вывода);</li>
 *   <li>{@code forms} — <b>формы</b> отчёта, собранные визуальным построителем форм
 *       в редакторе отчёта.</li>
 * </ul>
 *
 * <p>Все четыре реквизита — «сырой» JSON ({@link JsonNodeConverter}). Их структуру
 * определяет и интерпретирует модуль компоновки {@code app.modules.dcs} вместе со
 * своим конструктором на фронтенде; хост хранит и отдаёт их as-is. Поэтому модуль
 * можно развивать или удалить целиком, не трогая ни этот справочник, ни миграции.
 *
 * <p>{@code defaultRepoAccess=READ_ONLY}: читать (и, значит, формировать отчёт)
 * может любой аутентифицированный пользователь; изменять схему — только по гранту
 * WRITE на typeId 9500 (или admin).
 */
@Entity
@Table(name = "reports")
@TypeId(value = Report.TYPE_ID, defaultRepoAccess = DefaultAccess.READ_ONLY)
@Reference(prefix = "RPT", codeWidth = 6, singularName = "report")
@AccessChecked(strict = true)
@UiAggregate(
        slug = "reports",
        singularLabel = "Report",
        pluralLabel = "Reports",
        iconHint = "📊",
        displayPattern = "{code} — {name}",
        apiBase = "/api/reports",
        userCreatable = true,
        isReference = true
)
public class Report extends AbstractReferenceAggregate {

    public static final long TYPE_ID = 9500L;

    /**
     * Практический потолок текстового реквизита, общий для H2 и PostgreSQL
     * ({@code varchar(n)} с таким n принимают обе СУБД).
     */
    static final String JSON_COLUMN = "varchar(1000000)";

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** Схема компоновки: наборы данных, связи, вычисляемые поля, ресурсы, параметры. */
    @FieldId(value = 9500_10L, defaultAccess = DefaultAccess.READ_WRITE)
    @UiField(label = "Composition schema", kind = UiField.UiFieldKind.TEXTAREA,
            hiddenInTable = true, hiddenInForm = true, order = 30,
            description = "Datasets, links, resources and parameters — edited in the report designer")
    @Column(name = "schema_json", columnDefinition = JSON_COLUMN)
    @Convert(converter = JsonNodeConverter.class)
    private JsonNode scheme;

    /** Настройки компоновки по умолчанию: структура, отбор, порядок, оформление. */
    @FieldId(value = 9500_11L, defaultAccess = DefaultAccess.READ_WRITE)
    @UiField(label = "Default settings", kind = UiField.UiFieldKind.TEXTAREA,
            hiddenInTable = true, hiddenInForm = true, order = 31,
            description = "Report structure, filter, order and output parameters")
    @Column(name = "settings_json", columnDefinition = JSON_COLUMN)
    @Convert(converter = JsonNodeConverter.class)
    private JsonNode settings;

    /** Макеты отчёта (массив). */
    @FieldId(value = 9500_12L, defaultAccess = DefaultAccess.READ_WRITE)
    @UiField(label = "Templates", kind = UiField.UiFieldKind.TEXTAREA,
            hiddenInTable = true, hiddenInForm = true, order = 32,
            description = "Output area templates, edited in the report designer")
    @Column(name = "templates_json", columnDefinition = JSON_COLUMN)
    @Convert(converter = JsonNodeConverter.class)
    private JsonNode templates;

    /** Формы отчёта (массив), собранные построителем форм. */
    @FieldId(value = 9500_13L, defaultAccess = DefaultAccess.READ_WRITE)
    @UiField(label = "Forms", kind = UiField.UiFieldKind.TEXTAREA,
            hiddenInTable = true, hiddenInForm = true, order = 33,
            description = "Report forms, built with the visual form builder")
    @Column(name = "forms_json", columnDefinition = JSON_COLUMN)
    @Convert(converter = JsonNodeConverter.class)
    private JsonNode forms;

    /**
     * Идентификатор датасорса Workbench'а, на котором исполняются запросы схемы.
     * По умолчанию {@code main} — основная БД хоста, зарегистрированная read-only.
     */
    @FieldId(value = 9500_14L, defaultAccess = DefaultAccess.READ_WRITE)
    @UiField(label = "Data source", order = 40, maxLength = 100,
            description = "SQL Workbench data source id; «main» is the host database")
    @Column(name = "data_source_id", length = 100)
    private String dataSourceId = "main";

    @FieldId(value = 9500_15L, defaultAccess = DefaultAccess.READ_WRITE)
    @UiField(label = "Active", order = 50)
    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    public Report() {}

    public Report(UUID id, String code, String name) {
        super(code, name);
        this.id = id;
    }

    @Override public UUID getId() { return id; }

    public JsonNode getScheme()     { return scheme; }
    public JsonNode getSettings()   { return settings; }
    public JsonNode getTemplates()  { return templates; }
    public JsonNode getForms()      { return forms; }
    public String getDataSourceId() { return dataSourceId; }
    public boolean isEnabled()      { return enabled; }

    public void setScheme(JsonNode scheme)          { this.scheme = scheme; }
    public void setSettings(JsonNode settings)      { this.settings = settings; }
    public void setTemplates(JsonNode templates)    { this.templates = templates; }
    public void setForms(JsonNode forms)            { this.forms = forms; }
    public void setDataSourceId(String dataSourceId) { this.dataSourceId = dataSourceId; }
    public void setEnabled(boolean enabled)         { this.enabled = enabled; }
}
