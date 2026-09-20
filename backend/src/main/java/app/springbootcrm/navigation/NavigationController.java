package app.springbootcrm.navigation;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * {@code GET /api/navigation} — дерево навигации для текущего пользователя (уже
 * выбранное и отфильтрованное по правам на сервере).
 *
 * {@code GET /api/navigation/tools} — список инструментальных контроллеров для
 * визуального конструктора интерфейса (источник — {@link ToolRegistry}, чтобы не
 * было дрейфа между фронтом и бэкендом).
 */
@RestController
@RequestMapping("/api/navigation")
public class NavigationController {

    private final NavigationService service;
    private final ToolRegistry tools;

    public NavigationController(NavigationService service, ToolRegistry tools) {
        this.service = service;
        this.tools = tools;
    }

    @GetMapping
    public List<NavNode> navigation() {
        return service.resolveForCurrentUser();
    }

    @GetMapping("/tools")
    public List<ToolRegistry.ToolNode> tools() {
        return tools.all();
    }
}
