package app.modules.sqlworkbench.web;

import app.modules.sqlworkbench.dto.Dtos.*;
import app.modules.sqlworkbench.security.AccessGuard;
import app.modules.sqlworkbench.security.WorkbenchAction;
import app.modules.sqlworkbench.service.CrudService;
import app.modules.sqlworkbench.service.QueryService;
import org.springframework.web.bind.annotation.*;

/**
 * CRUD-гейтвей к содержимому таблиц (порт ContentView/UpdateModel/TaskUpdate).
 * Центральная функция переработанного продукта.
 */
@RestController("sqlworkbenchCrudController")
@RequestMapping("${sqlworkbench.base-path:/api/sqlworkbench}/datasources/{dsId}/tables/{table}/rows")
public class CrudController {

    private final CrudService crud;
    private final AccessGuard guard;

    public CrudController(CrudService crud, AccessGuard guard) {
        this.crud = crud; this.guard = guard;
    }

    @GetMapping
    public ResultSetDto read(@PathVariable String dsId, @PathVariable String table,
                             @RequestParam(required = false) String catalog,
                             @RequestParam(required = false) String schema,
                             @RequestParam(defaultValue = "0") int page,
                             @RequestParam(defaultValue = "" + QueryService.DEFAULT_PAGE_SIZE) int pageSize,
                             @RequestParam(required = false) String orderBy) {
        guard.check(WorkbenchAction.READ_DATA, dsId, schema, table);
        return crud.read(dsId, catalog, schema, table, page, pageSize, orderBy);
    }

    @PostMapping
    public CrudResult insert(@PathVariable String dsId, @PathVariable String table,
                             @RequestParam(required = false) String catalog,
                             @RequestParam(required = false) String schema,
                             @RequestBody RowMutation body) {
        guard.check(WorkbenchAction.INSERT_DATA, dsId, schema, table);
        return crud.insert(dsId, catalog, schema, table, body);
    }

    @PutMapping
    public CrudResult update(@PathVariable String dsId, @PathVariable String table,
                             @RequestParam(required = false) String catalog,
                             @RequestParam(required = false) String schema,
                             @RequestBody RowMutation body) {
        guard.check(WorkbenchAction.UPDATE_DATA, dsId, schema, table);
        return crud.update(dsId, catalog, schema, table, body);
    }

    @DeleteMapping
    public CrudResult delete(@PathVariable String dsId, @PathVariable String table,
                             @RequestParam(required = false) String catalog,
                             @RequestParam(required = false) String schema,
                             @RequestBody RowMutation body) {
        guard.check(WorkbenchAction.DELETE_DATA, dsId, schema, table);
        return crud.delete(dsId, catalog, schema, table, body);
    }
}
