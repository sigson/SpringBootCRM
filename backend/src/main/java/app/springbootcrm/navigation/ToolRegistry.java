package app.springbootcrm.navigation;

import app.springbootcrm.interfaces.LayoutNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * Реестр <b>инструментальных контроллеров</b> — узлов навигации, которые НЕ являются
 * частью датамодели (не агрегаты), но несут полезную нагрузку: SQL Workbench,
 * Генерация данных и т.п. В дефолтном режиме они группируются в отдельный раздел
 * «Общие инструменты».
 *
 * <p>У каждого инструмента есть ключ (для ссылки из {@link app.springbootcrm.interfaces.LayoutNode#tool()}),
 * лейбл/иконка, фронт-маршрут и флаг {@code adminOnly} — по нему
 * {@link NavigationService} прячет инструмент от не-админов (серверное отсечение).
 *
 * <p>Добавить новый инструмент = добавить запись в {@link #TOOLS}. Реестр намеренно
 * статичный: инструменты — это кодовые маршруты фронтенда, а не данные БД.
 */
@Component
public class ToolRegistry {

    /**
     * Свободный контроллер — узел навигации вне датамодели (не агрегат), но
     * полноценный участник системы типов: несёт стабильный {@code typeId}
     * из зарезервированного диапазона {@code 9400+}. Фронтенд диспетчеризует его
     * по {@code typeId} через реестр представлений; если переопределяющего хука
     * нет — открытие даёт модалку 404 (гарантированно нестандартное представление).
     */
    public record ToolNode(
            long typeId,
            String key,
            String label,
            String icon,
            String route,
            boolean adminOnly
    ) {}

    /** Зарезервированный базовый typeId для свободных контроллеров. */
    public static final long FREE_CONTROLLER_TYPE_BASE = 9400L;

    /** Порядок хранения = порядок показа. */
    private static final List<ToolNode> TOOLS = List.of(
            new ToolNode(9401L, "sql-workbench", "SQL Workbench", "🗄️", "/tool/sql-workbench", true),
            new ToolNode(9402L, "datagen", "Data generation", "🎲", "/tool/datagen", true)
    );

    private final Map<String, ToolNode> byKey = new LinkedHashMap<>();
    private final Map<Long, ToolNode> byTypeId = new LinkedHashMap<>();

    public ToolRegistry() {
        for (ToolNode t : TOOLS) {
            byKey.put(t.key(), t);
            byTypeId.put(t.typeId(), t);
        }
    }

    public List<ToolNode> all() { return List.copyOf(byKey.values()); }

    public ToolNode byKey(String key) { return key == null ? null : byKey.get(key); }

    public ToolNode byTypeId(long typeId) { return byTypeId.get(typeId); }
}
