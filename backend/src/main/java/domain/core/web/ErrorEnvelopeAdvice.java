package domain.core.web;

import app.springbootcrm.metadata.TypeRegistry;
import app.springbootcrm.metadata.TypeRegistryTypeLabelResolver;

import com.fasterxml.jackson.databind.JsonMappingException;
import domain.core.access.PermissionRequirement;
import domain.core.access.StructuredAccessDeniedException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * Единая центральная точка обработки исключений: каждое транслируется в
 * {@link ErrorEnvelope} с конкретным {@link ErrorEnvelope.Kind}, так что у фронтенда
 * одна точка парсинга. {@link StructuredAccessDeniedException} переносит список
 * {@link PermissionRequirement} (видно, каких прав не хватило), {@link ValidationFailedException}
 * и Jakarta {@code @Valid}-ошибки ({@link MethodArgumentNotValidException}) —
 * {@code fieldErrors} в общий {@link ErrorEnvelope.Kind#VALIDATION}-envelope.
 *
 * <p>{@code @Order(HIGHEST_PRECEDENCE)} — чтобы перехватить {@code MethodArgumentNotValidException}
 * раньше дефолтного Spring-handler'а.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ErrorEnvelopeAdvice {

    private static final Logger log = LoggerFactory.getLogger(ErrorEnvelopeAdvice.class);

    private final TypeLabelResolver typeLabels;

    /**
     * @param typeLabels опциональный bean — если в контексте есть реализация
     *                   (обычно {@code TypeRegistryTypeLabelResolver}), ошибки доступа
     *                   будут содержать human-readable {@code typeLabel}; иначе null.
     */
    public ErrorEnvelopeAdvice(org.springframework.beans.factory.ObjectProvider<TypeLabelResolver> typeLabels) {
        this.typeLabels = typeLabels.getIfAvailable(() -> typeId -> null);
    }

    // ============== 403 — Access Denied ==============

    @ExceptionHandler(StructuredAccessDeniedException.class)
    public ResponseEntity<ErrorEnvelope> handleStructured(
            StructuredAccessDeniedException e, HttpServletRequest req) {
        log.warn("AccessDenied at {} {}: {} (requirements={})",
                req.getMethod(), req.getRequestURI(), e.getMessage(), e.requirements());
        // Обогащаем requirements полем typeLabel (заполняется в REST-слое, чтобы
        // listener'ы в domain.core не зависели от TypeRegistry).
        List<PermissionRequirement> enriched = new ArrayList<>(e.requirements().size());
        for (PermissionRequirement req0 : e.requirements()) {
            if (req0.typeId() != null && (req0.typeLabel() == null || req0.typeLabel().isBlank())) {
                String label = typeLabels.labelFor(req0.typeId());
                enriched.add(label == null ? req0 : req0.withTypeLabel(label));
            } else {
                enriched.add(req0);
            }
        }
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ErrorEnvelope.accessDenied(
                        safeMsg(e, "Доступ заборонено"),
                        req.getRequestURI(),
                        enriched));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorEnvelope> handleAccessDenied(
            AccessDeniedException e, HttpServletRequest req) {
        // Untyped — без структурированных requirements: для кода, бросающего
        // plain AccessDeniedException вместо StructuredAccessDeniedException.
        log.warn("AccessDenied (untyped) at {} {}: {}",
                req.getMethod(), req.getRequestURI(), e.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ErrorEnvelope.accessDenied(
                        safeMsg(e, "Доступ заборонено"),
                        req.getRequestURI(),
                        null));
    }

    // ============== 400 — Validation ==============

    @ExceptionHandler(ValidationFailedException.class)
    public ResponseEntity<ErrorEnvelope> handleValidation(
            ValidationFailedException e, HttpServletRequest req) {
        log.debug("ValidationFailed at {} {}: {} ({} field-errors)",
                req.getMethod(), req.getRequestURI(), e.getMessage(), e.errors().size());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorEnvelope.validation(
                        safeMsg(e, "Помилка валідації"),
                        req.getRequestURI(),
                        e.errors()));
    }

    /** Jakarta @Valid → тот же VALIDATION-envelope. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorEnvelope> handleBeanValidation(
            MethodArgumentNotValidException e, HttpServletRequest req) {
        List<ValidationFailedException.FieldError> errors = new ArrayList<>();
        e.getBindingResult().getFieldErrors().forEach(fe ->
                errors.add(new ValidationFailedException.FieldError(
                        fe.getField(),
                        fe.getDefaultMessage() == null ? "Неприпустиме значення" : fe.getDefaultMessage())));
        e.getBindingResult().getGlobalErrors().forEach(ge ->
                errors.add(new ValidationFailedException.FieldError(
                        null,
                        ge.getDefaultMessage() == null ? "Неприпустиме значення" : ge.getDefaultMessage())));
        String summary = errors.isEmpty() ? "Помилка валідації" : errors.get(0).message();
        log.debug("BeanValidation at {} {}: {} ({} errors)",
                req.getMethod(), req.getRequestURI(), summary, errors.size());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorEnvelope.validation(summary, req.getRequestURI(), errors));
    }

    /** Method-level @Valid на параметрах (path/query). */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorEnvelope> handleConstraintViolations(
            ConstraintViolationException e, HttpServletRequest req) {
        List<ValidationFailedException.FieldError> errors = new ArrayList<>();
        for (ConstraintViolation<?> v : e.getConstraintViolations()) {
            String path = v.getPropertyPath() == null ? null : v.getPropertyPath().toString();
            errors.add(new ValidationFailedException.FieldError(path, v.getMessage()));
        }
        String summary = errors.isEmpty() ? "Помилка валідації" : errors.get(0).message();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorEnvelope.validation(summary, req.getRequestURI(), errors));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorEnvelope> handleBadInput(
            IllegalArgumentException e, HttpServletRequest req) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorEnvelope.badRequest(safeMsg(e, "Невірний запит"), req.getRequestURI()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorEnvelope> handleUnreadable(
            HttpMessageNotReadableException e, HttpServletRequest req) {
        // Чаще всего — невалидный JSON. Пытаемся извлечь имя поля из причины.
        String msg = "Невірний формат запиту";
        if (e.getCause() instanceof JsonMappingException jm && !jm.getPath().isEmpty()) {
            String fld = jm.getPath().get(jm.getPath().size() - 1).getFieldName();
            if (fld != null) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(ErrorEnvelope.validation(
                                "Поле «" + fld + "» має невірний формат",
                                req.getRequestURI(),
                                List.of(new ValidationFailedException.FieldError(fld,
                                        "Невірний формат значення"))));
            }
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorEnvelope.badRequest(msg, req.getRequestURI()));
    }

    // ============== 404 / 409 ==============

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<ErrorEnvelope> handleNotFound(
            NoSuchElementException e, HttpServletRequest req) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorEnvelope.notFound(safeMsg(e, "Не знайдено"), req.getRequestURI()));
    }

    /**
     * Неизвестный маршрут. Без этого обработчика запрос на несуществующий URL доходил
     * до {@link #handleAny} и превращался в 500 со стеком в логе: Spring бросает здесь
     * {@link NoResourceFoundException} (статика не нашлась), а она — обычное
     * {@code Exception}. Клиент при этом не мог отличить «сервер сломался» от «такого
     * эндпоинта нет» — а разница существенная: по ней, в частности, фронтенд понимает,
     * что опциональный модуль выключен.
     *
     * <p>Тело ответа намеренно не пересказывает сообщение Spring'а («No static resource
     * …»): оно говорит о внутреннем механизме, а не о запросе клиента.
     */
    @ExceptionHandler({ NoResourceFoundException.class, NoHandlerFoundException.class })
    public ResponseEntity<ErrorEnvelope> handleUnknownRoute(Exception e, HttpServletRequest req) {
        log.debug("No handler for {} {}", req.getMethod(), req.getRequestURI());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorEnvelope.notFound(
                        "Endpoint not found: " + req.getMethod() + " " + req.getRequestURI(),
                        req.getRequestURI()));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorEnvelope> handleDataIntegrity(
            DataIntegrityViolationException e, HttpServletRequest req) {
        log.warn("DataIntegrityViolation at {} {}: {}",
                req.getMethod(), req.getRequestURI(), e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorEnvelope.conflict(
                        "Конфлікт даних: можливо, запис з такими унікальними полями вже існує",
                        req.getRequestURI()));
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ErrorEnvelope> handleOptimistic(
            ObjectOptimisticLockingFailureException e, HttpServletRequest req) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorEnvelope.conflict(
                        "Запис було змінено іншим користувачем. Оновіть сторінку та повторіть.",
                        req.getRequestURI()));
    }

    // ============== Transactional unwrapping ==============

    /**
     * TX commit-time failures: Hibernate-listener'ы (PreInsert/PreUpdate/PreDelete)
     * могут бросить AccessDenied уже на flush'е, происходящем при commit'е.
     * Spring оборачивает его в {@link TransactionSystemException} — разворачиваем.
     */
    @ExceptionHandler(TransactionSystemException.class)
    public ResponseEntity<ErrorEnvelope> handleTxFailure(
            TransactionSystemException e, HttpServletRequest req) {
        Throwable root = e.getMostSpecificCause();
        if (root instanceof StructuredAccessDeniedException sade) {
            return handleStructured(sade, req);
        }
        if (root instanceof AccessDeniedException ade) {
            return handleAccessDenied(ade, req);
        }
        if (root instanceof ValidationFailedException vfe) {
            return handleValidation(vfe, req);
        }
        log.error("Transaction commit failed at {} {}: {}",
                req.getMethod(), req.getRequestURI(),
                root == null ? e.getMessage() : root.getMessage(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorEnvelope.internal(
                        root == null ? e.getMessage() : root.getMessage(),
                        req.getRequestURI()));
    }

    // ============== 500 — fallback ==============

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorEnvelope> handleAny(Exception e, HttpServletRequest req) {
        log.error("Unhandled exception at {} {}: {}",
                req.getMethod(), req.getRequestURI(), e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorEnvelope.internal(
                        safeMsg(e, e.getClass().getSimpleName()),
                        req.getRequestURI()));
    }

    // ============== utils ==============

    private static String safeMsg(Throwable t, String fallback) {
        String m = t.getMessage();
        return m == null || m.isBlank() ? fallback : m;
    }
}
