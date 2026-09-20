package app.modules.dcs.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Сквозной тест генератора отчётов: справочник отчётов хоста, пакет запросов
 * Workbench'а и движок компоновки — на демо-данных первой миграции.
 *
 * <p>Тест включает оба модуля явно: профиль {@code test} их выключает, и это само по
 * себе полезное свойство — остальные 40 тестов проходят на приложении без модулей,
 * подтверждая, что хост от них не зависит.
 */
// Класс приложения указан явно: тест лежит в дереве модуля (app.modules.dcs.*),
// и поиск @SpringBootConfiguration вверх по пакетам до app.springbootcrm не доходит.
@SpringBootTest(
        classes = app.springbootcrm.SpringBootCrmApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = { "sqlworkbench.enabled=true", "dcs.enabled=true" })
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DcsCompositionIT {

    @Autowired private TestRestTemplate http;
    @LocalServerPort private int port;
    private final ObjectMapper json = new ObjectMapper();

    private String token;
    private String reportId;

    /**
     * Схема: два набора в одной упакованной строке плюс связь между ними — ровно тот
     * сценарий, ради которого пакет запросов и появился.
     */
    private static final String PACKED = """
            --#query Deals
            SELECT d.code AS code, d.amount AS amount, d.customer_id AS customer_id FROM deals d
            --#query Customers
            SELECT c.id AS id, c.name AS name FROM customers c
            --#link Deals -> Customers LEFT ON customer_id = id
            """;

    /** Набор с параметром: его исполняют и автозаполнение полей, и предпросмотр. */
    private static final String PACKED_WITH_PARAM =
            "--#query Filtered\n"
            + "SELECT d.code AS code, d.amount AS amount FROM deals d WHERE d.amount >= &MinAmount\n";

    @BeforeAll
    void setUp() throws Exception {
        ResponseEntity<String> login = http.postForEntity(url("/api/auth/login"),
                new HttpEntity<>(json.writeValueAsString(
                        Map.of("username", "admin", "password", "admin")), jsonHeaders()),
                String.class);
        assertThat(login.getStatusCode().is2xxSuccessful()).isTrue();
        token = json.readTree(login.getBody()).get("token").asText();

        ResponseEntity<String> created = post("/api/reports", Map.of(
                "name", "Sales by customer " + UUID.randomUUID(),
                "scheme", schema(),
                "settings", Map.of("defaultSettings", settings()),
                "dataSourceId", "main"));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        reportId = json.readTree(created.getBody()).get("id").asText();
    }

    private static Map<String, Object> schema() {
        return Map.of(
                "packed", PACKED,
                "dataSets", List.of(
                        Map.of("name", "Deals", "fields", List.of(
                                Map.of("name", "code", "title", "Code", "valueType", "string"),
                                Map.of("name", "amount", "title", "Amount", "valueType", "number"),
                                Map.of("name", "customer_id", "title", "Customer id", "valueType", "string"))),
                        Map.of("name", "Customers", "fields", List.of(
                                Map.of("name", "id", "title", "Id", "valueType", "string"),
                                Map.of("name", "name", "title", "Customer", "valueType", "string")))),
                "links", List.of(Map.of(
                        "source", "Deals", "target", "Customers", "linkType", "left",
                        "conditions", List.of(Map.of(
                                "sourceExpr", "Deals.customer_id",
                                "operator", "=",
                                "targetExpr", "Customers.id")))),
                "resources", List.of(
                        Map.of("name", "total", "title", "Total", "expression", "Сумма(Deals.amount)"),
                        Map.of("name", "cnt", "title", "Count", "expression", "Количество(Deals.code)")),
                "parameters", List.of(
                        Map.of("name", "MinAmount", "title", "Amount from",
                                "valueType", "number", "value", 0)));
    }

    private static Map<String, Object> settings() {
        return Map.of(
                "structure", List.of(Map.of(
                        "id", "byCustomer", "kind", "grouping", "field", "Customers.name",
                        "selection", List.of("Customers.name", "total", "cnt"))),
                "filter", Map.of("combinator", "and", "items", List.of(
                        Map.of("left", "Deals.amount", "op", "ge", "right", "&MinAmount"))),
                "dataParameters", Map.of("MinAmount", 1000),
                "outputParameters", Map.of("title", "Sales by customer"));
    }

    // ------------------------------------------------------------------ тесты

    @Test
    @DisplayName("Сохранённый отчёт формируется: общий итог равен сумме групп")
    void composes_saved_report() throws Exception {
        JsonNode result = jsonOf(post("/api/dcs/reports/" + reportId + "/compose", Map.of()));

        assertThat(result.get("title").asText()).isEqualTo("Sales by customer");
        JsonNode root = result.get("rows").get(0);
        assertThat(root.get("kind").asText()).isEqualTo("grandTotal");

        // Демо-данные первой миграции: 24000 + 58000 + 9500.
        assertThat(root.get("cells").get("total").asDouble()).isEqualTo(91500.0);
        assertThat(root.get("cells").get("cnt").asInt()).isEqualTo(3);

        JsonNode groups = root.get("children");
        assertThat(groups).hasSize(3);
        double sum = 0;
        for (JsonNode g : groups) {
            assertThat(g.get("field").asText()).isEqualTo("Customers.name");
            sum += g.get("cells").get("total").asDouble();
        }
        assertThat(sum).isEqualTo(91500.0);
    }

    @Test
    @DisplayName("Пакет из двух запросов и связь собираются в один SQL с CTE и JOIN")
    void preview_sql_assembles_the_pack() throws Exception {
        JsonNode preview = jsonOf(post("/api/dcs/preview-sql",
                Map.of("scheme", schema(), "settings", settings())));

        String sql = preview.get("sql").asText();
        assertThat(sql).contains("WITH qp_Deals AS (");
        assertThat(sql).contains("qp_Customers AS (");
        assertThat(sql).contains("LEFT OUTER JOIN qp_Customers AS Customers "
                + "ON Deals.customer_id = Customers.id");
        // Значение отбора ушло параметром, а не в текст запроса.
        assertThat(sql).contains("?");
        assertThat(preview.get("parameterCount").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("Отбор по параметру действительно отсекает записи")
    void filter_parameter_is_applied() throws Exception {
        JsonNode all = jsonOf(post("/api/dcs/compose", Map.of(
                "scheme", schema(),
                "settings", withParameter(0),
                "dataSourceId", "main")));
        JsonNode filtered = jsonOf(post("/api/dcs/compose", Map.of(
                "scheme", schema(),
                "settings", withParameter(30000),
                "dataSourceId", "main")));

        assertThat(all.get("rows").get(0).get("cells").get("cnt").asInt()).isEqualTo(3);
        assertThat(filtered.get("rows").get(0).get("cells").get("cnt").asInt()).isEqualTo(1);
        assertThat(filtered.get("rows").get(0).get("cells").get("total").asDouble()).isEqualTo(58000.0);
    }

    private static Map<String, Object> withParameter(int minAmount) {
        return Map.of(
                "structure", List.of(Map.of(
                        "id", "byCustomer", "kind", "grouping", "field", "Customers.name",
                        "selection", List.of("Customers.name", "total", "cnt"))),
                "filter", Map.of("combinator", "and", "items", List.of(
                        Map.of("left", "Deals.amount", "op", "ge", "right", "&MinAmount"))),
                "dataParameters", Map.of("MinAmount", minAmount));
    }

    @Test
    @DisplayName("Доступные поля отчёта включают колонки наборов и ресурсы")
    void available_fields_lists_sources_and_resources() throws Exception {
        ResponseEntity<String> r = http.exchange(
                url("/api/dcs/reports/" + reportId + "/available-fields"),
                HttpMethod.GET, new HttpEntity<>(authHeaders()), String.class);
        assertThat(r.getStatusCode().is2xxSuccessful()).isTrue();

        JsonNode meta = json.readTree(r.getBody());
        List<String> ids = meta.get("fields").findValuesAsText("id");
        assertThat(ids).contains("Deals.amount", "Customers.name", "total", "cnt");
        assertThat(meta.get("parameters").get(0).get("name").asText()).isEqualTo("MinAmount");
    }

    @Test
    @DisplayName("Автозаполнение полей читает колонки каждого набора пакета")
    void describes_dataset_columns() throws Exception {
        JsonNode described = jsonOf(post("/api/dcs/describe-datasets",
                Map.of("packed", PACKED, "dataSourceId", "main")));

        assertThat(described).hasSize(2);
        assertThat(described.get(0).get("name").asText()).isEqualTo("Deals");
        List<String> columns = described.get(0).get("columns").findValuesAsText("name");
        assertThat(columns).containsExactlyInAnyOrder("code", "amount", "customer_id");
        assertThat(described.get(0).get("columns").get(1).get("valueType").asText()).isEqualTo("number");
    }

    @Test
    @DisplayName("Автозаполнение полей работает и для набора с параметром")
    void describes_dataset_with_a_parameter() throws Exception {
        JsonNode described = jsonOf(post("/api/dcs/describe-datasets", Map.of(
                "packed", PACKED_WITH_PARAM, "dataSourceId", "main",
                "parameters", Map.of("MinAmount", 1000))));

        // До появления связывания параметров этот вызов падал на первом же &Имя —
        // то есть ровно там, где подсказка по колонкам нужна больше всего.
        assertThat(described.get(0).get("error").isNull()).isTrue();
        assertThat(described.get(0).get("columns").findValuesAsText("name"))
                .containsExactlyInAnyOrder("code", "amount");
    }

    @Test
    @DisplayName("Предпросмотр набора связывает параметр и отбирает по нему")
    void previews_one_dataset() throws Exception {
        JsonNode all = jsonOf(post("/api/dcs/preview-dataset", Map.of(
                "packed", PACKED_WITH_PARAM, "dataSet", "Filtered", "dataSourceId", "main",
                "parameters", Map.of("MinAmount", 0), "limit", 10)));
        JsonNode filtered = jsonOf(post("/api/dcs/preview-dataset", Map.of(
                "packed", PACKED_WITH_PARAM, "dataSet", "Filtered", "dataSourceId", "main",
                "parameters", Map.of("MinAmount", 30000), "limit", 10)));

        assertThat(all.get("rows")).hasSize(3);
        assertThat(filtered.get("rows")).hasSize(1);
        // Значение ушло биндом, а не в текст запроса.
        assertThat(filtered.get("sql").asText()).contains("?").doesNotContain("30000");
    }

    @Test
    @DisplayName("Предпросмотр неизвестного набора отвечает ошибкой, а не падением")
    void preview_of_unknown_dataset_reports_error() throws Exception {
        JsonNode r = jsonOf(post("/api/dcs/preview-dataset", Map.of(
                "packed", PACKED, "dataSet", "NoSuchSet", "dataSourceId", "main",
                "parameters", Map.of())));

        assertThat(r.get("error").asText()).contains("NoSuchSet");
        assertThat(r.get("rows")).isEmpty();
    }

    @Test
    @DisplayName("Расшифровка ячейки возвращает исходные записи группировки")
    void drilldown_returns_detail_records() throws Exception {
        JsonNode composed = jsonOf(post("/api/dcs/reports/" + reportId + "/compose", Map.of()));
        JsonNode group = composed.get("rows").get(0).get("children").get(0);
        String customer = group.get("value").asText();

        JsonNode drill = jsonOf(post("/api/dcs/reports/" + reportId + "/drilldown", Map.of(
                "details", Map.of("Customers.name", customer),
                "action", "detail")));

        JsonNode details = drill.get("rows").get(0).get("children");
        assertThat(details).isNotEmpty();
        assertThat(details.get(0).get("kind").asText()).isEqualTo("detail");
        // В расшифровке видны поля наборов, а не только ресурсы.
        assertThat(details.get(0).get("cells").has("Deals.code")).isTrue();
    }

    @Test
    @DisplayName("Выгрузка в XLSX отдаёт настоящий файл книги")
    void exports_xlsx() {
        ResponseEntity<byte[]> r = http.exchange(
                url("/api/dcs/reports/" + reportId + "/export?format=xlsx"),
                HttpMethod.POST, new HttpEntity<>("{}", authJsonHeaders()), byte[].class);

        assertThat(r.getStatusCode().is2xxSuccessful()).isTrue();
        byte[] body = r.getBody();
        assertThat(body).isNotNull();
        // Сигнатура zip: книга Excel — это zip-контейнер.
        assertThat(new byte[] { body[0], body[1] }).containsExactly('P', 'K');
        assertThat(r.getHeaders().getFirst("Content-Disposition")).contains("attachment");
    }

    @Test
    @DisplayName("Выгрузка в CSV начинается с BOM и содержит итог")
    void exports_csv() {
        ResponseEntity<String> r = http.exchange(
                url("/api/dcs/reports/" + reportId + "/export?format=csv"),
                HttpMethod.POST, new HttpEntity<>("{}", authJsonHeaders()), String.class);

        assertThat(r.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(r.getBody()).startsWith("﻿");
        assertThat(r.getBody()).contains("91500");
    }

    @Test
    @DisplayName("Анонимный запрос на компоновку отклоняется")
    void anonymous_compose_is_rejected() {
        ResponseEntity<String> r = http.exchange(
                url("/api/dcs/reports/" + reportId + "/compose"),
                HttpMethod.POST, new HttpEntity<>("{}", jsonHeaders()), String.class);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("Ошибка в выражении ресурса сообщается проверкой, а не падением компоновки")
    void expression_validation_reports_errors() throws Exception {
        JsonNode bad = jsonOf(post("/api/dcs/validate-expression", Map.of("expression", "Сумма(")));
        JsonNode good = jsonOf(post("/api/dcs/validate-expression",
                Map.of("expression", "Сумма(Deals.amount) / Количество(Deals.code)")));

        assertThat(bad.get("valid").asBoolean()).isFalse();
        assertThat(bad.get("message").asText()).isNotBlank();
        assertThat(good.get("valid").asBoolean()).isTrue();
        assertThat(good.get("fields").findValuesAsText("")).isNotNull();
    }

    // --------------------------------------------------------------- helpers

    private URI url(String path) { return URI.create("http://localhost:" + port + path); }

    private HttpHeaders jsonHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private HttpHeaders authHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return h;
    }

    private HttpHeaders authJsonHeaders() {
        HttpHeaders h = jsonHeaders();
        h.setBearerAuth(token);
        return h;
    }

    private ResponseEntity<String> post(String path, Map<String, ?> body) throws Exception {
        return http.postForEntity(url(path),
                new HttpEntity<>(json.writeValueAsString(body), authJsonHeaders()), String.class);
    }

    private JsonNode jsonOf(ResponseEntity<String> r) throws Exception {
        assertThat(r.getStatusCode().is2xxSuccessful())
                .withFailMessage("Expected 2xx, got %s: %s", r.getStatusCode(), r.getBody())
                .isTrue();
        return json.readTree(r.getBody());
    }
}
