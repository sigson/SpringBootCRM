package app.modules.dcs.service;

import app.modules.dcs.bridge.ReportStoreBridge;
import app.modules.dcs.config.DcsProperties;
import app.modules.dcs.engine.CompositionProcessor;
import app.modules.dcs.engine.FieldCatalog;
import app.modules.dcs.engine.LayoutComposer;
import app.modules.dcs.engine.ParameterBinder;
import app.modules.dcs.engine.SettingsComposer;
import app.modules.dcs.expression.Expr;
import app.modules.dcs.expression.ExprParser;
import app.modules.dcs.model.CompositionResult;
import app.modules.dcs.model.DcsSchema;
import app.modules.dcs.model.DcsSettings;
import app.modules.dcs.model.SettingsBundle;
import app.modules.sqlworkbench.dto.Dtos.QueryRequest;
import app.modules.sqlworkbench.dto.Dtos.ResultSetDto;
import app.modules.sqlworkbench.querypack.QueryPack;
import app.modules.sqlworkbench.querypack.QueryPackCodec;
import app.modules.sqlworkbench.service.QueryPackService;
import app.modules.sqlworkbench.service.QueryService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * <h2>Оркестратор конвейера компоновки.</h2>
 *
 * <p>Здесь конвейер виден целиком и в одном месте: настройки → макет → исполнение →
 * обработка → результат. Каждый этап живёт в своём классе, а сервис только связывает
 * их и решает, на каком датасорсе исполнять.
 *
 * <p>Исполнение делегируется {@code QueryService} Workbench'а: он уже умеет то, что
 * отчётам обязательно нужно, — пул датасорсов, отказ на любом не-SELECT'е и
 * параметризованный {@code PreparedStatement}. Дублировать это в модуле значило бы
 * заводить второй, менее проверенный путь к чужой БД.
 */
@Service("dcsService")
public class DcsService {

    /** Ответ предпросмотра SQL. */
    public record SqlPreview(String sql, int parameterCount, List<String> warnings, String packed) {}

    /** Описание поля для конструктора настроек. */
    public record AvailableField(
            String id, String title, String kind, String role, String valueType,
            boolean usableInSelection, boolean usableInFilter,
            boolean usableInGroup, boolean usableInOrder) {}

    /** Колонка, реально возвращаемая запросом набора, — вход автозаполнения полей. */
    public record DescribedColumn(String name, String typeName, String valueType) {}

    public record DescribedDataSet(String name, List<DescribedColumn> columns, String error) {}

    /** Предпросмотр данных одного набора: колонки, строки и исполненный SQL. */
    public record DataSetPreview(
            String name, List<String> columns, List<List<Object>> rows,
            String sql, long elapsedMs, String error) {}

    /** Результат проверки выражения. */
    public record ExpressionCheck(boolean valid, String message, List<String> fields) {}

    private final ReportStoreBridge store;
    private final QueryService queryService;
    private final QueryPackService packService;
    private final DcsProperties properties;

    public DcsService(ReportStoreBridge store, QueryService queryService,
                      QueryPackService packService, DcsProperties properties) {
        this.store = store;
        this.queryService = queryService;
        this.packService = packService;
        this.properties = properties;
    }

    // --------------------------------------------------------------- компоновка

    /** Формирует сохранённый отчёт: вариант (если указан) плюс пользовательские настройки. */
    public CompositionResult compose(UUID reportId, String variantId, DcsSettings userSettings) {
        ReportStoreBridge.Loaded report = store.load(reportId);
        DcsSettings effective = effectiveSettings(report.settings(), variantId, userSettings);
        CompositionResult result = compose(report.schema(), effective, report.dataSourceId());
        result.reportId = reportId.toString();
        if (result.title == null) result.title = report.name();
        return result;
    }

