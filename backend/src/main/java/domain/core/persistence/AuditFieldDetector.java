package domain.core.persistence;

import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;

import java.lang.reflect.Field;

/**
 * Detects fields managed by Spring Data JPA Auditing — those bearing
 * {@link CreatedBy}, {@link CreatedDate}, {@link LastModifiedBy}, or
 * {@link LastModifiedDate}.
 *
 * <p>Why: the {@code AuditingEntityListener} writes these fields on every
 * {@code @PrePersist} / {@code @PreUpdate} JPA callback. By the time Hibernate
 * fires its own PRE_UPDATE / PRE_INSERT events, the new state already reflects
 * the audit listener's writes. Without an explicit skip, the access listeners
 * would interpret those writes as the current user trying to mutate
 * {@code READ_ONLY} fields and throw {@code AccessDeniedException} — making it
 * impossible for any non-system user to update or insert anything.
 *
 * <p>Used by {@code AccessAwarePreUpdateListener} and
 * {@code AccessAwarePreInsertListener} to skip these fields. The audit
 * framework remains the sole authority over them.
 */
public final class AuditFieldDetector {

    private AuditFieldDetector() {}

    public static boolean isAuditManaged(Field field) {
        if (field == null) return false;
        return field.isAnnotationPresent(CreatedBy.class)
                || field.isAnnotationPresent(CreatedDate.class)
                || field.isAnnotationPresent(LastModifiedBy.class)
                || field.isAnnotationPresent(LastModifiedDate.class);
    }
}
