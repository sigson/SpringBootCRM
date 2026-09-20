package app.modules.dcs.engine;

import app.modules.dcs.model.CompositionResult;
import app.modules.dcs.model.DcsSchema;
import app.modules.dcs.model.DcsSettings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Конвейер компоновки без базы: компоновщик макета строит SQL, процессор собирает
 * дерево итогов из заранее заданных строк.
 *
 * <p>Отвязка от СУБД здесь принципиальна. Проверяется семантика компоновки — какие поля
 * попадают в запрос, где считается агрегация, как сворачиваются итоги, — и она не должна
 * зависеть ни от наличия таблиц, ни от диалекта.
 */
class CompositionEngineTest {

    // ------------------------------------------------------------------ фикстуры

    private static DcsSchema schema() {
        DcsSchema s = new DcsSchema();
        s.packed = """
                --#query Sales
                SELECT d.code AS code, d.amount AS amount, d.client AS client FROM deals d
                """;

        DcsSchema.DataSet ds = new DcsSchema.DataSet();
        ds.name = "Sales";
        ds.fields = new ArrayList<>(List.of(
                field("code", "Код", "string"),
                field("amount", "Сумма", "number"),
                field("client", "Клиент", "string")));
        s.dataSets = new ArrayList<>(List.of(ds));

        DcsSchema.ResourceField total = new DcsSchema.ResourceField();
        total.name = "total";
        total.title = "Итого";
        total.expression = "Сумма(Sales.amount)";
        s.resources = new ArrayList<>(List.of(total));

        return s;
    }

    private static DcsSchema.DataSetField field(String name, String title, String type) {
        DcsSchema.DataSetField f = new DcsSchema.DataSetField();
        f.name = name;
        f.title = title;
        f.valueType = type;
        return f;
    }

    private static DcsSettings.StructureNode grouping(String fieldId, String... selection) {
        DcsSettings.StructureNode n = new DcsSettings.StructureNode();
        n.field = fieldId;
        n.selection = new ArrayList<>(List.of(selection));
        return n;
    }

    /** Строки «как из JDBC»: метки колонок плюс значения в том же порядке. */
    private record Rows(List<String> labels, List<List<Object>> values) {}

    private static Rows salesRows() {
        return new Rows(
                List.of("sales_code", "sales_amount", "sales_client"),
                List.of(
                        List.of("D1", new BigDecimal("100"), "A"),
                        List.of("D2", new BigDecimal("50"), "B"),
                        List.of("D3", new BigDecimal("25"), "A")));
    }

    /**
     * Строки такими, какими их вернула бы СУБД в режиме агрегации: по строке на
     * группу, ресурс уже посчитан.
     */
    private static Rows aggregatedRows() {
        return new Rows(
                List.of("sales_client", "total"),
                List.of(
                        List.of("A", new BigDecimal("125")),
                        List.of("B", new BigDecimal("50"))));
    }

    /**
     * Добавляет к группировке узел детальных записей. Это и есть способ выключить
     * агрегацию в СУБД: детальные строки нельзя получить из свёрнутого запроса.
     */
    private static DcsSettings.StructureNode withDetails(DcsSettings.StructureNode node) {
        DcsSettings.StructureNode details = new DcsSettings.StructureNode();
        details.selection = List.of("Sales.code", "Sales.amount");
        node.children = List.of(details);
        return node;
    }

    private static CompositionResult run(DcsSchema schema, DcsSettings settings, Rows rows) {
        FieldCatalog catalog = new FieldCatalog(schema, settings);
        LayoutComposer.Composed layout = new LayoutComposer(schema, settings, catalog).compose();
        return new CompositionProcessor(schema, settings, catalog, layout)
                .process(rows.labels(), rows.values());
    }

    // -------------------------------------------------------------------- тесты

