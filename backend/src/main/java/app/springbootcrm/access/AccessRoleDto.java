package app.springbootcrm.access;

import domain.core.access.AccessMetric;

import java.time.Instant;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Public-проекция {@link AccessRole}.
 *
 * <p>{@code accessTemplate} разворачивается в читаемую для UI структуру:
 * <pre>{@code
 *   {
 *     "globalFlags": 7,
 *     "typeFlags": { "1001": 67 }
 *   }
 * }</pre>
 * Все права выражаются битами {@code AccessFlags}: {@code globalFlags} — глобальные,
 * {@code typeFlags[typeId]} — права на конкретный тип.
 */
public record AccessRoleDto(
        UUID id,
        String code,
        String name,
        String description,
        Map<String, Object> accessTemplate,
        boolean enabled,
        /* Дата створення (ISO Instant) — дані синтетичної колонки «Дата запису». */
        Instant createdAt,
        /* Audit-ссылки для скрытых колонок «Автор»/«Корректировка». */
        String createdBy,
        String updatedBy
) {

    public static AccessRoleDto of(AccessRole r) {
        return new AccessRoleDto(
                r.getId(),
                r.getCode(),
                r.getName(),
                r.getDescription(),
                metricToMap(r.getAccessTemplate()),
                r.isEnabled(),
                r.getCreatedAt(),
                r.getCreatedBy() == null ? null : r.getCreatedBy().targetIdRaw(),
                r.getUpdatedBy() == null ? null : r.getUpdatedBy().targetIdRaw()
        );
    }

    static Map<String, Object> metricToMap(AccessMetric m) {
        if (m == null) return Map.of();
        Map<String, Object> out = new HashMap<>();
        out.put("globalFlags", m.globalFlags());
        Map<String, Integer> tf = new HashMap<>();
        m.typeFlags().forEach((k, v) -> tf.put(k.toString(), v));
        out.put("typeFlags", tf);
        return out;
    }
}
