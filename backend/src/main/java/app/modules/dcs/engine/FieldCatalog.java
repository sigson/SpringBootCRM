package app.modules.dcs.engine;

import app.modules.dcs.model.DcsSchema;
import app.modules.dcs.model.DcsSettings;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * <h2>Каталог доступных полей схемы — единственный резолвер идентификаторов.</h2>
 *
 * <p>Настройки ссылаются на поля строками ({@code "Sales.amount"}, {@code "amountTotal"}),
 * и превратить такую строку в колонку набора, вычисляемое поле или ресурс должно одно
 * место — иначе отбор, группировка и порядок начнут расходиться в трактовке одного и
 * того же имени.
 *
 * <p>Имя разрешается в три приёма: полный путь {@code Набор.Поле} → ресурс/вычисляемое/
 * пользовательское поле по имени → короткое имя колонки, если оно уникально среди всех
 * наборов. Последнее — уступка удобству: в типовой схеме с одним набором писать префикс
 * набора незачем.
 */
public final class FieldCatalog {

    /** Что представляет собой идентификатор поля. */
    public enum Kind {
        /** Колонка набора данных — попадает в SQL. */
        SOURCE,
        /** Вычисляемое поле схемы — считается построчно процессором. */
        CALCULATED,
        /** Ресурс — агрегат по группировке. */
        RESOURCE,
        /** Пользовательское поле из настроек. */
        USER
    }

    /** Разрешённое поле. */
    public record FieldInfo(
            String id,
            Kind kind,
            String title,
            String role,
            String valueType,
            /** Имя набора (только для {@link Kind#SOURCE}). */
            String dataSet,
            /** Имя колонки в наборе (только для {@link Kind#SOURCE}). */
            String column,
            /** Псевдоним колонки в итоговом SELECT'е (только для {@link Kind#SOURCE}). */
            String alias,
            /** Текст выражения (для вычисляемых полей и ресурсов). */
            String expression,
            boolean usableInSelection,
            boolean usableInFilter,
            boolean usableInGroup,
            boolean usableInOrder,
            boolean ignoreNull,
            boolean mandatory
    ) {
        /** Квалифицированная ссылка на колонку в собранном SQL. */
        public String sqlRef() { return dataSet + "." + column; }
    }

    private final Map<String, FieldInfo> byId = new LinkedHashMap<>();
    /** Короткое имя → id; хранится только когда имя однозначно. */
    private final Map<String, String> shortNames = new LinkedHashMap<>();
    private final List<FieldInfo> ordered = new ArrayList<>();

    public FieldCatalog(DcsSchema schema, DcsSettings settings) {
        Map<String, Integer> shortNameCounts = new LinkedHashMap<>();
        Map<String, String> aliases = new LinkedHashMap<>();

        if (schema != null && schema.dataSets != null) {
            for (DcsSchema.DataSet ds : schema.dataSets) {
                if (ds == null || ds.name == null || ds.fields == null) continue;
                for (DcsSchema.DataSetField f : ds.fields) {
                    if (f == null || f.name == null || f.name.isBlank()) continue;
                    String id = ds.name + "." + f.name;
                    String alias = uniqueAlias(aliases, ds.name + "_" + f.name, id);
                    FieldInfo info = new FieldInfo(
                            id, Kind.SOURCE,
                            blankTo(f.title, f.name), f.role, f.valueType,
                            ds.name, f.name, alias, null,
                            f.usableInSelection, f.usableInFilter, f.usableInGroup, f.usableInOrder,
                            f.ignoreNull, f.mandatory);
                    register(info);
                    shortNameCounts.merge(key(f.name), 1, Integer::sum);
                    shortNames.putIfAbsent(key(f.name), id);
                }
            }
        }
        // Короткое имя, встретившееся в нескольких наборах, неоднозначно — убираем.
        shortNameCounts.forEach((name, count) -> { if (count > 1) shortNames.remove(name); });

        if (schema != null && schema.calculatedFields != null) {
            for (DcsSchema.CalculatedField c : schema.calculatedFields) {
                if (c == null || c.name == null || c.name.isBlank()) continue;
                register(new FieldInfo(
                        c.name, Kind.CALCULATED, blankTo(c.title, c.name), c.role, c.valueType,
                        null, null, null, c.expression,
                        c.usableInSelection, c.usableInFilter, c.usableInGroup, c.usableInOrder,
                        false, false));
            }
        }

        if (schema != null && schema.resources != null) {
            for (DcsSchema.ResourceField r : schema.resources) {
                if (r == null || r.name == null || r.name.isBlank()) continue;
                register(new FieldInfo(
                        r.name, Kind.RESOURCE, blankTo(r.title, r.name), "resource", "number",
                        null, null, null, r.expression,
                        true, false, false, true, false, false));
            }
        }

        if (settings != null && settings.userFields != null) {
            for (DcsSettings.UserField u : settings.userFields) {
                if (u == null || u.name == null || u.name.isBlank()) continue;
                register(new FieldInfo(
                        u.name, Kind.USER, blankTo(u.title, u.name), null, null,
                        null, null, null, u.expression,
                        true, true, true, true, false, false));
            }
        }
    }

    private void register(FieldInfo info) {
        byId.put(key(info.id()), info);
        ordered.add(info);
    }

    /** Разрешает идентификатор поля; {@code null} — такого поля в схеме нет. */
    public FieldInfo resolve(String id) {
        if (id == null || id.isBlank()) return null;
        String k = key(id.trim());
        FieldInfo direct = byId.get(k);
        if (direct != null) return direct;
        String viaShort = shortNames.get(k);
        return viaShort == null ? null : byId.get(key(viaShort));
    }

    /** Разрешает или бросает понятную ошибку — используется там, где поле обязано быть. */
    public FieldInfo require(String id) {
        FieldInfo f = resolve(id);
        if (f == null) throw new IllegalArgumentException("Unknown field in the settings: " + id);
        return f;
    }

    public List<FieldInfo> all() { return List.copyOf(ordered); }

    public List<FieldInfo> byKind(Kind kind) {
        return ordered.stream().filter(f -> f.kind() == kind).toList();
    }

    /** Заголовок поля для вывода; для неизвестного id возвращает сам id. */
    public String titleOf(String id) {
        FieldInfo f = resolve(id);
        return f == null ? id : f.title();
    }

    private static String uniqueAlias(Map<String, String> taken, String base, String owner) {
        String sane = base.replaceAll("[^A-Za-z0-9_]", "_");
        if (sane.isEmpty() || Character.isDigit(sane.charAt(0))) sane = "f_" + sane;
        String candidate = sane;
        int n = 2;
        while (taken.containsKey(key(candidate)) && !owner.equals(taken.get(key(candidate)))) {
            candidate = sane + "_" + n++;
        }
        taken.put(key(candidate), owner);
        return candidate;
    }

    /**
     * Регистронезависимый ключ: H2 в PostgreSQL-режиме приводит неэкранированные
     * идентификаторы к нижнему регистру, поэтому имя колонки из метаданных и имя из
     * настроек могут отличаться регистром, оставаясь одним полем.
     */
    private static String key(String s) { return s.toLowerCase(Locale.ROOT); }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
