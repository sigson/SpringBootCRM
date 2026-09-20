package app.modules.dcs.web;

import app.modules.dcs.bridge.ReportStoreBridge;
import app.modules.dcs.export.CsvExporter;
import app.modules.dcs.export.XlsxExporter;
import app.modules.dcs.model.CompositionResult;
import app.modules.dcs.model.DcsSchema;
import app.modules.dcs.model.DcsSettings;
import app.modules.dcs.service.DcsService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * REST генератора отчётов.
 *
 * <p>Два семейства эндпоинтов. Первое работает с <b>сохранённым отчётом</b> по его id —
 * им пользуется форма отчёта. Второе принимает схему и настройки <b>прямо в теле</b> —
 * им пользуется конструктор, где отчёт ещё не сохранён, а посмотреть результат уже надо.
 *
 * <p>Доступ обеспечивает справочник отчётов: чтение схемы идёт через access-checked
 * репозиторий хоста, а исполнение — через read-only датасорсы Workbench'а. Собственной
 * политики доступа модуль не заводит, чтобы не появилось второго, расходящегося
 * набора правил.
 */
@RestController("dcsController")
@RequestMapping("${dcs.base-path:/api/dcs}")
public class DcsController {

    private final DcsService service;
    private final ReportStoreBridge store;

    public DcsController(DcsService service, ReportStoreBridge store) {
        this.service = service;
        this.store = store;
    }

    /** Признак живого модуля: по 404 здесь фронтенд прячет раздел отчётов. */
    @GetMapping("/capabilities")
    public Map<String, Object> capabilities() {
        return Map.of("compose", true, "export", List.of("xlsx", "csv"), "drilldown", true);
    }

    // ------------------------------------------------------- сохранённый отчёт

    /** Формирование сохранённого отчёта. */
    @PostMapping("/reports/{id}/compose")
    public CompositionResult compose(@PathVariable UUID id, @RequestBody(required = false) ComposeRequest req) {
        ComposeRequest r = req == null ? new ComposeRequest(null, null) : req;
        return service.compose(id, r.variantId(), store.parseUserSettings(r.settings()));
    }

    /** Расшифровка ячейки сохранённого отчёта. */
    @PostMapping("/reports/{id}/drilldown")
    public CompositionResult drilldown(@PathVariable UUID id, @RequestBody DrilldownRequest req) {
        return service.drilldown(id, req.variantId(), store.parseUserSettings(req.settings()),
                req.details(), req.action(), req.field());
    }

