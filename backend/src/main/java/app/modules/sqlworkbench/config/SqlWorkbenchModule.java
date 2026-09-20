package app.modules.sqlworkbench.config;

import app.springbootcrm.SpringBootCrmApplication;
import app.springbootcrm.auth.AdminCheck;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;

/**
 * <h2>Единая точка включения независимого модуля «SQL Workbench».</h2>
 *
 * <p>Портированная клиент-серверная версия SQLeo Visual Query Builder. Этот класс —
 * и только он — поднимает все бины модуля через {@link ComponentScan} по пакету
 * {@code app.modules.sqlworkbench}, и делает это лишь при
 * {@code sqlworkbench.enabled=true} (default true).
 *
 * <h3>Три свойства модуля</h3>
 * <ol>
 *   <li><b>Отключаемость.</b> {@code sqlworkbench.enabled=false} → весь
 *       {@code @ComponentScan} не выполняется: ни контроллера, ни сервиса, ни
 *       политики доступа. Раздел исчезает и на бэкенде, и (через 404 на
 *       capabilities) на фронтенде.</li>
 *
 *   <li><b>Независимость / односторонняя связь.</b> Основная кодовая база
 *       {@code app.springbootcrm.*} <u>не</u> импортирует ничего из {@code app.modules.*}.
 *       Связь только в одну сторону: модуль → хост (мост {@code AdminCheck}).
 *       Поэтому хост компилируется и работает без модуля.</li>
 *
 *   <li><b>Удаляемость одной папкой.</b> Хост ({@code SpringBootCrmApplication})
 *       намеренно <b>исключает</b> {@code app.modules} из своего component-scan
 *       (строковый {@code excludeFilters}). Модуль активируется только отсюда.
 *       Поэтому если удалить папку {@code app/modules/sqlworkbench} целиком —
 *       исчезает и этот класс, приложение компилируется и стартует без изменений
 *       в {@code app.springbootcrm}, а строковый фильтр в хосте корректно исключает
 *       несуществующий пакет.</li>
 * </ol>
 *
 * <p>Этот класс сам попадает в контекст через широкий scan хоста по {@code app}
 * (он под {@code app.modules}, который исключён лишь <i>частично</i>: фильтр в
 * хосте пропускает именно этот enable-config — см. {@code SpringBootCrmApplication}).
 *
 * <p><b>Безопасность.</b> Модуль НЕ объявляет собственной {@code SecurityFilterChain}:
 * эндпоинты {@code /api/sqlworkbench/**} обслуживает существующая цепочка хоста
 * (аутентификация для {@code /api/**}). Admin-only обеспечивает
 * {@code AccessGuard → WorkbenchAccessPolicy} (мост в {@code AdminCheck}).
 */
@Configuration
@ConditionalOnProperty(prefix = "sqlworkbench", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(SqlWorkbenchProperties.class)
@ComponentScan(
        basePackages = "app.modules.sqlworkbench",
        // Не сканируем самих себя повторно (иначе циклическая регистрация конфига).
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = SqlWorkbenchModule.class))
public class SqlWorkbenchModule {
}
