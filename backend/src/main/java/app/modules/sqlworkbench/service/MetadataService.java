package app.modules.sqlworkbench.service;

import app.modules.sqlworkbench.dto.Dtos.*;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.util.*;

/**
 * Чтение метаданных БД через JDBC DatabaseMetaData — порт
 * com.sqleo.environment.ctrl.explorer.ViewMetadata / SideNavigator.
 * Предоставляет данные для обозревателя схемы во фронтенде.
 */
@Service("sqlworkbenchMetadataService")
public class MetadataService {

    private final DataSourceRegistry registry;

    public MetadataService(DataSourceRegistry registry) { this.registry = registry; }

    /**
     * Системные (служебные) схемы СУБД, которые НЕ должны протекать в обозреватель
     * таблиц.
     *
     * <p>Например, H2 держит в {@code INFORMATION_SCHEMA} собственное системное
     * представление {@code USERS} (список пользователей БД). Поскольку
     * {@code getTables(null, null, ...)} перебирает объекты всех схем, оно
     * показалось бы рядом с прикладным {@code PUBLIC.users} — поэтому системные
     * схемы отсекаются.
     *
     * <p>Перечень покрывает H2 ({@code INFORMATION_SCHEMA}) и PostgreSQL
     * ({@code pg_catalog}, {@code information_schema}). Сравнение — без учёта регистра.
     */
    private static final Set<String> SYSTEM_SCHEMAS = Set.of(
            "INFORMATION_SCHEMA", "PG_CATALOG", "PG_TOAST");

    private static boolean isSystemSchema(String schema) {
        return schema != null && SYSTEM_SCHEMAS.contains(schema.toUpperCase(Locale.ROOT));
    }

    public List<SchemaInfo> schemas(String dsId) {
        List<SchemaInfo> out = new ArrayList<>();
        try (Connection c = registry.connection(dsId)) {
            DatabaseMetaData md = c.getMetaData();
            try (ResultSet rs = md.getSchemas()) {
                while (rs.next()) {
                    String schema = rs.getString("TABLE_SCHEM");
                    if (isSystemSchema(schema)) continue;   // не показываем служебные схемы
                    out.add(new SchemaInfo(rs.getString("TABLE_CATALOG"), schema));
                }
            }
            if (out.isEmpty()) { // БД без схем (напр. MySQL) — берём каталоги
                try (ResultSet rs = md.getCatalogs()) {
                    while (rs.next()) out.add(new SchemaInfo(rs.getString("TABLE_CAT"), null));
                }
            }
        } catch (SQLException e) {
            throw asRuntime(e);
        }
        return out;
    }

    public List<TableInfo> tables(String dsId, String catalog, String schema) {
        List<TableInfo> out = new ArrayList<>();
        try (Connection c = registry.connection(dsId)) {
            DatabaseMetaData md = c.getMetaData();
            try (ResultSet rs = md.getTables(catalog, schema, "%",
                    new String[]{"TABLE", "VIEW"})) {
                while (rs.next()) {
                    String sch = rs.getString("TABLE_SCHEM");
                    // Отсекаем объекты системных схем (в частности INFORMATION_SCHEMA.USERS в H2)
                    // независимо от того, передал клиент конкретную схему или null.
                    if (isSystemSchema(sch)) continue;
                    out.add(new TableInfo(
                            rs.getString("TABLE_CAT"), sch,
                            rs.getString("TABLE_NAME"), rs.getString("TABLE_TYPE"),
                            rs.getString("REMARKS")));
                }
            }
        } catch (SQLException e) {
            throw asRuntime(e);
        }
        out.sort(Comparator.comparing(TableInfo::name, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    public TableMetadata table(String dsId, String catalog, String schema, String table) {
        try (Connection c = registry.connection(dsId)) {
            DatabaseMetaData md = c.getMetaData();

            Set<String> pk = new HashSet<>();
            try (ResultSet rs = md.getPrimaryKeys(catalog, schema, table)) {
                while (rs.next()) pk.add(rs.getString("COLUMN_NAME"));
            }

            List<ColumnInfo> cols = new ArrayList<>();
            try (ResultSet rs = md.getColumns(catalog, schema, table, "%")) {
                while (rs.next()) {
                    String name = rs.getString("COLUMN_NAME");
                    cols.add(new ColumnInfo(
                            name, rs.getString("TYPE_NAME"), rs.getInt("DATA_TYPE"),
                            rs.getInt("COLUMN_SIZE"),
                            rs.getInt("NULLABLE") == DatabaseMetaData.columnNullable,
                            pk.contains(name), rs.getString("REMARKS")));
                }
            }

            List<ForeignKeyInfo> fks = new ArrayList<>();
            try (ResultSet rs = md.getImportedKeys(catalog, schema, table)) {
                while (rs.next()) {
                    fks.add(new ForeignKeyInfo(
                            rs.getString("FKCOLUMN_NAME"), rs.getString("PKTABLE_NAME"),
                            rs.getString("PKCOLUMN_NAME"), rs.getString("FK_NAME")));
                }
            }

            TableInfo info = new TableInfo(catalog, schema, table, "TABLE", null);
            return new TableMetadata(info, cols, fks);
        } catch (SQLException e) {
            throw asRuntime(e);
        }
    }

    private RuntimeException asRuntime(SQLException e) {
        return new IllegalStateException("Failed to read metadata: " + e.getMessage(), e);
    }
}