    @Test
    @DisplayName("Детальный режим: итоги считаются по исходным записям")
    void grouping_totals_over_detail_records() {
        DcsSettings settings = new DcsSettings();
        settings.structure = List.of(withDetails(grouping("Sales.client", "Sales.client", "total")));

        CompositionResult result = run(schema(), settings, salesRows());

        CompositionResult.Node root = result.rows.get(0);
        assertThat(root.kind).isEqualTo("grandTotal");
        assertThat(root.cells.get("total")).isEqualTo(new BigDecimal("175"));
        assertThat(root.children).hasSize(2);

        CompositionResult.Node a = root.children.stream()
                .filter(n -> "A".equals(n.value)).findFirst().orElseThrow();
        assertThat(a.cells.get("total")).isEqualTo(new BigDecimal("125"));
        assertThat(a.rowCount).isEqualTo(2);
        // Данные расшифровки несут значение группировки — по ним строится drill-down.
        assertThat(a.details).containsEntry("Sales.client", "A");
        // Под группировкой — сами записи.
        assertThat(a.children).hasSize(2);
        assertThat(a.children.get(0).kind).isEqualTo("detail");
    }

    @Test
    @DisplayName("Режим SQL-агрегации: итоги верхних уровней сворачиваются из групп СУБД")
    void grouping_totals_rolled_up_from_sql_groups() {
        DcsSettings settings = new DcsSettings();
        settings.structure = List.of(grouping("Sales.client", "Sales.client", "total"));

        CompositionResult result = run(schema(), settings, aggregatedRows());

        assertThat(result.aggregatedInSql).isTrue();
        // Общий итог получен свёрткой 125 + 50, а не повторным суммированием записей.
        assertThat(result.rows.get(0).cells.get("total")).isEqualTo(new BigDecimal("175"));
        assertThat(result.rows.get(0).children).hasSize(2);
        assertThat(result.rows.get(0).children.stream()
                .filter(n -> "A".equals(n.value)).findFirst().orElseThrow()
                .cells.get("total")).isEqualTo(new BigDecimal("125"));
    }

    @Test
    @DisplayName("Запрос выбирает только те поля, которые нужны настройкам")
    void query_selects_only_used_fields() {
        DcsSettings settings = new DcsSettings();
        settings.structure = List.of(grouping("Sales.client", "Sales.client", "total"));

        FieldCatalog catalog = new FieldCatalog(schema(), settings);
        LayoutComposer.Composed layout = new LayoutComposer(schema(), settings, catalog).compose();

        // Группировка + ресурс используют client и amount; code не выбран никем.
        assertThat(layout.sql()).contains("Sales.client").contains("Sales.amount");
        assertThat(layout.sql()).doesNotContain("Sales.code AS");
    }

    @Test
    @DisplayName("Простой агрегат без детальных записей уходит в GROUP BY СУБД")
    void simple_aggregate_is_pushed_into_sql() {
        DcsSettings settings = new DcsSettings();
        settings.structure = List.of(grouping("Sales.client", "Sales.client", "total"));

        FieldCatalog catalog = new FieldCatalog(schema(), settings);
        LayoutComposer.Composed layout = new LayoutComposer(schema(), settings, catalog).compose();

        assertThat(layout.aggregatedInSql()).isTrue();
        assertThat(layout.sql()).contains("SUM(Sales.amount) AS total");
        assertThat(layout.sql()).contains("GROUP BY Sales.client");
        // Свёртка уровней — сумма готовых сумм.
        assertThat(layout.rollupExpressions()).containsEntry("total", "Сумма(total)");
    }

    @Test
    @DisplayName("Детальные записи в структуре отменяют агрегацию в СУБД")
    void detail_records_disable_sql_aggregation() {
        DcsSettings settings = new DcsSettings();
        DcsSettings.StructureNode client = grouping("Sales.client", "Sales.client", "total");
        DcsSettings.StructureNode details = new DcsSettings.StructureNode();
        details.selection = List.of("Sales.code", "Sales.amount");
        client.children = List.of(details);
        settings.structure = List.of(client);

        FieldCatalog catalog = new FieldCatalog(schema(), settings);
        LayoutComposer.Composed layout = new LayoutComposer(schema(), settings, catalog).compose();

        assertThat(layout.aggregatedInSql()).isFalse();
        assertThat(layout.sql()).doesNotContain("GROUP BY");
    }

