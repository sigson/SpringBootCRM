package domain.core.access;

import app.springbootcrm.auth.AdminCheck;

import org.springframework.security.access.AccessDeniedException;

import java.util.Collections;
import java.util.List;

/**
 * Subclass {@link AccessDeniedException} с машиночитаемым описанием «каких прав
 * не хватило».
 *
 * <p>Все точки отказа в доступе (Hibernate-listener'ы {@code AccessAwarePre*Listener},
 * {@code AdminCheck.requireAdmin}, ручные проверки в сервисах) бросают именно это
 * исключение; глобальный {@code ErrorEnvelopeAdvice} ловит его, переносит
 * {@link #requirements()} в JSON-envelope и отдаёт клиенту 403 с полным описанием.
 *
 * <p>Plain {@link AccessDeniedException} (например, из сторонней библиотеки)
 * {@code ErrorEnvelopeAdvice} обрабатывает как untyped — envelope без структурированных
 * {@code requirements}, но с исходным {@code message}.
 */
public class StructuredAccessDeniedException extends AccessDeniedException {

    private final List<PermissionRequirement> requirements;

    public StructuredAccessDeniedException(String message, List<PermissionRequirement> requirements) {
        super(message);
        this.requirements = requirements == null
                ? List.of()
                : Collections.unmodifiableList(List.copyOf(requirements));
    }

    public StructuredAccessDeniedException(String message, PermissionRequirement requirement) {
        this(message, requirement == null ? List.of() : List.of(requirement));
    }

    /** Детали: какого бита/typeId/field-name не хватает. Иммутабельный список. */
    public List<PermissionRequirement> requirements() {
        return requirements;
    }
}
