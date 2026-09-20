package app.modules.sqlworkbench.web;

import app.modules.sqlworkbench.dto.Dtos.QueryPackRequest;
import app.modules.sqlworkbench.dto.Dtos.QueryPackSqlResponse;
import app.modules.sqlworkbench.dto.Dtos.ResultSetDto;
import app.modules.sqlworkbench.querypack.QueryPack;
import app.modules.sqlworkbench.security.AccessGuard;
import app.modules.sqlworkbench.security.WorkbenchAction;
import app.modules.sqlworkbench.service.QueryPackService;
import org.springframework.web.bind.annotation.*;

/**
 * REST пакетов запросов: разбор упакованной строки, сборка её в один SQL по настройке
 * связей и выполнение результата.
 *
 * <p>Все три эндпоинта принимают либо структуру ({@code pack}), либо строку
 * ({@code packed}) — фронтенд редактирует связи в структуре, а хранит строку.
 */
@RestController("sqlworkbenchQueryPackController")
@RequestMapping("${sqlworkbench.base-path:/api/sqlworkbench}")
public class QueryPackController {

    private final QueryPackService service;
    private final AccessGuard guard;

    public QueryPackController(QueryPackService service, AccessGuard guard) {
        this.service = service;
        this.guard = guard;
    }

    /** Строка → структура пакета (наборы + связи + части итогового запроса). */
    @PostMapping("/query-pack/parse")
    public QueryPack parse(@RequestBody QueryPackRequest req) {
        guard.check(WorkbenchAction.BUILD_QUERY);
        return service.parse(req.packed());
    }

    /** Структура пакета → упакованная строка. */
    @PostMapping("/query-pack/encode")
    public QueryPackSqlResponse encode(@RequestBody QueryPackRequest req) {
        guard.check(WorkbenchAction.BUILD_QUERY);
        return service.toSql(resolve(req));
    }

    /** Сборка пакета в SQL без выполнения (предпросмотр и отладка). */
    @PostMapping("/query-pack/sql")
    public QueryPackSqlResponse sql(@RequestBody QueryPackRequest req) {
        guard.check(WorkbenchAction.BUILD_QUERY);
        return service.toSql(resolve(req));
    }

    /** Сборка и выполнение пакета на указанном датасорсе. */
    @PostMapping("/datasources/{dsId}/query-pack/run")
    public ResultSetDto run(@PathVariable String dsId,
                            @RequestBody QueryPackRequest req,
                            @RequestParam(required = false) Integer page,
                            @RequestParam(required = false) Integer pageSize) {
        guard.check(WorkbenchAction.BUILD_QUERY, dsId, null, null);
        guard.check(WorkbenchAction.RUN_QUERY, dsId, null, null);
        return service.run(resolve(req), dsId, page, pageSize);
    }

    /** Структура имеет приоритет; строка — запасной вход для «сырых» вызовов. */
    private QueryPack resolve(QueryPackRequest req) {
        if (req.pack() != null) return req.pack();
        return service.parse(req.packed());
    }
}
