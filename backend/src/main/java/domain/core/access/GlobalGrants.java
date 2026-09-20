package domain.core.access;

import app.springbootcrm.access.AccessRole;

import java.util.Map;

/**
 * Field- и type-level overrides из конфигурации.
 *
 * <p>Заполняется через {@code GrantsProperties} (yaml: {@code app.ddd.access.grants.*}).
 * Применяется в {@link AccessResolver#resolve}: на каждом resolve'е field-level правила
 * фильтруют base-access ({@code @FieldId.defaultAccess}).
 *
 * <p>В текущей реализации поддерживаются:
 * <ul>
 *   <li>{@code typeDefaults}: typeId → AccessLevel (применяется ко всем полям типа);</li>
 *   <li>{@code fieldOverrides}: typeId → fieldId → AccessLevel (точечный override
 *       для критичных полей вроде {@code AccessRole.accessTemplate}).</li>
 * </ul>
 *
 */
public final class GlobalGrants {

    private final Map<Long, AccessLevel> typeDefaults;
    private final Map<Long, Map<Long, AccessLevel>> fieldOverrides;

    public GlobalGrants(Map<Long, AccessLevel> typeDefaults,
                        Map<Long, Map<Long, AccessLevel>> fieldOverrides) {
        this.typeDefaults = (typeDefaults == null) ? Map.of() : Map.copyOf(typeDefaults);
        this.fieldOverrides = (fieldOverrides == null) ? Map.of() : Map.copyOf(fieldOverrides);
    }

    public static GlobalGrants empty() {
        return new GlobalGrants(Map.of(), Map.of());
    }

    /**
     * Возвращает наиболее строгий уровень доступа из конфига для (typeId, fieldId).
     * Стартовая база — {@link AccessLevel#READ_WRITE} (полностью открыто),
     * затем по очереди пересекаем с type-override и field-override.
     */
    public AccessLevel evaluate(long typeId, long fieldId, AccessMetric userMetric) {
        AccessLevel base = AccessLevel.READ_WRITE;

        Map<Long, AccessLevel> fieldMap = fieldOverrides.get(typeId);
        if (fieldMap != null) {
            AccessLevel f = fieldMap.get(fieldId);
            if (f != null) base = base.intersect(f);
        }
        AccessLevel typeLevel = typeDefaults.get(typeId);
        if (typeLevel != null) base = base.intersect(typeLevel);

        return base;
    }

    /**
     * Фабрика из конфига. Сейчас grants-конфиг не вычитывается из yaml
     * (нет необходимости в текущем scope'е) — возвращает {@link #empty()}.
     * Для production'а: маппинг yaml → record.
     */
    public static GlobalGrants fromConfig(GrantsProperties props) {
        if (props == null) return empty();
        return empty();
    }
}
