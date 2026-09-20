package app.modules.sqlworkbench.security;

/**
 * Перечень защищаемых действий API-гейтвея SpringBootCRM.
 * Host-продукт через {@link WorkbenchAccessPolicy} решает, какие действия
 * разрешены конкретному пользователю/датасорсу/таблице.
 */
public enum WorkbenchAction {
    VIEW_DATASOURCES,
    MANAGE_DATASOURCES,
    READ_METADATA,
    RUN_QUERY,
    READ_DATA,
    INSERT_DATA,
    UPDATE_DATA,
    DELETE_DATA,
    BUILD_QUERY
}