    /** Формирует по присланным схеме и настройкам — предпросмотр прямо из конструктора. */
    public CompositionResult compose(DcsSchema schema, DcsSettings settings, String dataSourceId) {
        long started = System.currentTimeMillis();

        DcsSettings effective = settings == null ? new DcsSettings() : settings;
        applyDefaultMaxRows(effective);

        FieldCatalog catalog = new FieldCatalog(schema, effective);
        LayoutComposer.Composed layout = new LayoutComposer(schema, effective, catalog).compose();

        ResultSetDto rs = queryService.runSelect(
                resolveDataSource(dataSourceId),
                new QueryRequest(layout.sql(), 0, layout.maxRows() + 1),
                layout.parameters());

        boolean truncated = rs.rows().size() > layout.maxRows();
        List<List<Object>> rows = truncated ? rs.rows().subList(0, layout.maxRows()) : rs.rows();

        CompositionResult result = new CompositionProcessor(schema, effective, catalog, layout)
                .process(rs.columns(), rows);
        result.sql = layout.sql();
        result.truncated = truncated;
        result.elapsedMs = System.currentTimeMillis() - started;
        if (truncated) {
            result.warnings.add("The selection hit the " + layout.maxRows()
                    + "-record limit: totals are computed over incomplete data. "
                    + "Narrow the filter or raise the limit in the output parameters.");
        }
        return result;
    }

    /**
     * Расшифровка: пересборка отчёта с дополнительным отбором по значениям группировок
     * расшифровываемой ячейки.
     *
     * <p>Именно пересборка, а не «раскрытие сохранённого дерева»: так расшифровка
     * всегда показывает актуальные данные и работает одинаково в обоих режимах
     * агрегации — в режиме SQL-агрегации детальных записей в первом результате
     * попросту нет, и взять их можно только новым запросом.
     */
    public CompositionResult drilldown(UUID reportId, String variantId, DcsSettings userSettings,
                                       Map<String, Object> details, String action, String groupByField) {
        ReportStoreBridge.Loaded report = store.load(reportId);
        DcsSettings effective = effectiveSettings(report.settings(), variantId, userSettings);

        List<DcsSettings.FilterNode> nodes = new ArrayList<>();
        if (details != null) {
            details.forEach((field, value) -> nodes.add(SettingsComposer.equals(field, value)));
        }
        if (!nodes.isEmpty()) {
            effective.filter = SettingsComposer.andFilters(effective.filter, SettingsComposer.and(nodes));
        }

        // Детальные записи выводят поля наборов и вычисляемые поля: показывать в
        // расшифровке одни ресурсы бессмысленно — ради исходных строк её и открывают.
        List<String> detailSelection = detailSelection(report.schema(), effective);

        DcsSettings.StructureNode node = new DcsSettings.StructureNode();
        node.id = "drilldown";
        node.kind = DcsSettings.StructureNode.KIND_GROUPING;
        if ("groupBy".equalsIgnoreCase(action) && groupByField != null && !groupByField.isBlank()) {
            node.field = groupByField;
            node.title = "Breakdown by " + groupByField;
            DcsSettings.StructureNode detail = new DcsSettings.StructureNode();
            detail.id = "drilldown-detail";
            detail.selection = detailSelection;
            node.children = List.of(detail);
        } else {
            // «Расшифровать» без поля — детальные записи под выбранной ячейкой.
            node.title = "Detail records";
            node.selection = detailSelection;
        }
        effective.structure = List.of(node);

        CompositionResult result = compose(report.schema(), effective, report.dataSourceId());
        result.reportId = reportId.toString();
        return result;
    }

    // ------------------------------------------------------------- вспомогательное

    /** Сгенерированный SQL без исполнения — вкладка отладки конструктора. */
    public SqlPreview previewSql(DcsSchema schema, DcsSettings settings) {
        DcsSettings effective = settings == null ? new DcsSettings() : settings;
        applyDefaultMaxRows(effective);
        FieldCatalog catalog = new FieldCatalog(schema, effective);
        LayoutComposer.Composed layout = new LayoutComposer(schema, effective, catalog).compose();
        return new SqlPreview(layout.sql(), layout.parameters().size(), layout.warnings(),
                QueryPackCodec.encode(QueryPackCodec.decode(schema.packed)));
    }