    @Test
    @DisplayName("Среднее нельзя свернуть по группам — агрегация остаётся в процессоре")
    void average_resource_is_not_pushed_down() {
        DcsSchema s = schema();
        s.resources.get(0).expression = "Среднее(Sales.amount)";

        DcsSettings settings = new DcsSettings();
        settings.structure = List.of(grouping("Sales.client", "Sales.client", "total"));

        FieldCatalog catalog = new FieldCatalog(s, settings);
        LayoutComposer.Composed layout = new LayoutComposer(s, settings, catalog).compose();

        assertThat(layout.aggregatedInSql()).isFalse();
    }

    @Test
    @DisplayName("Отбор по колонке набора уходит в WHERE параметром, а не текстом")
    void filter_is_pushed_down_as_bind_parameter() {
        DcsSettings settings = new DcsSettings();
        settings.structure = List.of(grouping("Sales.client", "Sales.client", "total"));
        DcsSettings.FilterNode item = new DcsSettings.FilterNode();
        item.left = "Sales.amount";
        item.op = DcsSettings.CompareOp.GE;
        item.right = "40";
        DcsSettings.FilterGroup group = new DcsSettings.FilterGroup();
        group.items = List.of(item);
        settings.filter = group;

        FieldCatalog catalog = new FieldCatalog(schema(), settings);
        LayoutComposer.Composed layout = new LayoutComposer(schema(), settings, catalog).compose();

        assertThat(layout.sql()).contains("WHERE Sales.amount >= ?");
        // Значение приведено к типу поля и ушло биндом — в тексте запроса его нет.
        assertThat(layout.parameters()).containsExactly(new BigDecimal("40"));
        assertThat(layout.sql()).doesNotContain("40");
        assertThat(layout.filterPushedDown()).isTrue();
    }

    @Test
    @DisplayName("Отбор по вычисляемому полю остаётся процессору")
    void filter_on_calculated_field_is_not_pushed_down() {
        DcsSchema s = schema();
        DcsSchema.CalculatedField calc = new DcsSchema.CalculatedField();
        calc.name = "double";
        calc.expression = "Sales.amount * 2";
        calc.usableInFilter = true;
        s.calculatedFields = new ArrayList<>(List.of(calc));

        DcsSettings settings = new DcsSettings();
        settings.structure = List.of(grouping("Sales.client", "Sales.client", "total"));
        DcsSettings.FilterNode item = new DcsSettings.FilterNode();
        item.left = "double";
        item.op = DcsSettings.CompareOp.GT;
        item.right = "60";
        DcsSettings.FilterGroup group = new DcsSettings.FilterGroup();
        group.items = List.of(item);
        settings.filter = group;

        CompositionResult result = run(s, settings, salesRows());

        // 100*2 и 50*2 проходят, 25*2 — нет.
        assertThat(result.sourceRowCount).isEqualTo(2);
        assertThat(result.rows.get(0).cells.get("total")).isEqualTo(new BigDecimal("150"));
    }

    @Test
    @DisplayName("Вычисляемое поле считается построчно и доступно в выводе")
    void calculated_field_is_computed_per_row() {
        DcsSchema s = schema();
        DcsSchema.CalculatedField calc = new DcsSchema.CalculatedField();
        calc.name = "double";
        calc.title = "Удвоенная";
        calc.expression = "Sales.amount * 2";
        s.calculatedFields = new ArrayList<>(List.of(calc));

        DcsSettings settings = new DcsSettings();
        DcsSettings.StructureNode details = new DcsSettings.StructureNode();
        details.selection = List.of("Sales.code", "double");
        settings.structure = List.of(details);

        CompositionResult result = run(s, settings, salesRows());

        CompositionResult.Node first = result.rows.get(0).children.get(0);
        assertThat(first.kind).isEqualTo("detail");
        assertThat(first.cells.get("double")).isEqualTo(new BigDecimal("200"));
    }

