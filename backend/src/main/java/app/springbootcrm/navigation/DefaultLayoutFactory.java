package app.springbootcrm.navigation;

import app.springbootcrm.metadata.UiAggregate;

import app.springbootcrm.interfaces.LayoutNode;
import app.springbootcrm.metadata.AggregateClassification;
import app.springbootcrm.metadata.TypeRegistry;
import app.springbootcrm.metadata.TypeRegistry.TypeDescriptor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Генератор «дефолтного» режима интерфейса.
 *
 * <p>Дефолтный режим <b>не редактируется</b> и <b>всегда актуален</b> относительно
 * структуры бизнес-объектов: он целиком выводится из {@link TypeRegistry}, который
 * перестраивается из {@code MetadataSnapshot} на каждом старте приложения. Добавление
 * нового агрегата на бэкенде автоматически появляется здесь — без миграций данных
 * и правок фронта.
 *
 * <p>Структура:
 * <pre>
 *   Catalogs             ← все @UiAggregate с isReference=true (кроме табличных частей)
 *   Documents            ← агрегаты из app.springbootcrm.documents.*
 *   ----&lt;Справочник1&gt;
 *   ----&lt;Справочник2&gt;
 *   Registers            ← остальные @UiAggregate с isReference=false
 *   ----&lt;Регистр1&gt;
 *   Общие инструменты    ← инструментальные контроллеры (SQL Workbench, Генерация данных)
 *   ----SQLWorkbench
 *   ----ГенерацияДанных
 * </pre>
 *
 * <p>Табличные части сюда не попадают — они редактируются в контексте
 * агрегата-владельца (как ТЧ «Коэффициенты» в редакторе пользователя), а не как
 * самостоятельный пункт меню.
 *
 * <p>Дерево строится языком {@link LayoutNode} — тем же, что и кастомные интерфейсы.
 * За счёт этого {@link NavigationService} применяет одинаковую фильтрацию по правам
 * и к дефолту, и к кастомным интерфейсам.
 */
@Component
public class DefaultLayoutFactory {

    private final TypeRegistry types;
    private final ToolRegistry tools;

    public DefaultLayoutFactory(TypeRegistry types, ToolRegistry tools) {
        this.types = types;
        this.tools = tools;
    }

    /**
     * Строит дефолтное дерево «с нуля» из текущего {@link TypeRegistry}. Вызов
     * дешёвый (десятки типов), поэтому делается на каждый запрос — это гарантирует
     * актуальность без кеша/инвалидации.
     */
    public List<LayoutNode> build() {
        List<LayoutNode> references = new ArrayList<>();
        List<LayoutNode> documents = new ArrayList<>();
        List<LayoutNode> registers = new ArrayList<>();

        for (TypeDescriptor td : types.all()) {
            if (td.isTabularPart()) continue;            // ТЧ — не самостоятельный пункт
            // Свободные контроллеры (NONSTANDARD) в дефолтном дереве идут отдельной
            // группой через tools.all() ниже — здесь их пропускаем, чтобы не задвоить.
            if (TypeRegistry.REPRESENTATION_NONSTANDARD.equals(td.representation())) continue;
            if (td.isReference()) {
                references.add(LayoutNode.object(td.typeId()));
            } else if (td.isDocument()) {
                documents.add(LayoutNode.object(td.typeId()));
            } else {
                registers.add(LayoutNode.object(td.typeId()));
            }
        }

        List<LayoutNode> toolNodes = new ArrayList<>();
        for (ToolRegistry.ToolNode t : tools.all()) {
            toolNodes.add(LayoutNode.tool(t.key()));
        }

        List<LayoutNode> root = new ArrayList<>();
        if (!references.isEmpty()) root.add(LayoutNode.group("Catalogs", "📚", references));
        if (!documents.isEmpty())  root.add(LayoutNode.group("Documents", "📄", documents));
        if (!registers.isEmpty())  root.add(LayoutNode.group("Registers", "📊", registers));
        if (!toolNodes.isEmpty())  root.add(LayoutNode.group("General tools", "🧰", toolNodes));
        return root;
    }
}
