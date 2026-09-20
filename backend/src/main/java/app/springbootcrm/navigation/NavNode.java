package app.springbootcrm.navigation;

import app.springbootcrm.interfaces.LayoutNode;

import java.util.List;

/**
 * Разрешённый (после фильтрации по правам) узел навигации, отдаваемый фронту через
 * {@code GET /api/navigation}. В отличие от {@link app.springbootcrm.interfaces.LayoutNode}
 * (декларативный вход), эта запись уже содержит готовые к рендеру лейблы/иконки/маршруты
 * и не содержит недоступных пользователю веток.
 */
public record NavNode(
        /** Стабильный идентификатор (slug для OBJECT, ключ для TOOL, "grp:n" для GROUP). */
        String id,
        /** GROUP | OBJECT | TOOL. */
        String kind,
        String label,
        String icon,
        /** Фронт-маршрут: {@code /o/{slug}} для OBJECT, {@code /tool/{key}} для TOOL, {@code null} для GROUP. */
        String route,
        /** typeId агрегата для OBJECT (иначе null). */
        Long typeId,
        /** slug агрегата для OBJECT (иначе null). */
        String slug,
        /** ключ инструмента для TOOL (иначе null). */
        String tool,
        List<NavNode> children
) {}
