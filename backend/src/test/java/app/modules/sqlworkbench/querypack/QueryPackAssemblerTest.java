package app.modules.sqlworkbench.querypack;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Сборка нескольких запросов в один SQL по настройке связей. */
class QueryPackAssemblerTest {

    @Test
    @DisplayName("Два набора и связь превращаются в CTE плюс JOIN")
    void assembles_cte_and_join() {
        QueryPack pack = QueryPackCodec.decode("""
                --#query Sales
                SELECT d.id, d.currency, d.amount FROM deals d
                --#query Rates
                SELECT r.currency, r.rate FROM exchange_rates r
                --#link Sales -> Rates LEFT ON currency = currency
                --#select Sales.amount AS amount, Rates.rate AS rate
                """);

        String sql = QueryPackAssembler.assemble(pack).sql();

        assertThat(sql).contains("WITH qp_Sales AS (");
        assertThat(sql).contains("qp_Rates AS (");
        assertThat(sql).contains("FROM qp_Sales AS Sales");
        assertThat(sql).contains("LEFT OUTER JOIN qp_Rates AS Rates ON Sales.currency = Rates.currency");
        assertThat(sql).contains("SELECT Sales.amount AS amount, Rates.rate AS rate");
    }

    @Test
    @DisplayName("Имя CTE не совпадает с именем набора — иначе одноимённая таблица перебьёт выражение")
    void cte_name_is_prefixed_to_avoid_table_shadowing() {
        QueryPack pack = QueryPackCodec.decode("""
                --#query deals
                SELECT 1 AS n
                --#query other
                SELECT 2 AS m
                --#link deals -> other CROSS
                """);

        String sql = QueryPackAssembler.assemble(pack).sql();

        // Ссылки в выражениях пишутся по имени набора, а физическое имя — с префиксом.
        assertThat(sql).contains("qp_deals AS (");
        assertThat(sql).contains("FROM qp_deals AS deals");
    }

    @Test
    @DisplayName("Единственный набор без связей оборачивается в подзапрос, а не в CTE")
    void single_dataset_uses_subquery() {
        QueryPack pack = QueryPackCodec.decode("SELECT 1 AS n");

        String sql = QueryPackAssembler.assemble(pack).sql();

        assertThat(sql).doesNotContain("WITH ");
        assertThat(sql).contains("FROM ( SELECT 1 AS n ) Query1");
    }

    @Test
    @DisplayName("Связь, известная «с другой стороны», применяется зеркально")
    void link_applied_from_the_other_side_is_mirrored() {
        // Стартовый набор — источник первой связи (A). Вторая связь C -> B приходит
        // тогда, когда известен уже B, поэтому LEFT обязан стать RIGHT.
        QueryPack pack = QueryPackCodec.decode("""
                --#query A
                SELECT 1 AS a
                --#query B
                SELECT 2 AS b
                --#query C
                SELECT 3 AS c
                --#link A -> B INNER ON a = b
                --#link C -> B LEFT ON c = b
                """);

        String sql = QueryPackAssembler.assemble(pack).sql();

        assertThat(sql).contains("INNER JOIN qp_B AS B ON A.a = B.b");
        assertThat(sql).contains("RIGHT OUTER JOIN qp_C AS C ON C.c = B.b");
    }

    @Test
    @DisplayName("Набор без связей подключается CROSS JOIN'ом и даёт предупреждение")
    void unlinked_dataset_warns() {
        QueryPack pack = QueryPackCodec.decode("""
                --#query A
                SELECT 1 AS a
                --#query B
                SELECT 2 AS b
                --#query Orphan
                SELECT 3 AS o
                --#link A -> B INNER ON a = b
                """);

        QueryPackAssembler.Assembled assembled = QueryPackAssembler.assemble(pack);

        assertThat(assembled.sql()).contains("CROSS JOIN qp_Orphan AS Orphan");
        assertThat(assembled.warnings()).anySatisfy(
                w -> assertThat(w).contains("Orphan").contains("CROSS JOIN"));
    }

    @Test
    @DisplayName("Без явного списка полей выводятся все колонки участвующих наборов")
    void default_select_is_star_per_dataset() {
        QueryPack pack = QueryPackCodec.decode("""
                --#query A
                SELECT 1 AS a
                --#query B
                SELECT 2 AS b
                --#link A -> B INNER ON a = b
                """);

        assertThat(QueryPackAssembler.assemble(pack).sql()).contains("SELECT A.*, B.*");
    }

    @Test
    @DisplayName("Недопустимое имя набора отвергается с внятным сообщением")
    void invalid_dataset_name_rejected() {
        QueryPack pack = new QueryPack();
        pack.queries.add(new QueryPack.PackedQuery("bad name", "SELECT 1"));

        assertThatThrownBy(() -> QueryPackAssembler.assemble(pack))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bad name");
    }

    @Test
    @DisplayName("Повторяющееся имя набора отвергается")
    void duplicate_dataset_name_rejected() {
        QueryPack pack = new QueryPack();
        pack.queries.add(new QueryPack.PackedQuery("A", "SELECT 1"));
        pack.queries.add(new QueryPack.PackedQuery("A", "SELECT 2"));

        assertThatThrownBy(() -> QueryPackAssembler.assemble(pack))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate");
    }
}
