package app.modules.sqlworkbench.web;

import app.modules.sqlworkbench.dto.Dtos.*;
import app.modules.sqlworkbench.security.AccessGuard;
import app.modules.sqlworkbench.security.WorkbenchAction;
import app.modules.sqlworkbench.service.MetadataService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Обозреватель схемы (порт ViewMetadata/SideNavigator). */
@RestController("sqlworkbenchMetadataController")
@RequestMapping("${sqlworkbench.base-path:/api/sqlworkbench}/datasources/{dsId}/metadata")
public class MetadataController {

    private final MetadataService metadata;
    private final AccessGuard guard;

    public MetadataController(MetadataService metadata, AccessGuard guard) {
        this.metadata = metadata; this.guard = guard;
    }

    @GetMapping("/schemas")
    public List<SchemaInfo> schemas(@PathVariable String dsId) {
        guard.check(WorkbenchAction.READ_METADATA, dsId, null, null);
        return metadata.schemas(dsId);
    }

    @GetMapping("/tables")
    public List<TableInfo> tables(@PathVariable String dsId,
                                  @RequestParam(required = false) String catalog,
                                  @RequestParam(required = false) String schema) {
        guard.check(WorkbenchAction.READ_METADATA, dsId, schema, null);
        return metadata.tables(dsId, catalog, schema);
    }

    @GetMapping("/tables/{table}")
    public TableMetadata table(@PathVariable String dsId, @PathVariable String table,
                               @RequestParam(required = false) String catalog,
                               @RequestParam(required = false) String schema) {
        guard.check(WorkbenchAction.READ_METADATA, dsId, schema, table);
        return metadata.table(dsId, catalog, schema, table);
    }
}
