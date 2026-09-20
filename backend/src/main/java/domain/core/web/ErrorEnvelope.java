package domain.core.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import domain.core.access.PermissionRequirement;

import java.util.List;

/**
 * Унифицированный JSON-shape всех error-ответов сервера.
 *
 * <p>У клиента одна точка обработки: все поля, кроме {@code kind}, {@code status}
 * и {@code message}, опциональны. В зависимости от {@code kind}:
 * <ul>
 *   <li>{@code ACCESS_DENIED} → заполнен {@code requirements};</li>
 *   <li>{@code VALIDATION} → заполнен {@code fieldErrors};</li>
 *   <li>{@code NOT_FOUND} / {@code CONFLICT} / {@code BAD_REQUEST} / {@code INTERNAL} →
 *       только {@code message} (+ опционально {@code path}).</li>
 * </ul>
 *
 * <p>Все null-поля исключаются при сериализации.
 *
 * @param kind            категория ошибки (для роутинга UI)
 * @param status          HTTP-код
 * @param message         основное сообщение для показа
 * @param path            URI запроса (для диагностики)
 * @param requirements    только для {@code ACCESS_DENIED}: каких прав не хватило
 * @param fieldErrors     только для {@code VALIDATION}: ошибки по полям
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorEnvelope(
        Kind kind,
        int status,
        String message,
        String path,
        List<PermissionRequirement> requirements,
        List<ValidationFailedException.FieldError> fieldErrors
) {

    public enum Kind {
        /** 403 — нет прав на действие; детали в {@code requirements}. */
        ACCESS_DENIED,
        /** 400 — валидация входных данных не прошла; детали в {@code fieldErrors}. */
        VALIDATION,
        /** 404 — ресурс не найден. */
        NOT_FOUND,
        /** 409 — конфликт (уникальность, optimistic lock). */
        CONFLICT,
        /** 400 — bad request, не связанный с валидацией полей. */
        BAD_REQUEST,
        /** 401 — не аутентифицирован. */
        UNAUTHORIZED,
        /** 500 — необработанная ошибка сервера. */
        INTERNAL
    }

    /** Фабрика ACCESS_DENIED. */
    public static ErrorEnvelope accessDenied(String message, String path,
                                             List<PermissionRequirement> requirements) {
        return new ErrorEnvelope(Kind.ACCESS_DENIED, 403, message, path,
                requirements == null || requirements.isEmpty() ? null : requirements,
                null);
    }

    /** Фабрика VALIDATION. */
    public static ErrorEnvelope validation(String message, String path,
                                           List<ValidationFailedException.FieldError> errors) {
        return new ErrorEnvelope(Kind.VALIDATION, 400, message, path,
                null,
                errors == null || errors.isEmpty() ? null : errors);
    }

    /** Фабрика NOT_FOUND. */
    public static ErrorEnvelope notFound(String message, String path) {
        return new ErrorEnvelope(Kind.NOT_FOUND, 404, message, path, null, null);
    }

    /** Фабрика CONFLICT. */
    public static ErrorEnvelope conflict(String message, String path) {
        return new ErrorEnvelope(Kind.CONFLICT, 409, message, path, null, null);
    }

    /** Фабрика BAD_REQUEST (без field-level деталей). */
    public static ErrorEnvelope badRequest(String message, String path) {
        return new ErrorEnvelope(Kind.BAD_REQUEST, 400, message, path, null, null);
    }

    /** Фабрика UNAUTHORIZED. */
    public static ErrorEnvelope unauthorized(String message, String path) {
        return new ErrorEnvelope(Kind.UNAUTHORIZED, 401, message, path, null, null);
    }

    /** Фабрика INTERNAL. */
    public static ErrorEnvelope internal(String message, String path) {
        return new ErrorEnvelope(Kind.INTERNAL, 500, message, path, null, null);
    }
}
