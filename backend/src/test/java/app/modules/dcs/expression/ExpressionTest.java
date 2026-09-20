package app.modules.dcs.expression;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Язык выражений компоновки: разбор и вычисление.
 *
 * <p>Проверяются те свойства, на которых держится корректность итогов: агрегаты идут по
 * множеству записей области, деление на ноль даёт пустоту, а не исключение, и итог
 * произвольного выражения не равен сумме частных.
 */
class ExpressionTest {

    /** Контекст с фиксированным набором записей — им и «кормятся» агрегаты. */
    private record Ctx(List<Map<String, Object>> rows, Map<String, Object> params,
                       int level, int recordNumber) implements EvalContext {
        @Override public Object field(String path) {
            return rows.isEmpty() ? null : rows.get(0).get(path);
        }
        @Override public Object parameter(String name) { return params.get(name); }
        @Override public List<Map<String, Object>> rows() { return rows; }
        @Override public Object evaluateInScope(String expression, String grouping, String area) {
            // Для теста «другая область» — это весь набор записей.
            return new ExprEvaluator(this).eval(ExprParser.parse(expression));
        }
    }

    private static Object eval(String expression, List<Map<String, Object>> rows) {
        return new ExprEvaluator(new Ctx(rows, Map.of(), 0, 0)).eval(ExprParser.parse(expression));
    }

    private static Object eval(String expression) {
        return eval(expression, List.of(Map.of()));
    }

    private static List<Map<String, Object>> sales() {
        return List.of(
                Map.of("amount", new BigDecimal("100"), "qty", new BigDecimal("2"), "client", "A"),
                Map.of("amount", new BigDecimal("50"), "qty", new BigDecimal("5"), "client", "B"),
                Map.of("amount", new BigDecimal("25"), "qty", new BigDecimal("1"), "client", "A"));
    }

    @Test
    @DisplayName("Арифметика считается в BigDecimal, приоритеты соблюдаются")
    void arithmetic() {
        assertThat(eval("2 + 3 * 4")).isEqualTo(new BigDecimal("14"));
        assertThat(eval("(2 + 3) * 4")).isEqualTo(new BigDecimal("20"));
        assertThat(eval("-5 + 2")).isEqualTo(new BigDecimal("-3"));
    }

    @Test
    @DisplayName("Деление на ноль даёт пустое значение, а не падение")
    void division_by_zero_is_empty() {
        assertThat(eval("10 / 0")).isNull();
    }

    @Test
    @DisplayName("Агрегаты считаются по всем записям области")
    void aggregates_over_rows() {
        List<Map<String, Object>> rows = sales();
        assertThat(eval("Сумма(amount)", rows)).isEqualTo(new BigDecimal("175"));
        assertThat(eval("Количество(amount)", rows)).isEqualTo(BigDecimal.valueOf(3));
        assertThat(eval("КоличествоРазличных(client)", rows)).isEqualTo(BigDecimal.valueOf(2));
        assertThat(eval("Минимум(amount)", rows)).isEqualTo(new BigDecimal("25"));
        assertThat(eval("Максимум(amount)", rows)).isEqualTo(new BigDecimal("100"));
    }

    @Test
    @DisplayName("Английские имена функций работают наравне с русскими")
    void english_function_names() {
        assertThat(eval("Sum(amount)", sales())).isEqualTo(new BigDecimal("175"));
        assertThat(eval("COUNT(amount)", sales())).isEqualTo(BigDecimal.valueOf(3));
    }

    @Test
    @DisplayName("Агрегируется выражение, а не только поле")
    void aggregate_of_expression() {
        // 100*2 + 50*5 + 25*1 = 475 — это и проверяет, что каждая запись подставляется
        // как текущая строка, а не берётся первая.
        assertThat(eval("Сумма(amount * qty)", sales())).isEqualTo(new BigDecimal("475"));
    }

    @Test
    @DisplayName("Итог произвольного ресурса — не сумма частных")
    void ratio_resource_is_computed_on_the_whole_set() {
        // 175 / 8, а не среднее из 50, 10 и 25.
        Object value = eval("Сумма(amount) / Сумма(qty)", sales());
        assertThat(new BigDecimal(String.valueOf(value))).isEqualByComparingTo("21.875");
    }

