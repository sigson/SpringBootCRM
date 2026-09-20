package app.modules.sqlworkbench.querypack;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Разбор и сборка упакованной строки — формата, которым отчёт передаёт Workbench'у
 * сразу несколько запросов и настройку связей между ними.
 */
class QueryPackCodecTest {

    @Test
    @DisplayName("Плоский SELECT без разметки — это пакет с одним набором")
    void plain_select_becomes_single_dataset() {
        QueryPack pack = QueryPackCodec.decode("SELECT 1 AS n FROM dual;");

        assertThat(pack.queries).hasSize(1);
        assertThat(pack.queries.get(0).name).isEqualTo("Query1");
        // Завершающая точка с запятой снимается: набор встраивается в CTE.
        assertThat(pack.queries.get(0).sql).isEqualTo("SELECT 1 AS n FROM dual");
    }

    @Test
    @DisplayName("Несколько запросов и связь между ними читаются из одной строки")
    void decodes_datasets_and_links() {
        QueryPack pack = QueryPackCodec.decode("""
                --#pack Sales report
                --#query Sales | Продажи
                SELECT d.id, d.amount, d.currency FROM deals d
                --#query Rates
                SELECT r.currency, r.rate FROM exchange_rates r
                --#link Sales -> Rates LEFT ON currency = currency
                --#select Sales.amount * Rates.rate AS base
                --#where Sales.amount > 0
                --#order base DESC
                --#limit 500
                """);

        assertThat(pack.name).isEqualTo("Sales report");
        assertThat(pack.queries).hasSize(2);
        assertThat(pack.queries.get(0).title).isEqualTo("Продажи");
        assertThat(pack.queries.get(1).sql).contains("exchange_rates");

        assertThat(pack.links).hasSize(1);
        QueryPack.PackLink link = pack.links.get(0);
        assertThat(link.source).isEqualTo("Sales");
        assertThat(link.target).isEqualTo("Rates");
        assertThat(link.type).isEqualTo(QueryPack.LinkType.LEFT);
        assertThat(link.conditions).hasSize(1);
        assertThat(link.conditions.get(0).sourceExpr).isEqualTo("currency");
        assertThat(link.conditions.get(0).targetExpr).isEqualTo("currency");

        assertThat(pack.select).containsExactly("Sales.amount * Rates.rate AS base");
        assertThat(pack.where).containsExactly("Sales.amount > 0");
        assertThat(pack.orderBy).containsExactly("base DESC");
        assertThat(pack.limit).isEqualTo(500);
    }

    @Test
    @DisplayName("Запятая внутри скобок не режет список выражений SELECT'а")
    void select_list_respects_parentheses() {
        QueryPack pack = QueryPackCodec.decode("""
                --#query A
                SELECT 1
                --#select COALESCE(a, b) AS x, c AS y
                """);

        assertThat(pack.select).containsExactly("COALESCE(a, b) AS x", "c AS y");
    }

    @Test
    @DisplayName("Сборка строки и обратный разбор дают ту же структуру")
    void encode_decode_round_trip() {
        QueryPack original = QueryPackCodec.decode("""
                --#query Sales
                SELECT d.id, d.currency FROM deals d
                --#query Rates
                SELECT r.currency FROM exchange_rates r
                --#link Sales -> Rates INNER ON currency = currency
                """);

        QueryPack again = QueryPackCodec.decode(QueryPackCodec.encode(original));

        assertThat(again.queries).hasSize(2);
        assertThat(again.queries.get(0).name).isEqualTo("Sales");
        assertThat(again.links).hasSize(1);
        assertThat(again.links.get(0).type).isEqualTo(QueryPack.LinkType.INNER);
        assertThat(QueryPackCodec.onText(again.links.get(0))).isEqualTo("currency = currency");
    }

    @Test
    @DisplayName("Неразбираемое ON-условие сохраняется целиком, а не теряется")
    void unparseable_condition_kept_raw() {
        QueryPack pack = QueryPackCodec.decode("""
                --#query A
                SELECT 1
                --#query B
                SELECT 2
                --#link A -> B LEFT ON A.day BETWEEN B.from AND B.to
                """);

        QueryPack.PackLink link = pack.links.get(0);
        assertThat(link.conditions).isEmpty();
        assertThat(link.rawCondition).isEqualTo("A.day BETWEEN B.from AND B.to");
    }

    @Test
    @DisplayName("Неизвестная директива игнорируется — формат совместим вперёд")
    void unknown_directive_is_ignored() {
        QueryPack pack = QueryPackCodec.decode("""
                --#query A
                SELECT 1
                --#somethingNew whatever
                """);

        assertThat(pack.queries).hasSize(1);
    }
}
