package domain.core.access;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Запись о роли в {@link AccessMetricPayload#roleFlags()}: (plain flags + WriteMode).
 *
 * <p>Roles даются по типу пользователя (например, "admin", "support"), флаги — что эта роль
 * разрешает делать. {@link WriteMode} описывает строгость (например, INIT_ONCE для роли,
 * которая только устанавливает поля при онбординге).
 */
public record RoleEntry(int flags, WriteMode mode) {

    @JsonCreator
    public RoleEntry(@JsonProperty("flags") int flags,
                     @JsonProperty("mode")  WriteMode mode) {
        this.flags = flags;
        this.mode  = (mode == null) ? WriteMode.ANY : mode;
    }
}