    @Test
    @DisplayName("ВЫБОР КОГДА … ТОГДА … ИНАЧЕ … КОНЕЦ")
    void case_expression() {
        List<Map<String, Object>> row = List.of(Map.of("amount", new BigDecimal("100")));
        assertThat(eval("ВЫБОР КОГДА amount > 50 ТОГДА \"big\" ИНАЧЕ \"small\" КОНЕЦ", row))
                .isEqualTo("big");
        assertThat(eval("CASE WHEN amount > 500 THEN 'big' ELSE 'small' END", row))
                .isEqualTo("small");
    }

    @Test
    @DisplayName("Сравнения, И/ИЛИ/НЕ, В списке, МЕЖДУ, ЕСТЬ NULL")
    void predicates() {
        List<Map<String, Object>> row = List.of(Map.of("n", new BigDecimal("5"), "s", "abc"));
        assertThat(eval("n > 3 И n < 10", row)).isEqualTo(true);
        assertThat(eval("n > 30 ИЛИ n = 5", row)).isEqualTo(true);
        assertThat(eval("НЕ (n = 5)", row)).isEqualTo(false);
        assertThat(eval("n В (1, 3, 5)", row)).isEqualTo(true);
        assertThat(eval("n МЕЖДУ 1 И 10", row)).isEqualTo(true);
        assertThat(eval("s ПОДОБНО \"a%\"", row)).isEqualTo(true);
    }

    @Test
    @DisplayName("Пустое слагаемое не обнуляет сумму")
    void null_operand_behaves_as_zero_in_addition() {
        List<Map<String, Object>> row = List.of(new java.util.HashMap<>(Map.of("a", new BigDecimal("5"))));
        assertThat(eval("a + b", row)).isEqualTo(new BigDecimal("5"));
    }

    @Test
    @DisplayName("Строковые функции и функции даты")
    void scalar_functions() {
        List<Map<String, Object>> row = List.of(Map.of("s", "  hello  ", "d", "2026-03-15"));
        assertThat(eval("СокрЛП(s)", row)).isEqualTo("hello");
        assertThat(eval("ВРег(СокрЛП(s))", row)).isEqualTo("HELLO");
        assertThat(eval("Подстрока(СокрЛП(s), 1, 2)", row)).isEqualTo("he");
        assertThat(eval("Год(d)", row)).isEqualTo(BigDecimal.valueOf(2026));
        assertThat(eval("Квартал(d)", row)).isEqualTo(BigDecimal.valueOf(1));
        assertThat(eval("ЕстьNULL(missing, 7)", row)).isEqualTo(new BigDecimal("7"));
    }

    @Test
    @DisplayName("Параметр схемы подставляется по имени")
    void parameter_substitution() {
        EvalContext ctx = new Ctx(List.of(Map.of("amount", new BigDecimal("100"))),
                Map.of("Min", new BigDecimal("50")), 0, 0);
        Object value = new ExprEvaluator(ctx).eval(ExprParser.parse("amount > &Min"));
        assertThat(value).isEqualTo(true);
    }

    @Test
    @DisplayName("Неизвестная функция — явная ошибка, а не тихий null")
    void unknown_function_fails_loudly() {
        assertThatThrownBy(() -> eval("НетТакойФункции(1)"))
                .isInstanceOf(ExprEvaluator.EvalException.class)
                .hasMessageContaining("НетТакойФункции");
    }

    @Test
    @DisplayName("Синтаксическая ошибка сообщает позицию")
    void syntax_error_reports_position() {
        assertThatThrownBy(() -> ExprParser.parse("1 + "))
                .isInstanceOf(ExprParser.ParseException.class)
                .hasMessageContaining("position");
    }

    @Test
    @DisplayName("parseQuietly возвращает null вместо исключения")
    void parse_quietly() {
        assertThat(ExprParser.parseQuietly("1 +")).isNull();
        assertThat(ExprParser.parseQuietly("")).isNull();
        assertThat(ExprParser.parseQuietly("1 + 1")).isNotNull();
    }

    @Test
    @DisplayName("Простой агрегат распознаётся — от этого зависит проталкивание GROUP BY")
    void aggregate_detection() {
        assertThat(ExprEvaluator.isAggregateFunction("Сумма")).isTrue();
        assertThat(ExprEvaluator.isAggregateFunction("SUM")).isTrue();
        assertThat(ExprEvaluator.isAggregateFunction("Представление")).isFalse();
    }
}
