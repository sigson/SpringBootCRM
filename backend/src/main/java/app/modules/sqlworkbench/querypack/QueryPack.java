package app.modules.sqlworkbench.querypack;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;

/**
 * <h2>Пакет запросов — несколько именованных SELECT'ов плюс настройка связей между ними.</h2>
 *
 * <p>Расширение {@link app.modules.sqlworkbench.querymodel.QueryModel} на случай, когда
 * источник данных не один: конструктор (или генератор отчётов СКД) упаковывает
 * <b>несколько запросов в одну строку</b> (см. {@link QueryPackCodec}), а настройка
 * связей ({@link #links}) описывает, как эти запросы соединяются. {@link QueryPackAssembler}
 * собирает из пакета один исполнимый SQL: каждый запрос становится CTE, связи —
 * цепочкой JOIN'ов.
 *
 * <p>Это серверный аналог «Наборов данных» + «Связей наборов данных» 1С:СКД, но
 * живущий в модуле Workbench'а и доступный независимо от него: сам по себе пакет —
 * просто способ соединить произвольные SELECT'ы, ничего не зная про отчёты.
 *
 * <p>Модель целиком сериализуема в JSON, поэтому одним и тем же деревом оперируют
 * визуальный конструктор на фронтенде, REST-контракт и движок компоновки.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class QueryPack {

    /** Тип соединения наборов. {@code CROSS} игнорирует условия связи. */
    public enum LinkType {
        INNER("INNER JOIN"),
        LEFT("LEFT OUTER JOIN"),
        RIGHT("RIGHT OUTER JOIN"),
        FULL("FULL OUTER JOIN"),
        CROSS("CROSS JOIN");

        public final String sql;
        LinkType(String sql) { this.sql = sql; }

        /** Разбор из текста директивы/JSON: принимает {@code LEFT}, {@code left}, {@code LEFT OUTER}. */
        public static LinkType parse(String s, LinkType fallback) {
            if (s == null || s.isBlank()) return fallback;
            String k = s.trim().toUpperCase(java.util.Locale.ROOT)
                    .replace("OUTER", "").replace("JOIN", "").trim();
            for (LinkType t : values()) if (t.name().equals(k)) return t;
            return fallback;
        }

        /** Зеркальный тип — нужен, когда связь применяется «с другой стороны» цепочки. */
        public LinkType mirrored() {
            return switch (this) { case LEFT -> RIGHT; case RIGHT -> LEFT; default -> this; };
        }
    }

    /** Именованный запрос пакета. Имя становится именем CTE и префиксом колонок. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PackedQuery {
        /** Идентификатор набора: {@code [A-Za-z_][A-Za-z0-9_]*}. */
        public String name;
        /** Человекочитаемый заголовок (в SQL не участвует). */
        public String title;
        /** Текст SELECT'а без завершающей точки с запятой. */
        public String sql;
        /** Временно исключить набор из сборки, не удаляя его из пакета. */
        public boolean disabled;

        public PackedQuery() {}
        public PackedQuery(String name, String sql) { this.name = name; this.sql = sql; }
    }

    /** Одно элементарное условие связи: {@code source.expr <op> target.expr}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class LinkCondition {
        /** Выражение со стороны источника. Без точки — трактуется как колонка источника. */
        public String sourceExpr;
        public String operator = "=";
        /** Выражение со стороны приёмника. Без точки — колонка приёмника. */
        public String targetExpr;

        public LinkCondition() {}
        public LinkCondition(String sourceExpr, String operator, String targetExpr) {
            this.sourceExpr = sourceExpr;
            this.operator = operator == null || operator.isBlank() ? "=" : operator;
            this.targetExpr = targetExpr;
        }
    }

    /** Связь двух наборов (аналог «Связи наборов данных» СКД). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PackLink {
        /** Имя набора-источника («главный»). */
        public String source;
        /** Имя набора-приёмника («подчинённый»). */
        public String target;
        public LinkType type = LinkType.LEFT;
        /** Структурные условия. Пусто и {@link #rawCondition} пуст → связь по природному CROSS. */
        public List<LinkCondition> conditions = new ArrayList<>();
        /** Произвольный текст ON-условия; если задан — перекрывает {@link #conditions}. */
        public String rawCondition;
        public boolean disabled;
    }

    /** Имя пакета (для UI и заголовков; в SQL не участвует). */
    public String name;

    public List<PackedQuery> queries = new ArrayList<>();
    public List<PackLink> links = new ArrayList<>();

    /**
     * Явный список выражений итогового SELECT'а (например {@code Sales.item AS item}).
     * Пусто → {@code <набор>.*} по каждому участвующему набору.
     */
    public List<String> select = new ArrayList<>();
    /** Дополнительные условия итогового WHERE (соединяются через AND). */
    public List<String> where = new ArrayList<>();
    public List<String> groupBy = new ArrayList<>();
    /** Условия HAVING (соединяются через AND). */
    public List<String> having = new ArrayList<>();
    /** Элементы ORDER BY целиком, включая направление ({@code total DESC}). */
    public List<String> orderBy = new ArrayList<>();
    public Integer limit;
    public boolean distinct;

    public List<PackedQuery> enabledQueries() {
        return queries == null ? List.of() : queries.stream().filter(q -> !q.disabled).toList();
    }

    public List<PackLink> enabledLinks() {
        return links == null ? List.of() : links.stream().filter(l -> !l.disabled).toList();
    }

    public PackedQuery query(String name) {
        if (name == null || queries == null) return null;
        return queries.stream().filter(q -> name.equals(q.name)).findFirst().orElse(null);
    }
}
