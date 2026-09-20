package domain.core.validation;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/** Проверка формата e-mail. */
public interface EmailFormatValidator extends FieldValidator<EmailFormat> {

    /** Поля интерфейса неявно {@code public static final} — компилируется один раз. */
    Pattern EMAIL_RE = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    @Override
    default ValidationResult validate(EmailFormat c, String value) {
        return EMAIL_RE.matcher(value).matches() ? ValidationResult.ok() : ValidationResult.fail(c.message());
    }
}

@Component
class EmailFormatValidatorBean implements EmailFormatValidator {}
