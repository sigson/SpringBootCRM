package app.springbootcrm.reference;

import app.springbootcrm.reference.CodeGenerator;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit-тест на статический форматтер CodeGenerator.formatCode.
 * Покрывает примеры из требований заказчика п.5 и крайние случаи.
 */
class CodeGeneratorFormatTest {

    @Test
    void with_prefix_pads_to_total_width() {
        // "CUS" (3) + 6 digits = 9 chars total
        assertThat(CodeGenerator.formatCode("CUS", 9, 1L)).isEqualTo("CUS000001");
        assertThat(CodeGenerator.formatCode("CUS", 9, 42L)).isEqualTo("CUS000042");
        assertThat(CodeGenerator.formatCode("CUS", 9, 999_999L)).isEqualTo("CUS999999");
    }

    @Test
    void without_prefix_uses_all_width_for_digits() {
        // No prefix → 9 digits
        assertThat(CodeGenerator.formatCode("", 9, 1L)).isEqualTo("000000001");
        assertThat(CodeGenerator.formatCode(null, 9, 1L)).isEqualTo("000000001");
    }

    @Test
    void zero_width_means_no_padding() {
        assertThat(CodeGenerator.formatCode("PR", 0, 42L)).isEqualTo("PR42");
        assertThat(CodeGenerator.formatCode("", 0, 42L)).isEqualTo("42");
    }

    @Test
    void value_exceeding_width_overflows_gracefully() {
        // 7-digit value when width allows 6 digits — оставляем как есть (не обрезаем)
        assertThat(CodeGenerator.formatCode("CUS", 9, 1_234_567L)).contains("1234567");
    }

    @Test
    void large_width_pads_long_zeros() {
        assertThat(CodeGenerator.formatCode("", 15, 1L)).isEqualTo("000000000000001");
    }

    @Test
    void code_width_100_yields_code_of_length_100() {
        // Нет вшитого потолка по длине: codeWidth=100 → код длиной ровно 100.
        String code = CodeGenerator.formatCode("", 100, 42L);
        assertThat(code).hasSize(100);
        assertThat(code).endsWith("42");
        assertThat(code.chars().allMatch(Character::isDigit)).isTrue();
    }

    @Test
    void ceiling_is_driven_purely_by_digits_not_a_constant() {
        // 10^digits - 1 — без жёсткой константы-потолка 9_999_999.
        assertThat(CodeGenerator.ceilingForDigits(1)).isEqualTo(9L);
        assertThat(CodeGenerator.ceilingForDigits(6)).isEqualTo(999_999L);
        assertThat(CodeGenerator.ceilingForDigits(7)).isEqualTo(9_999_999L);
        // Раньше потолок «зажимался» к 9_999_999 константой — теперь растёт по digits.
        assertThat(CodeGenerator.ceilingForDigits(8)).isEqualTo(99_999_999L);
        assertThat(CodeGenerator.ceilingForDigits(9)).isEqualTo(999_999_999L);
        assertThat(CodeGenerator.ceilingForDigits(18)).isEqualTo(999_999_999_999_999_999L);
    }

    @Test
    void ceiling_saturates_at_long_max_for_huge_widths() {
        // digits >= 19: 10^digits выходит за long — насыщаем до Long.MAX_VALUE
        // (раньше тип-счётчик исчерпается, чем потолок).
        assertThat(CodeGenerator.ceilingForDigits(19)).isEqualTo(Long.MAX_VALUE);
        assertThat(CodeGenerator.ceilingForDigits(100)).isEqualTo(Long.MAX_VALUE);
    }
}