    /** Доступные поля сохранённого отчёта — для формы пользовательских настроек. */
    @GetMapping("/reports/{id}/available-fields")
    public Map<String, Object> availableFields(@PathVariable UUID id) {
        ReportStoreBridge.Loaded report = store.load(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("fields", service.availableFields(report.schema(), report.settings().defaultSettings));
        out.put("parameters", report.schema().parameters);
        out.put("defaultSettings", report.settings().defaultSettings);
        out.put("variants", report.settings().variants);
        out.put("forms", report.forms());
        out.put("templates", report.templates());
        return out;
    }

    /** Выгрузка сформированного отчёта в файл. */
    @PostMapping("/reports/{id}/export")
    public ResponseEntity<byte[]> export(@PathVariable UUID id,
                                         @RequestParam(defaultValue = "xlsx") String format,
                                         @RequestBody(required = false) ComposeRequest req) {
        ComposeRequest r = req == null ? new ComposeRequest(null, null) : req;
        CompositionResult result = service.compose(id, r.variantId(), store.parseUserSettings(r.settings()));
        return fileResponse(result, format);
    }

    // ---------------------------------------------------------- конструктор

    /** Формирование по присланным схеме и настройкам (отчёт может быть ещё не сохранён). */
    @PostMapping("/compose")
    public CompositionResult composeAdHoc(@RequestBody AdHocRequest req) {
        return service.compose(schemaOf(req), settingsOf(req), req.dataSourceId());
    }

    /** Предпросмотр сгенерированного SQL. */
    @PostMapping("/preview-sql")
    public DcsService.SqlPreview previewSql(@RequestBody AdHocRequest req) {
        return service.previewSql(schemaOf(req), settingsOf(req));
    }

    /** Доступные поля присланной схемы. */
    @PostMapping("/available-fields")
    public List<DcsService.AvailableField> availableFieldsAdHoc(@RequestBody AdHocRequest req) {
        return service.availableFields(schemaOf(req), settingsOf(req));
    }

    /**
     * Какие колонки возвращают наборы пакета — автозаполнение полей набора данных
     * («Автозаполнение» в конструкторе схемы 1С).
     */
    @PostMapping("/describe-datasets")
    public List<DcsService.DescribedDataSet> describe(@RequestBody DescribeRequest req) {
        return service.describeDataSets(req.packed(), req.dataSourceId(), req.parameters());
    }

    /** Данные одного набора — предпросмотр из конструктора запроса. */
    @PostMapping("/preview-dataset")
    public DcsService.DataSetPreview previewDataSet(@RequestBody PreviewDataSetRequest req) {
        return service.previewDataSet(req.packed(), req.dataSet(), req.dataSourceId(),
                req.parameters(), req.limit());
    }

    /** Проверка выражения языка компоновки. */
    @PostMapping("/validate-expression")
    public DcsService.ExpressionCheck validate(@RequestBody ExpressionRequest req) {
        return service.validateExpression(req.expression());
    }

    /** Выгрузка результата, сформированного из присланных схемы и настроек. */
    @PostMapping("/export")
    public ResponseEntity<byte[]> exportAdHoc(@RequestParam(defaultValue = "xlsx") String format,
                                              @RequestBody AdHocRequest req) {
        return fileResponse(service.compose(schemaOf(req), settingsOf(req), req.dataSourceId()), format);
    }

    // ------------------------------------------------------------- служебное

    private DcsSchema schemaOf(AdHocRequest req) { return store.parseSchema(req.scheme()); }

    private DcsSettings settingsOf(AdHocRequest req) {
        DcsSettings s = store.parseUserSettings(req.settings());
        return s == null ? new DcsSettings() : s;
    }

    private ResponseEntity<byte[]> fileResponse(CompositionResult result, String format) {
        boolean csv = "csv".equalsIgnoreCase(format);
        byte[] body = csv ? CsvExporter.export(result) : XlsxExporter.export(result);
        String name = (result.title == null || result.title.isBlank() ? "report" : result.title)
                .replaceAll("[\\\\/:*?\"<>|]", "_") + (csv ? ".csv" : ".xlsx");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(csv
                ? new MediaType("text", "csv", StandardCharsets.UTF_8)
                : MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
        headers.setContentDisposition(ContentDisposition.attachment().filename(name, StandardCharsets.UTF_8).build());
        return new ResponseEntity<>(body, headers, org.springframework.http.HttpStatus.OK);
    }

    // ------------------------------------------------------------------ DTO

    public record ComposeRequest(String variantId, JsonNode settings) {}

    public record DrilldownRequest(
            String variantId, JsonNode settings,
            /** Значения группировок расшифровываемой ячейки. */
            Map<String, Object> details,
            /** {@code detail} — детальные записи; {@code groupBy} — разрез по полю. */
            String action,
            String field) {}

    public record AdHocRequest(JsonNode scheme, JsonNode settings, String dataSourceId) {}

    public record DescribeRequest(
            String packed, String dataSourceId,
            /** Значения {@code &Параметров}; без них набор с параметром не исполним. */
            Map<String, Object> parameters) {}

    public record PreviewDataSetRequest(
            String packed, String dataSet, String dataSourceId,
            Map<String, Object> parameters, Integer limit) {}

    public record ExpressionRequest(String expression) {}
}
