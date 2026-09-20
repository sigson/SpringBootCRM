package app.modules.sqlworkbench.security;

/** Исключение запрета доступа SpringBootCRM (мапится на HTTP 403). */
public class AccessDeniedWorkbenchException extends RuntimeException {
    public AccessDeniedWorkbenchException(WorkbenchAction action, AccessContext ctx) {
        super("Access denied: action " + action + " for user '" +
                (ctx == null ? "?" : ctx.principal()) + "'" +
                (ctx != null && ctx.dataSourceId() != null ? " on data source '" + ctx.dataSourceId() + "'" : ""));
    }
}
