package app.modules.sqlworkbench.bridge;

import app.modules.sqlworkbench.config.SqlWorkbenchProperties;
import app.modules.sqlworkbench.dto.Dtos.DataSourceRequest;
import app.modules.sqlworkbench.service.DataSourceRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.sql.Connection;

/**
 * <h2>Мост: автоматическая регистрация основной БД хоста как датасорса модуля.</h2>
 *
 * <p>Берёт имеющийся {@link DataSource} хоста (пул, настроенный Spring Boot по
 * {@code spring.datasource.*}) и регистрирует его в {@link DataSourceRegistry}
 * под id {@code sqlworkbench.host-datasource-id} (по умолчанию «main»),
 * <b>read-only</b>. Благодаря этому администратор сразу видит реальные таблицы
 * SpringBootCRM (users, activity, access_role, …) и может строить SELECT-ы и
 * просматривать строки — без ручного ввода JDBC-кредов.
 *
 * <p>Read-only по умолчанию — намеренный предохранитель: просмотр и запросы
 * безопасны, а любая мутация главной БД заблокирована на уровне
 * {@code DataSourceRegistry.isReadOnly} + политики (root для write-действий).
 * Если нужен полный CRUD над рабочей БД — админ регистрирует отдельный
 * (writable) датасорс через UI вкладки «Соединения».
 *
 * <p>Выключатель: {@code sqlworkbench.register-host-datasource} (default true).
 */
@Configuration
@ConditionalOnProperty(prefix = "sqlworkbench", name = "register-host-datasource",
        havingValue = "true", matchIfMissing = true)
public class HostDataSourceRegistrar {

    private static final Logger log = LoggerFactory.getLogger(HostDataSourceRegistrar.class);

    @Bean
    public ApplicationRunner registerHostDataSource(DataSourceRegistry registry,
                                                    DataSource hostDataSource,
                                                    SqlWorkbenchProperties props) {
        return args -> {
            String id = props.getHostDatasourceId();
            String driver = "";
            String url = "(host pool)";
            String user = "";
            try (Connection c = hostDataSource.getConnection()) {
                var md = c.getMetaData();
                url = md.getURL();
                user = md.getUserName();
                driver = md.getDriverName();
            } catch (Exception e) {
                log.warn("[sqlworkbench] could not read the host pool metadata: {}", e.getMessage());
            }
            // readOnly = true → только просмотр/SELECT главной БД.
            DataSourceRequest req = new DataSourceRequest(
                    id, props.getHostDatasourceName(), driver, url, user, "", true);
            registry.register(req, hostDataSource);
            log.info("[sqlworkbench] registered the host database as data source '{}' (read-only)", id);
        };
    }
}
