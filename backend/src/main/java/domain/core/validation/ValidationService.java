package domain.core.validation;

import domain.core.web.ValidationFailedException;
import domain.core.web.ValidationFailedException.FieldError;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Применяет ограничения из {@link FieldValidationRegistry}.
 *
 * <p>Три режима — все поверх одного набора ограничений (один источник правды):
 * <ul>
 *   <li>{@link #validateField} — интерактивная проверка одного реквизита
 *       (фронтенд дёргает по дебаунсу при вводе);</li>
 *   <li>{@link #validateObject} — полная проверка всех реквизитов объекта;</li>
 *   <li>{@link #assertValid} — то же, но бросает {@link ValidationFailedException}
 *       (для серверной проверки при сохранении).</li>
 * </ul>
 */
@Service
public class ValidationService {

    private final FieldValidationRegistry registry;

    public ValidationService(FieldValidationRegistry registry) {
        this.registry = registry;
    }

    /**
     * Проверяет одно поле. Возвращает первый текст ошибки (или valid) и {@code meta}
     * со всеми параметрами ограничений поля (min/max/maxLength/pattern…).
     */
    public FieldResult validateField(long typeId, String field, Object value) {
        List<FieldConstraint> constraints = registry.forField(typeId, field);
        Map<String, Object> meta = mergeMeta(constraints);
        for (FieldConstraint c : constraints) {
            String err = c.validate(value);
            if (err != null) return FieldResult.fail(field, err, meta);
        }
        return FieldResult.ok(field, meta);
    }

    /**
     * Полная проверка объекта по карте значений {@code field -> value}. Проверяет
     * все реквизиты типа, на которых есть ограничения (поля, отсутствующие в карте,
     * считаются пустыми — чтобы сработал {@link Required}).
     */
    public FieldResult.ObjectResult validateObject(long typeId, Map<String, Object> values) {
        Map<String, Object> safe = values == null ? Map.of() : values;
        List<FieldResult> results = new ArrayList<>();
        boolean allValid = true;
        for (Map.Entry<String, List<FieldConstraint>> e : registry.forType(typeId).entrySet()) {
            String field = e.getKey();
            Object value = safe.get(field);
            FieldResult r = validateAgainst(field, value, e.getValue());
            if (!r.valid()) allValid = false;
            results.add(r);
        }
        return new FieldResult.ObjectResult(allValid, results);
    }

    /**
     * Серверная проверка при сохранении: бросает {@link ValidationFailedException}
     * (kind=VALIDATION, fieldErrors) если хоть один реквизит невалиден. Полностью
     * заменяет россыпь ручных {@code requireXxx()} в сервисах.
     */
    public void assertValid(long typeId, Map<String, Object> values) {
        FieldResult.ObjectResult res = validateObject(typeId, values);
        if (res.valid()) return;
        List<FieldError> errors = new ArrayList<>();
        for (FieldResult r : res.results()) {
            if (!r.valid()) errors.add(new FieldError(r.field(), r.message()));
        }
        if (!errors.isEmpty()) throw ValidationFailedException.of(errors);
    }

    // ------------------------------------------------------------------

    private FieldResult validateAgainst(String field, Object value,
                                        List<FieldConstraint> constraints) {
        Map<String, Object> meta = mergeMeta(constraints);
        for (FieldConstraint c : constraints) {
            String err = c.validate(value);
            if (err != null) return FieldResult.fail(field, err, meta);
        }
        return FieldResult.ok(field, meta);
    }

    /** Сводит params всех ограничений поля в один map для подсказок фронтенда. */
    private static Map<String, Object> mergeMeta(List<FieldConstraint> constraints) {
        if (constraints.isEmpty()) return null;
        Map<String, Object> meta = new LinkedHashMap<>();
        for (FieldConstraint c : constraints) meta.putAll(c.meta());
        return meta.isEmpty() ? null : meta;
    }
}
