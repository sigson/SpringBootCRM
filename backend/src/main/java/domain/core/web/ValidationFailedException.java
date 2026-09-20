package domain.core.web;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Универсальное валидационное исключение для всех доменов: один тип, один глобальный
 * handler ({@code ErrorEnvelopeAdvice}), один shape JSON-ответа — у фронтенда единая
 * точка показа ошибок валидации.
 *
 * <p>Два режима:
 * <ul>
 *   <li><b>Single-field</b>: {@link #ofField(String, String)} — одна ошибка на поле;</li>
 *   <li><b>Multi-field</b>: {@link #of(List)} — батч ошибок (например, из Jakarta
 *       {@code MethodArgumentNotValidException} или кастомного валидатора).</li>
 * </ul>
 *
 * <p>Наследует {@link RuntimeException} — не требует {@code throws}.
 */
public class ValidationFailedException extends RuntimeException {

    private final List<FieldError> errors;

    private ValidationFailedException(String message, List<FieldError> errors) {
        super(message);
        this.errors = Collections.unmodifiableList(List.copyOf(errors));
    }

    /** Одна ошибка одного поля. */
    public static ValidationFailedException ofField(String field, String message) {
        List<FieldError> list = new ArrayList<>(1);
        list.add(new FieldError(field, message));
        return new ValidationFailedException(message, list);
    }

    /** Ошибка без привязки к полю (например, перекрёстная валидация двух полей). */
    public static ValidationFailedException general(String message) {
        List<FieldError> list = new ArrayList<>(1);
        list.add(new FieldError(null, message));
        return new ValidationFailedException(message, list);
    }

    /**
     * Батч ошибок. Top-level message берётся из первой ошибки.
     */
    public static ValidationFailedException of(List<FieldError> errors) {
        if (errors == null || errors.isEmpty()) {
            throw new IllegalArgumentException("ValidationFailedException requires at least one error");
        }
        String summary = errors.get(0).message();
        return new ValidationFailedException(summary, errors);
    }

    public List<FieldError> errors() {
        return errors;
    }

    /**
     * Одна field-ошибка. {@code field} может быть {@code null} для cross-field
     * или form-wide ошибок.
     */
    public record FieldError(String field, String message) {}
}
