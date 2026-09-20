package domain.core.validation;

/**
 * Результат проверки одного ограничения: валидно ли значение и (если нет)
 * текст ошибки для показа рядом с полем.
 *
 * <p>Возвращается базовым методом {@link FieldValidator#validate}.
 */
public record ValidationResult(boolean valid, String message) {

    private static final ValidationResult OK = new ValidationResult(true, null);

    /** Значение прошло проверку. */
    public static ValidationResult ok() {
        return OK;
    }

    /** Значение не прошло проверку; {@code message} — текст для пользователя. */
    public static ValidationResult fail(String message) {
        return new ValidationResult(false, message);
    }
}
