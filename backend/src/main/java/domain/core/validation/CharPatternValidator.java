package domain.core.validation;

import org.springframework.stereotype.Component;

import java.util.Map;

/** Проверка соответствия значения Java-регулярке целиком ({@code matches}). */
public interface CharPatternValidator extends FieldValidator<CharPattern> {

    @Override
    default ValidationResult validate(CharPattern c, String value) {
        return value.matches(c.regex()) ? ValidationResult.ok() : ValidationResult.fail(c.message());
    }

    @Override
    default Map<String, Object> meta(CharPattern c) {
        return Map.of("regex", c.regex());
    }
}

@Component
class CharPatternValidatorBean implements CharPatternValidator {}
