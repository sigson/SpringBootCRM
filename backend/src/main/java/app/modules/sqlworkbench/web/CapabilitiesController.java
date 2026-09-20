package app.modules.sqlworkbench.web;

import app.modules.sqlworkbench.security.AccessGuard;
import app.modules.sqlworkbench.security.WorkbenchAction;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Возвращает фронтенду набор разрешённых текущему пользователю действий.
 * proxy-view использует это, чтобы скрывать/блокировать недоступные вкладки и кнопки.
 */
@RestController("sqlworkbenchCapabilitiesController")
@RequestMapping("${sqlworkbench.base-path:/api/sqlworkbench}")
public class CapabilitiesController {

    private final AccessGuard guard;

    public CapabilitiesController(AccessGuard guard) { this.guard = guard; }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "UP", "module", "springbootcrm");
    }

    @GetMapping("/capabilities")
    public Map<String, Boolean> capabilities() {
        Map<String, Boolean> caps = new LinkedHashMap<>();
        for (WorkbenchAction a : WorkbenchAction.values()) {
            caps.put(a.name(), guard.allowed(a, null, null, null));
        }
        return caps;
    }
}
