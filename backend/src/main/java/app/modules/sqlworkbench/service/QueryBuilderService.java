package app.modules.sqlworkbench.service;

import app.modules.sqlworkbench.dto.Dtos.QueryRequest;
import app.modules.sqlworkbench.dto.Dtos.ResultSetDto;
import app.modules.sqlworkbench.querymodel.QueryModel;
import app.modules.sqlworkbench.querymodel.SqlFormatter;
import org.springframework.stereotype.Service;

/**
 * Сервис визуального конструктора запросов — порт QueryModel/QueryBuilder.
 * Принимает дерево {@link QueryModel} (построенное на диаграмме во фронтенде),
 * сериализует в SQL и при необходимости исполняет.
 */
@Service("sqlworkbenchQueryBuilderService")
public class QueryBuilderService {

    private final QueryService queryService;

    public QueryBuilderService(QueryService queryService) { this.queryService = queryService; }

    public String toSql(QueryModel model, boolean pretty) {
        return SqlFormatter.toSql(model, pretty);
    }

    public ResultSetDto run(String dsId, QueryModel model, Integer page, Integer pageSize) {
        String sql = SqlFormatter.toSql(model, false);
        return queryService.runSelect(dsId, new QueryRequest(sql, page, pageSize));
    }
}
