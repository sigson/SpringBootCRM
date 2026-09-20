package app.modules.sqlworkbench.web;

import app.modules.sqlworkbench.dto.Dtos.ApiError;
import app.modules.sqlworkbench.security.AccessDeniedWorkbenchException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.NoSuchElementException;

/**
 * <h2>Обработчик ошибок REST-API модуля «SQL Workbench».</h2>
 *
 * <p>Advice <u>ограничен</u> контроллерами модуля через
 * {@code basePackages = "app.modules.sqlworkbench.web"}, поэтому он не перехватывает
 * обработку ошибок всего хоста ({@code app.springbootcrm}) и центральная модалка ошибок
 * хоста работает без изменений.
 */
@RestControllerAdvice(basePackages = "app.modules.sqlworkbench.web")
public class SqlWorkbenchExceptionHandler {

    @ExceptionHandler(AccessDeniedWorkbenchException.class)
    public ResponseEntity<ApiError> denied(AccessDeniedWorkbenchException e, HttpServletRequest req) {
        return build(HttpStatus.FORBIDDEN, e.getMessage(), req);
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<ApiError> notFound(NoSuchElementException e, HttpServletRequest req) {
        return build(HttpStatus.NOT_FOUND, e.getMessage(), req);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> badRequest(IllegalArgumentException e, HttpServletRequest req) {
        return build(HttpStatus.BAD_REQUEST, e.getMessage(), req);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiError> conflict(IllegalStateException e, HttpServletRequest req) {
        return build(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage(), req);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> generic(Exception e, HttpServletRequest req) {
        return build(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage(), req);
    }

    private ResponseEntity<ApiError> build(HttpStatus status, String msg, HttpServletRequest req) {
        return ResponseEntity.status(status).body(
                new ApiError(status.value(), status.getReasonPhrase(), msg, req.getRequestURI()));
    }
}
