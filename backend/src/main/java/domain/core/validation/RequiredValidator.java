package domain.core.validation;

import org.springframework.stereotype.Component;

import java.util.Map;

/** Проверка обязательности. Единственная, что реагирует на пустое значение. */
public interface RequiredValidator extends FieldValidator<Required> {

    @Override
    default ValidationResult validate(Required c, String value) {
        return value.isEmpty() ? ValidationResult.fail(c.message()) : ValidationResult.ok();
    }

    @Override
    default boolean appliesToEmpty() {
        return true;
    }

    @Override
    default Map<String, Object> meta(Required c) {
        return Map.of("required", true);
    }
}

/** Экземпляр для обнаружения Spring'ом (вся логика — в default-методах интерфейса). */
@Component
class RequiredValidatorBean implements RequiredValidator {}
