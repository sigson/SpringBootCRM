package app.modules.sqlworkbench.service;

import app.modules.sqlworkbench.dto.Dtos.QueryPackSqlResponse;
import app.modules.sqlworkbench.dto.Dtos.QueryRequest;
import app.modules.sqlworkbench.dto.Dtos.ResultSetDto;
import app.modules.sqlworkbench.querypack.QueryPack;
import app.modules.sqlworkbench.querypack.QueryPackAssembler;
import app.modules.sqlworkbench.querypack.QueryPackCodec;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * <h2>Сервис пакетов запросов: строка с несколькими запросами + связи → один SQL.</h2>
 *
 * <p>Точка входа и для вкладки «Пакет запросов» самого Workbench'а, и для внешних
 * потребителей (движок компоновки отчётов вызывает его напрямую, минуя REST).
 * Сервис намеренно ничего не знает про отчёты: его контракт — «дай пакет, получи SQL
 * или результат».
 */
@Service("sqlworkbenchQueryPackService")
public class QueryPackService {

    private final QueryService queryService;

    public QueryPackService(QueryService queryService) { this.queryService = queryService; }

    /** Разбор упакованной строки в структуру пакета. */
    public QueryPack parse(String packed) { return QueryPackCodec.decode(packed); }

    /** Обратная упаковка структуры в строку. */
    public String pack(QueryPack pack) { return QueryPackCodec.encode(pack); }

    /** Сборка пакета в SQL (плоский и с переносами) плюс диагностика. */
    public QueryPackSqlResponse toSql(QueryPack pack) {
        QueryPackAssembler.Assembled flat = QueryPackAssembler.assemble(pack, false);
        QueryPackAssembler.Assembled pretty = QueryPackAssembler.assemble(pack, true);
        return new QueryPackSqlResponse(
                flat.sql(), pretty.sql(), flat.joinedQueries(), flat.warnings(), QueryPackCodec.encode(pack));
    }

    /** Сборка и выполнение пакета. */
    public ResultSetDto run(QueryPack pack, String dsId, Integer page, Integer pageSize) {
        return run(pack, dsId, page, pageSize, List.of());
    }

    /** Сборка и выполнение с позиционными параметрами ({@code ?} в выражениях пакета). */
    public ResultSetDto run(QueryPack pack, String dsId, Integer page, Integer pageSize, List<Object> params) {
        String sql = QueryPackAssembler.assemble(pack, false).sql();
        return queryService.runSelect(dsId, new QueryRequest(sql, page, pageSize), params);
    }
}
