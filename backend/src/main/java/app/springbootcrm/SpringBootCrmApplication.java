package app.springbootcrm;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

/**
 * SpringBootCRM — программный комплекс. Точка входа.
 *
 * <p>Компонентный scan охватывает {@code domain.*} (DDD-инфраструктура: access
 * control, lifecycle, persistence, eventing) и {@code app.*} (бизнес-модули
 * {@code app.springbootcrm.*}). Entity/JPA-scan остаётся на {@code @AutoConfigurationPackage}
 * (пакет этого класса + пакеты domain-core), поэтому отключение опциональных
 * модулей его не затрагивает.
 *
 * <h3>Опциональные модули ({@code app.modules.*})</h3>
 * <p>Биновые пакеты опциональных модулей ({@code security|service|web|dto|querymodel|bridge})
 * <b>исключены</b> из широкого scan'а регулярным {@code excludeFilters}. Каждый
 * модуль активируется <i>только</i> через собственный enable-config в своём
 * {@code …config}-пакете (например {@code app.modules.sqlworkbench.config.SqlWorkbenchModule}),
 * который и выполняет {@code @ComponentScan} по своим бинам — но лишь при
 * соответствующем {@code <module>.enabled=true}.
 *
 * <p>За счёт этого: (1) модуль отключается одной property; (2) удаление папки
 * {@code app/modules/<name>} не затрагивает {@code app.springbootcrm} и не ломает старт —
 * фильтр задан <u>строкой</u>, поэтому корректно «исключает» даже отсутствующий
 * пакет; (3) основная кодовая база не ссылается на модули.
 */
@SpringBootApplication
@ComponentScan(
        basePackages = {"domain", "app"},
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.REGEX,
                pattern = "app\\.modules\\..*\\.(security|service|web|dto|querymodel|bridge)\\..*"))
public class SpringBootCrmApplication {

    public static void main(String[] args) {
        SpringApplication.run(SpringBootCrmApplication.class, args);
    }
}
