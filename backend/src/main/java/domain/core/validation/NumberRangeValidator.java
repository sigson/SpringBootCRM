package domain.core.validation;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** Проверка числа в диапазоне с учётом включения/исключения границ. */
public interface NumberRangeValidator extends FieldValidator<NumberRange> {

    @Override
    default ValidationResult validate(NumberRange c, String value) {
        Double n = parseNumber(value);
        if (n == null) return ValidationResult.fail("Введіть число");
        boolean okMin = c.minInclusive() ? n >= c.min() : n > c.min();
        boolean okMax = c.maxInclusive() ? n <= c.max() : n < c.max();
        if (!okMin || !okMax) {
            return ValidationResult.fail(c.message().isEmpty() ? autoMessage(c) : c.message());
        }
        return ValidationResult.ok();
    }

    @Override
    default Map<String, Object> meta(NumberRange c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("min", c.min());
        m.put("max", c.max());
        m.put("minInclusive", c.minInclusive());
        m.put("maxInclusive", c.maxInclusive());
        return m;
    }

    private static Double parseNumber(String s) {
        try { return Double.valueOf(s.replace(',', '.')); }
        catch (NumberFormatException e) { return null; }
    }

    private static String autoMessage(NumberRange c) {
        boolean hasMin = c.min() != Double.NEGATIVE_INFINITY;
        boolean hasMax = c.max() != Double.POSITIVE_INFINITY;
        String lo = trimNum(c.min());
        String hi = trimNum(c.max());
        String geq = c.minInclusive() ? "≥" : ">";
        String leq = c.maxInclusive() ? "≤" : "<";
        if (hasMin && hasMax) return "Значення має бути " + geq + " " + lo + " та " + leq + " " + hi;
        if (hasMin) return "Значення має бути " + geq + " " + lo;
        if (hasMax) return "Значення має бути " + leq + " " + hi;
        return "Невірне числове значення";
    }

    private static String trimNum(double d) {
        if (d == Math.rint(d) && !Double.isInfinite(d)) return String.valueOf((long) d);
        return String.valueOf(d);
    }
}

@Component
class NumberRangeValidatorBean implements NumberRangeValidator {}
