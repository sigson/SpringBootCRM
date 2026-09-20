package app.modules.sqlworkbench.web;

import app.modules.sqlworkbench.dto.Dtos.*;
import app.modules.sqlworkbench.querymodel.QueryModel;
import app.modules.sqlworkbench.security.AccessGuard;
import app.modules.sqlworkbench.security.WorkbenchAction;
import app.modules.sqlworkbench.service.QueryBuilderService;
import app.modules.sqlworkbench.service.QueryService;
import org.springframework.web.bind.annotation.*;

/** SQL-редактор и визуальный конструктор (порт editor + QueryBuilder). */
@RestController("sqlworkbenchQueryController")
@RequestMapping("${sqlworkbench.base-path:/api/sqlworkbench}")
public class QueryController {

    private final QueryService queryService;
    private final QueryBuilderService builderService;
    private final AccessGuard guard;

    public QueryController(QueryService queryService, QueryBuilderService builderService, AccessGuard guard) {
        this.queryService = queryService; this.builderService = builderService; this.guard = guard;
    }

    /** Выполнение произвольного SELECT из редактора. */
    @PostMapping("/datasources/{dsId}/query")
    public ResultSetDto query(@PathVariable String dsId, @RequestBody QueryRequest req) {
        guard.check(WorkbenchAction.RUN_QUERY, dsId, null, null);
        return queryService.runSelect(dsId, req);
    }

    /** Сериализация модели визуального конструктора в SQL (без выполнения). */
    @PostMapping("/query-builder/sql")
    public BuildSqlResponse buildSql(@RequestBody QueryModel model,
                                     @RequestParam(defaultValue = "true") boolean pretty) {
        guard.check(WorkbenchAction.BUILD_QUERY);
        return new BuildSqlResponse(
                builderService.toSql(model, false),
                builderService.toSql(model, pretty));
    }

    /** Построение SQL из модели и его выполнение. */
    @PostMapping("/datasources/{dsId}/query-builder/run")
    public ResultSetDto runBuilder(@PathVariable String dsId, @RequestBody QueryModel model,
                                   @RequestParam(required = false) Integer page,
                                   @RequestParam(required = false) Integer pageSize) {
        guard.check(WorkbenchAction.BUILD_QUERY, dsId, model.schema, null);
        guard.check(WorkbenchAction.RUN_QUERY, dsId, model.schema, null);
        return builderService.run(dsId, model, page, pageSize);
    }
}
