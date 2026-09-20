package domain.core.validation;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** Проверка длины строки в диапазоне [min, max]. */
public interface TextLengthValidator extends FieldValidator<TextLength> {

    @Override
    default ValidationResult validate(TextLength c, String value) {
        int len = value.length();
        if (len < c.min() || len > c.max()) {
            return ValidationResult.fail(
                    c.message().isEmpty() ? autoMessage(c.min(), c.max()) : c.message());
        }
        return ValidationResult.ok();
    }

    @Override
    default Map<String, Object> meta(TextLength c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("min", c.min());
        m.put("max", c.max());
        return m;
    }

    private static String autoMessage(int min, int max) {
        if (min > 0 && max < Integer.MAX_VALUE) return "Довжина має бути від " + min + " до " + max + " символів";
        if (max < Integer.MAX_VALUE) return "Не більше " + max + " символів";
        return "Не менше " + min + " символів";
    }
}

@Component
class TextLengthValidatorBean implements TextLengthValidator {}