    /** Доступные поля схемы — ими наполняется конструктор настроек. */
    public List<AvailableField> availableFields(DcsSchema schema, DcsSettings settings) {
        FieldCatalog catalog = new FieldCatalog(schema, settings == null ? new DcsSettings() : settings);
        List<AvailableField> out = new ArrayList<>();
        for (FieldCatalog.FieldInfo f : catalog.all()) {
            out.add(new AvailableField(
                    f.id(), f.title(), f.kind().name().toLowerCase(Locale.ROOT), f.role(), f.valueType(),
                    f.usableInSelection(), f.usableInFilter(), f.usableInGroup(), f.usableInOrder()));
        }
        return out;
    }

    /**
     * Какие колонки реально отдаёт каждый набор пакета. Запрос выполняется с
     * {@code pageSize=1}: нужны метаданные результата, а не данные, — так автозаполнение
     * полей не зависит от того, есть ли в таблице записи.
     *
     * <p>{@code &Параметры} связываются так же, как при компоновке: без этого
     * автозаполнение падало бы на любом наборе с параметром, то есть ровно там, где
     * подсказка по колонкам нужнее всего.
     */
    public List<DescribedDataSet> describeDataSets(String packed, String dataSourceId,
                                                   Map<String, Object> parameters) {
        QueryPack pack = packService.parse(packed);
        String ds = resolveDataSource(dataSourceId);
        List<DescribedDataSet> out = new ArrayList<>();
        for (QueryPack.PackedQuery q : pack.queries) {
            try {
                ResultSetDto rs = runDataSet(ds, q.sql, parameters, 1);
                List<DescribedColumn> columns = new ArrayList<>();
                for (int i = 0; i < rs.columns().size(); i++) {
                    String type = i < rs.columnTypes().size() ? rs.columnTypes().get(i) : null;
                    columns.add(new DescribedColumn(rs.columns().get(i), type, valueTypeOf(type)));
                }
                out.add(new DescribedDataSet(q.name, columns, null));
            } catch (RuntimeException e) {
                out.add(new DescribedDataSet(q.name, List.of(), e.getMessage()));
            }
        }
        return out;
    }

    /**
     * Данные одного набора — предпросмотр прямо из конструктора запроса.
     *
     * <p>Набор проверяется до компоновки: увидеть, что запрос вернул не то, дешевле на
     * двадцати строках, чем на дереве итогов, где ошибка источника выглядит как
     * странные цифры.
     */
    public DataSetPreview previewDataSet(String packed, String dataSetName,
                                         String dataSourceId, Map<String, Object> parameters,
                                         Integer limit) {
        QueryPack pack = packService.parse(packed);
        QueryPack.PackedQuery target = pack.query(dataSetName);
        if (target == null) {
            return new DataSetPreview(dataSetName, List.of(), List.of(), null, 0,
                    "Dataset «" + dataSetName + "» is not in the pack");
        }
        int rows = limit == null ? 20 : Math.max(1, Math.min(limit, 500));
        try {
            ResultSetDto rs = runDataSet(resolveDataSource(dataSourceId), target.sql, parameters, rows);
            return new DataSetPreview(dataSetName, rs.columns(), rs.rows(),
                    rs.executedSql(), rs.elapsedMs(), null);
        } catch (RuntimeException e) {
            return new DataSetPreview(dataSetName, List.of(), List.of(), null, 0, e.getMessage());
        }
    }

    /** Исполнение текста набора со связанными параметрами. */
    private ResultSetDto runDataSet(String dsId, String sql,
                                    Map<String, Object> parameters, int pageSize) {
        Map<String, Object> params = parameters == null ? Map.of() : parameters;
        ParameterBinder.Bound bound = ParameterBinder.bind(sql, params::get, null);
        return queryService.runSelect(dsId, new QueryRequest(bound.sql(), 0, pageSize), bound.values());
    }

