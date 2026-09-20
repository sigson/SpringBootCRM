package app.springbootcrm.interfaces;

import app.springbootcrm.navigation.ToolRegistry;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Узел дерева настраиваемого интерфейса (элемент JSON-реквизита
 * {@link InterfaceLayout#getLayout()}). Декларативно описывает навигацию: разработчик задаёт,
 * где какое меню и что открывается по клику, а backend берёт на себя фильтрацию по правам и сборку.
 *
 * <h3>Виды узлов ({@code kind}):</h3>
 * <ul>
 *   <li>{@code GROUP}  — логическая группа (подменю): {@code title} + {@code children}.
 *       Если после фильтрации по правам в группе не осталось доступных листьев, группа скрывается.</li>
 *   <li>{@code OBJECT} — ссылка на бизнес-объект (агрегат), несёт {@code typeId}. Лейбл и иконку
 *       берёт из метаданных типа, если не заданы явно. Скрывается, если у пользователя нет
 *       READ-доступа к {@code typeId}.</li>
 *   <li>{@code TOOL}   — инструментальный контроллер вне датамодели (SQL Workbench, генерация данных),
 *       несёт ключ {@code tool}; видимость определяет {@link app.springbootcrm.navigation.ToolRegistry}.</li>
 * </ul>
 *
 * <p>{@code @JsonIgnoreProperties(ignoreUnknown=true)} делает схему forward-совместимой: добавление
 * новых полей не ломает старые layout'ы. Обязателен только {@code kind}; остальные поля значимы
 * в зависимости от вида ({@code typeId} для OBJECT, {@code tool} для TOOL, {@code title}+{@code children} для GROUP).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LayoutNode(
        String kind,
        String title,
        String icon,
        Long typeId,
        String tool,
        List<LayoutNode> children
) {
    public static final String KIND_GROUP  = "GROUP";
    public static final String KIND_OBJECT = "OBJECT";
    public static final String KIND_TOOL   = "TOOL";

    /** Никогда не {@code null}: пустой список вместо null для удобства обхода. */
    @Override
    public List<LayoutNode> children() {
        return children == null ? List.of() : children;
    }

    public static LayoutNode group(String title, String icon, List<LayoutNode> children) {
        return new LayoutNode(KIND_GROUP, title, icon, null, null, children);
    }

    public static LayoutNode object(long typeId) {
        return new LayoutNode(KIND_OBJECT, null, null, typeId, null, List.of());
    }

    public static LayoutNode tool(String toolKey) {
        return new LayoutNode(KIND_TOOL, null, null, null, toolKey, List.of());
    }
}