    @Test
    @DisplayName("Условное оформление применяется к итогу группировки")
    void conditional_appearance_applies_to_group_totals() {
        DcsSettings settings = new DcsSettings();
        settings.structure = List.of(withDetails(grouping("Sales.client", "Sales.client", "total")));

        DcsSettings.FilterNode when = new DcsSettings.FilterNode();
        when.left = "total";
        when.op = DcsSettings.CompareOp.GT;
        when.right = new BigDecimal("100");
        DcsSettings.FilterGroup cond = new DcsSettings.FilterGroup();
        cond.items = List.of(when);

        DcsSettings.AppearanceItem rule = new DcsSettings.AppearanceItem();
        rule.filter = cond;
        rule.fields = List.of("total");
        rule.areas = List.of("groupTotals");
        rule.appearance = new DcsSettings.Appearance();
        rule.appearance.textColor = "#c62828";
        settings.conditionalAppearance = List.of(rule);

        CompositionResult result = run(schema(), settings, salesRows());

        CompositionResult.Node a = result.rows.get(0).children.stream()
                .filter(n -> "A".equals(n.value)).findFirst().orElseThrow();   // 125 > 100
        CompositionResult.Node b = result.rows.get(0).children.stream()
                .filter(n -> "B".equals(n.value)).findFirst().orElseThrow();   // 50

        assertThat(a.cellAppearance).isNotNull();
        assertThat(a.cellAppearance.get("total").textColor).isEqualTo("#c62828");
        assertThat(b.cellAppearance).isNull();
    }

    @Test
    @DisplayName("Порядок группировок берётся из настроек")
    void groups_are_ordered() {
        DcsSettings settings = new DcsSettings();
        settings.structure = List.of(withDetails(grouping("Sales.client", "Sales.client", "total")));
        DcsSettings.OrderItem order = new DcsSettings.OrderItem();
        order.field = "total";
        order.direction = "desc";
        settings.order = List.of(order);

        CompositionResult result = run(schema(), settings, salesRows());

        assertThat(result.rows.get(0).children)
                .extracting(n -> n.value)
                .containsExactly("A", "B");   // 125, затем 50
    }

    @Test
    @DisplayName("Кросс-таблица даёт матрицу ячеек «колонка|ресурс» и итог по строке")
    void cross_table_matrix() {
        DcsSettings settings = new DcsSettings();
        DcsSettings.StructureNode table = new DcsSettings.StructureNode();
        table.kind = DcsSettings.StructureNode.KIND_TABLE;
        table.rows = List.of(grouping("Sales.client"));
        table.columns = List.of(grouping("Sales.code"));
        table.selection = List.of("total");
        settings.structure = List.of(table);

        CompositionResult result = run(schema(), settings, salesRows());

        CompositionResult.Node t = result.rows.get(0);
        assertThat(t.kind).isEqualTo("table");
        assertThat(t.columnHeaders).hasSize(3);
        // Итог по всей таблице лежит под ключом без колонки.
        assertThat(t.cells.get("|total")).isEqualTo(new BigDecimal("175"));

        CompositionResult.Node a = t.children.stream()
                .filter(n -> "A".equals(n.value)).findFirst().orElseThrow();
        assertThat(a.cells.get("|total")).isEqualTo(new BigDecimal("125"));
        assertThat(a.cells.get("/D1|total")).isEqualTo(new BigDecimal("100"));
        assertThat(a.cells.get("/D2|total")).isNull();   // D2 принадлежит клиенту B
    }

    @Test
    @DisplayName("Без структуры выводится общий итог, а не ошибка")
    void empty_structure_yields_grand_total() {
        DcsSettings settings = new DcsSettings();
        settings.selection = List.of("total");

        CompositionResult result = run(schema(), settings, salesRows());

        assertThat(result.rows).hasSize(1);
        assertThat(result.rows.get(0).kind).isEqualTo("grandTotal");
        assertThat(result.rows.get(0).cells.get("total")).isEqualTo(new BigDecimal("175"));
    }

    @Test
    @DisplayName("Иерархическая группировка строится плоской и честно предупреждает")
    void hierarchy_falls_back_with_a_warning() {
        DcsSettings settings = new DcsSettings();
        DcsSettings.StructureNode node = withDetails(grouping("Sales.client", "Sales.client", "total"));
        node.groupingType = "hierarchy";
        settings.structure = List.of(node);

        CompositionResult result = run(schema(), settings, salesRows());

        assertThat(result.warnings).anySatisfy(w -> assertThat(w).contains("parent field"));
        assertThat(result.rows.get(0).children).hasSize(2);
    }

