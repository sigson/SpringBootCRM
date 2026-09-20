package app.springbootcrm.user;

import app.springbootcrm.access.AccessRole;
import app.springbootcrm.interfaces.InterfaceLayout;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Проекция пользователя с двумя уровнями видимости.
 *
 * <p>{@code level = "full"} — запрашивающий видит запись целиком: это он сам, либо
 * администратор. {@code level = "basic"} — все остальные: остаются публичные реквизиты
 * (код, имя, логин, отображаемое имя, признак активности, роль), а персональные и
 * относящиеся к безопасности поля — email, флаги доступа, аудит-ссылки, назначенный
 * интерфейс — отдаются как {@code null}.
 *
 * <p>Маскирование делается здесь, а не access-aware сериализатором: тот работает по
 * полям сущности, а DTO собирается геттерами и проходит мимо него.
 */
public record UserDto(
        /** "full" — запись видна целиком; "basic" — персональные поля скрыты. */
        String level,
        UUID id,
        String code,
        String name,
        String username,
        String email,
        String displayName,
        boolean enabled,
        UUID roleId,
        long roleTypeId,
        Integer accessGlobalFlags,
        Map<String, Integer> accessTypeFlags,
        Instant createdAt,
        String createdBy,
        String updatedBy,
        UUID interfaceLayoutId,
        long interfaceLayoutTypeId
) {

    public static final String LEVEL_FULL = "full";
    public static final String LEVEL_BASIC = "basic";

    /** Полная проекция: для самого пользователя и для администратора. */
    public static UserDto of(User u) {
        return new UserDto(
                LEVEL_FULL,
                u.getId(),
                u.getCode(),
                u.getName(),
                u.getUsername(),
                u.getEmail(),
                u.getDisplayName(),
                u.isEnabled(),
                u.getRoleId(),
                AccessRole.TYPE_ID,
                u.getAccess() == null ? 0 : u.getAccess().globalFlags(),
                copyTypeFlags(u),
                u.getCreatedAt(),
                u.getCreatedBy() == null ? null : u.getCreatedBy().targetIdRaw(),
                u.getUpdatedBy() == null ? null : u.getUpdatedBy().targetIdRaw(),
                u.getInterfaceLayoutId(),
                InterfaceLayout.TYPE_ID
        );
    }

    /** Урезанная проекция: чужая запись глазами обычного пользователя. */
    public static UserDto basic(User u) {
        return new UserDto(
                LEVEL_BASIC,
                u.getId(),
                u.getCode(),
                u.getName(),
                u.getUsername(),
                null,                     // email — персональные данные
                u.getDisplayName(),
                u.isEnabled(),
                u.getRoleId(),
                AccessRole.TYPE_ID,
                null,                     // флаги доступа — только себе и админу
                null,
                u.getCreatedAt(),
                null,                     // аудит-ссылки
                null,
                null,                     // назначенный интерфейс
                InterfaceLayout.TYPE_ID
        );
    }

    private static Map<String, Integer> copyTypeFlags(User u) {
        if (u.getAccess() == null) return Map.of();
        Map<String, Integer> out = new HashMap<>();
        u.getAccess().typeFlags().forEach((tid, flags) -> out.put(String.valueOf(tid), flags));
        return out;
    }
}
