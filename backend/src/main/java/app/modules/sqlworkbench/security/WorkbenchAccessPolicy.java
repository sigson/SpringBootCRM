package app.modules.sqlworkbench.security;

/**
 * ТОЧКА РАСШИРЕНИЯ для интеграции в host-продукт.
 *
 * Программист host-приложения объявляет свой @Bean, реализующий этот интерфейс,
 * и тем самым полностью управляет ограничением доступа к API SpringBootCRM:
 * по ролям, по конкретным датасорсам, по таблицам, по типу операции.
 *
 * Если бин не объявлен — используется {@link DefaultWorkbenchAccessPolicy},
 * managed through springbootcrm.access.* in the configuration.
 */
public interface WorkbenchAccessPolicy {

    /** @return true, если действие разрешено в данном контексте. */
    boolean isAllowed(WorkbenchAction action, AccessContext ctx);

    /** Бросает {@link AccessDeniedWorkbenchException}, если действие запрещено. */
    default void check(WorkbenchAction action, AccessContext ctx) {
        if (!isAllowed(action, ctx)) {
            throw new AccessDeniedWorkbenchException(action, ctx);
        }
    }
}