    @Test
    @DisplayName("Связь наборов доходит до SQL из настройки связей схемы")
    void schema_links_reach_the_sql() {
        DcsSchema s = schema();
        s.packed = """
                --#query Sales
                SELECT d.amount AS amount, d.client AS client FROM deals d
                --#query Clients
                SELECT c.id AS id, c.name AS name FROM clients c
                """;
        DcsSchema.DataSet clients = new DcsSchema.DataSet();
        clients.name = "Clients";
        clients.fields = new ArrayList<>(List.of(field("id", "Ид", "string"), field("name", "Имя", "string")));
        s.dataSets.add(clients);

        DcsSchema.DataSetLink link = new DcsSchema.DataSetLink();
        link.source = "Sales";
        link.target = "Clients";
        link.linkType = "left";
        DcsSchema.LinkCondition c = new DcsSchema.LinkCondition();
        // Конструктор хранит полные пути; компоновщик снимает «свой» префикс.
        c.sourceExpr = "Sales.client";
        c.targetExpr = "Clients.id";
        link.conditions = List.of(c);
        s.links = new ArrayList<>(List.of(link));

        DcsSettings settings = new DcsSettings();
        settings.structure = List.of(grouping("Clients.name", "Clients.name", "total"));

        FieldCatalog catalog = new FieldCatalog(s, settings);
        LayoutComposer.Composed layout = new LayoutComposer(s, settings, catalog).compose();

        assertThat(layout.sql())
                .contains("LEFT OUTER JOIN qp_Clients AS Clients ON Sales.client = Clients.id");
    }

    @Test
    @DisplayName("Короткое имя поля разрешается, когда оно однозначно")
    void short_field_name_resolves_when_unique() {
        DcsSettings settings = new DcsSettings();
        settings.structure = List.of(withDetails(grouping("client", "client", "total")));

        CompositionResult result = run(schema(), settings, salesRows());

        assertThat(result.rows.get(0).children).hasSize(2);
        assertThat(result.rows.get(0).children.get(0).field).isEqualTo("Sales.client");
    }

    @Test
    @DisplayName("Пустые значения группируются вместе и подписаны явно")
    void null_group_is_explicit() {
        Rows rows = new Rows(
                List.of("sales_code", "sales_amount", "sales_client"),
                List.of(
                        java.util.Arrays.asList("D1", new BigDecimal("10"), null),
                        java.util.Arrays.asList("D2", new BigDecimal("20"), null)));

        DcsSettings settings = new DcsSettings();
        settings.structure = List.of(withDetails(grouping("Sales.client", "Sales.client", "total")));

        CompositionResult result = run(schema(), settings, rows);

        assertThat(result.rows.get(0).children).hasSize(1);
        assertThat(result.rows.get(0).children.get(0).display).isEqualTo("<empty>");
        assertThat(result.rows.get(0).children.get(0).cells.get("total")).isEqualTo(new BigDecimal("30"));
    }

    @Test
    @DisplayName("Настройки без единого поля набора — понятная ошибка, а не пустой запрос")
    void settings_without_any_source_field_fail_clearly() {
        DcsSchema s = schema();
        s.resources = new ArrayList<>();
        DcsSettings settings = new DcsSettings();

        FieldCatalog catalog = new FieldCatalog(s, settings);
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> new LayoutComposer(s, settings, catalog).compose())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no dataset field");
    }

    @Test
    @DisplayName("Наложение пользовательских настроек не затирает незаданные разделы")
    void settings_composer_overlays_only_what_is_set() {
        DcsSettings base = new DcsSettings();
        base.structure = List.of(grouping("Sales.client", "total"));
        base.outputParameters.title = "Отчёт";
        base.dataParameters = new java.util.LinkedHashMap<>(Map.of("A", 1, "B", 2));

        DcsSettings user = new DcsSettings();
        user.dataParameters = new java.util.LinkedHashMap<>(Map.of("B", 20));

        DcsSettings merged = SettingsComposer.compose(base, user);

        assertThat(merged.structure).hasSize(1);                      // структура сохранилась
        assertThat(merged.outputParameters.title).isEqualTo("Отчёт"); // заголовок сохранился
        assertThat(merged.dataParameters).containsEntry("A", 1)       // параметры слились
                                         .containsEntry("B", 20);
    }
}
