package app.modules.sqlworkbench.config;

import app.springbootcrm.auth.AdminCheck;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * <h2>Настройки независимого модуля «SQL Workbench» (префикс {@code sqlworkbench.*}).</h2>
 *
 * <p>Модуль — портированная клиент-серверная версия SQLeo Visual Query Builder. Он
 * самодостаточен и <b>отключаем</b>: при {@code sqlworkbench.enabled=false} ни один
 * бин модуля не поднимается, и хост-приложение {@code app.springbootcrm} работает так, будто
 * модуля не существует.
 *
 * <p>Вся папка {@code app.modules.sqlworkbench} удаляема: при её отсутствии
 * component-scan просто ничего не находит, а основная кодовая база на неё не
 * ссылается.
 *
 * <h3>Доступ</h3>
 * По умолчанию раздел — <b>admin-only</b>: при отсутствии собственного бина
 * {@code WorkbenchAccessPolicy} политика доступа делегируется в {@code AdminCheck} хоста
 * (см. {@code bridge/AdminOnlyAccessPolicy}).
 */
@ConfigurationProperties(prefix = "sqlworkbench")
public class SqlWorkbenchProperties {

    /** Глобальный выключатель модуля. {@code false} — модуль полностью отключён. */
    private boolean enabled = true;

    /** Базовый путь REST-API модуля (под security-цепочкой хоста). */
    private String basePath = "/api/sqlworkbench";

    /**
     * Автоматически зарегистрировать основную БД хоста как read-only датасорс
     * («main»), чтобы админ сразу видел реальные таблицы SpringBootCRM. Запись в главную
     * БД заблокирована read-only флагом — только SELECT/просмотр.
     */
    private boolean registerHostDatasource = true;

    /** Имя, под которым показывается автозарегистрированный датасорс хоста. */
    private String hostDatasourceName = "SpringBootCRM (main database, read-only)";

    /** Id автозарегистрированного датасорса хоста. */
    private String hostDatasourceId = "main";

    private final Access access = new Access();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getBasePath() { return basePath; }
    public void setBasePath(String basePath) { this.basePath = basePath; }
    public boolean isRegisterHostDatasource() { return registerHostDatasource; }
    public void setRegisterHostDatasource(boolean v) { this.registerHostDatasource = v; }
    public String getHostDatasourceName() { return hostDatasourceName; }
    public void setHostDatasourceName(String v) { this.hostDatasourceName = v; }
    public String getHostDatasourceId() { return hostDatasourceId; }
    public void setHostDatasourceId(String v) { this.hostDatasourceId = v; }
    public Access getAccess() { return access; }

    /**
     * Настройки политики доступа по умолчанию. Используются только если хост НЕ
     * объявил собственный {@code WorkbenchAccessPolicy}. По умолчанию раздел admin-only
     * через мост в {@code AdminCheck}; эти поля сохранены для совместимости с
     * role-based режимом портированного модуля.
     */
    public static class Access {
        public enum Mode { ADMIN_ONLY, PERMIT_ALL, ROLE_BASED, DENY_ALL }
        private Mode mode = Mode.ADMIN_ONLY;
        private String defaultReadRole = "";
        private String defaultWriteRole = "";
        private Map<String, String> roles = new LinkedHashMap<>();
        public Mode getMode() { return mode; }
        public void setMode(Mode mode) { this.mode = mode; }
        public String getDefaultReadRole() { return defaultReadRole; }
        public void setDefaultReadRole(String r) { this.defaultReadRole = r; }
        public String getDefaultWriteRole() { return defaultWriteRole; }
        public void setDefaultWriteRole(String r) { this.defaultWriteRole = r; }
        public Map<String, String> getRoles() { return roles; }
        public void setRoles(Map<String, String> roles) { this.roles = roles; }
    }
}
