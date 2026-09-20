package app.modules.dcs.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Подстановка параметров — единственное место, где текст запроса встречается со
 * значениями. Тесты фиксируют то, ради чего этот класс и выделен: значение никогда
 * не попадает в SQL, порядок значений совпадает с порядком позиций, и один и тот же
 * проход обслуживает компоновку, автозаполнение полей и предпросмотр набора.
 */
class ParameterBinderTest {

    private static ParameterBinder.Bound bind(String sql, Map<String, Object> params) {
        return ParameterBinder.bind(sql, params::get, null);
    }

    @Test
    @DisplayName("Параметр заменяется на ?, значение уходит биндом")
    void replaces_parameter_with_placeholder() {
        var bound = bind("SELECT * FROM deals WHERE amount >= &MinAmount",
                Map.of("MinAmount", 1000));

        assertThat(bound.sql()).isEqualTo("SELECT * FROM deals WHERE amount >= ?");
        assertThat(bound.values()).containsExactly(1000);
        assertThat(bound.sql()).doesNotContain("1000");
    }

    @Test
    @DisplayName("Порядок значений совпадает с порядком позиций, повтор даёт два бинда")
    void keeps_positional_order() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("A", 1);
        params.put("B", 2);

        var bound = bind("SELECT &B, &A, &B FROM t", params);

        assertThat(bound.sql()).isEqualTo("SELECT ?, ?, ? FROM t");
        assertThat(bound.values()).containsExactly(2, 1, 2);
    }

    @Test
    @DisplayName("Внутри строкового литерала & не трогается")
    void ignores_ampersand_inside_literals() {
        var bound = bind("SELECT 'R&D' AS dept, \"a&b\" FROM t WHERE x = &P", Map.of("P", 7));

        assertThat(bound.sql()).isEqualTo("SELECT 'R&D' AS dept, \"a&b\" FROM t WHERE x = ?");
        assertThat(bound.values()).containsExactly(7);
    }

    @Test
    @DisplayName("Неизвестный параметр связывается как NULL и сообщается вызывающему")
    void unresolved_parameter_is_reported() {
        var reported = new java.util.ArrayList<String>();
        var bound = ParameterBinder.bind("SELECT * FROM t WHERE x = &Missing",
                name -> null, reported::add);

        assertThat(bound.sql()).endsWith("= ?");
        assertThat(bound.values()).containsExactly((Object) null);
        assertThat(reported).containsExactly("Missing");
    }

    @Test
    @DisplayName("Имя параметра может быть кириллическим")
    void cyrillic_parameter_names() {
        var bound = bind("SELECT * FROM t WHERE d >= &НачалоПериода",
                Map.of("НачалоПериода", "2026-01-01"));

        assertThat(bound.sql()).isEqualTo("SELECT * FROM t WHERE d >= ?");
        assertThat(bound.values()).containsExactly("2026-01-01");
    }

    @Test
    @DisplayName("Одинокий & без имени остаётся символом")
    void bare_ampersand_is_left_alone() {
        var bound = bind("SELECT a & b FROM t", Map.of());

        assertThat(bound.sql()).isEqualTo("SELECT a & b FROM t");
        assertThat(bound.values()).isEmpty();
    }

    @Test
    @DisplayName("Имена параметров перечисляются в порядке появления, без повторов")
    void lists_names_in_order() {
        assertThat(ParameterBinder.namesIn("SELECT &B, &A, &B FROM t"))
                .containsExactly("B", "A");
    }
}