    /** Проверка выражения — редактор выражений показывает ошибку сразу, а не при формировании. */
    public ExpressionCheck validateExpression(String expression) {
        if (expression == null || expression.isBlank()) {
            return new ExpressionCheck(false, "The expression is empty", List.of());
        }
        try {
            Expr parsed = ExprParser.parse(expression);
            List<String> fields = new ArrayList<>();
            collectFields(parsed, fields);
            return new ExpressionCheck(true, null, fields);
        } catch (RuntimeException e) {
            return new ExpressionCheck(false, e.getMessage(), List.of());
        }
    }

    /** Настройки конкретного варианта отчёта (или настроек по умолчанию). */
    public DcsSettings effectiveSettings(SettingsBundle bundle, String variantId, DcsSettings user) {
        DcsSettings base = bundle == null || bundle.defaultSettings == null
                ? new DcsSettings() : bundle.defaultSettings;
        if (variantId != null && !variantId.isBlank() && bundle != null) {
            SettingsBundle.Variant v = bundle.variant(variantId);
            if (v != null && v.settings != null) base = SettingsComposer.compose(base, v.settings);
        }
        return SettingsComposer.compose(base, user);
    }

    public ReportStoreBridge store() { return store; }

    /** Поля наборов и вычисляемые поля — набор колонок детальной расшифровки. */
    private List<String> detailSelection(DcsSchema schema, DcsSettings settings) {
        FieldCatalog catalog = new FieldCatalog(schema, settings);
        List<String> out = new ArrayList<>();
        for (FieldCatalog.FieldInfo f : catalog.all()) {
            if (!f.usableInSelection()) continue;
            if (f.kind() == FieldCatalog.Kind.SOURCE || f.kind() == FieldCatalog.Kind.CALCULATED) {
                out.add(f.id());
            }
        }
        return out;
    }

    private void applyDefaultMaxRows(DcsSettings settings) {
        if (settings.outputParameters == null) {
            settings.outputParameters = new DcsSettings.OutputParameters();
        }
        if (settings.outputParameters.maxRows == null) {
            settings.outputParameters.maxRows = properties.getMaxRows();
        }
    }

    private String resolveDataSource(String id) {
        return id == null || id.isBlank() ? properties.getDefaultDataSourceId() : id;
    }

    /** Грубое, но достаточное сопоставление типа JDBC с типом значения для UI. */
    static String valueTypeOf(String sqlTypeName) {
        if (sqlTypeName == null) return "string";
        String t = sqlTypeName.toLowerCase(Locale.ROOT);
        if (t.contains("int") || t.contains("dec") || t.contains("num")
                || t.contains("double") || t.contains("real") || t.contains("float")) return "number";
        if (t.contains("bool") || t.contains("bit")) return "boolean";
        if (t.contains("timestamp") || t.contains("date") || t.contains("time")) return "date";
        return "string";
    }

    private static void collectFields(Expr e, List<String> out) {
        if (e == null) return;
        switch (e) {
            case Expr.Field f -> out.add(f.path());
            case Expr.Unary u -> collectFields(u.operand(), out);
            case Expr.Binary b -> { collectFields(b.left(), out); collectFields(b.right(), out); }
            case Expr.Call c -> c.args().forEach(a -> collectFields(a, out));
            case Expr.Case c -> {
                for (Expr.Branch br : c.branches()) { collectFields(br.when(), out); collectFields(br.then(), out); }
                collectFields(c.otherwise(), out);
            }
            case Expr.In i -> { collectFields(i.value(), out); i.options().forEach(o -> collectFields(o, out)); }
            case Expr.Between b -> {
                collectFields(b.value(), out); collectFields(b.low(), out); collectFields(b.high(), out);
            }
            case Expr.IsNull n -> collectFields(n.value(), out);
            case Expr.Lit ignored -> { }
            case Expr.Param ignored -> { }
        }
    }

    /** Значения параметров по умолчанию — форма параметров открывается заполненной. */
    public Map<String, Object> defaultParameters(DcsSchema schema) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (schema == null || schema.parameters == null) return out;
        for (DcsSchema.Parameter p : schema.parameters) {
            if (p != null && p.name != null) out.put(p.name, p.value);
        }
        return out;
    }
}
