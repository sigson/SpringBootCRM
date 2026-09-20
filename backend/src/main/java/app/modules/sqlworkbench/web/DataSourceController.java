package app.modules.sqlworkbench.web;

import app.modules.sqlworkbench.dto.Dtos.*;
import app.modules.sqlworkbench.security.AccessGuard;
import app.modules.sqlworkbench.security.WorkbenchAction;
import app.modules.sqlworkbench.service.DataSourceRegistry;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Управление JDBC-датасорсами (порт MaskDatasource/ViewDatasources). */
@RestController("sqlworkbenchDataSourceController")
@RequestMapping("${sqlworkbench.base-path:/api/sqlworkbench}/datasources")
public class DataSourceController {

    private final DataSourceRegistry registry;
    private final AccessGuard guard;

    public DataSourceController(DataSourceRegistry registry, AccessGuard guard) {
        this.registry = registry; this.guard = guard;
    }

    @GetMapping
    public List<DataSourceInfo> list() {
        guard.check(WorkbenchAction.VIEW_DATASOURCES);
        return registry.list();
    }

    @PostMapping
    public DataSourceInfo register(@RequestBody DataSourceRequest req) {
        guard.check(WorkbenchAction.MANAGE_DATASOURCES);
        return registry.register(req);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> remove(@PathVariable String id) {
        guard.check(WorkbenchAction.MANAGE_DATASOURCES);
        registry.remove(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/test")
    public Map<String, Object> test(@PathVariable String id) {
        guard.check(WorkbenchAction.VIEW_DATASOURCES);
        return Map.of("id", id, "ok", registry.test(id));
    }
}
