package app.modules.dcs.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;

/**
 * <h2>Единая точка включения модуля «Компоновка данных» (аналог 1С:СКД).</h2>
 *
 * <p>Устроен ровно как {@code SqlWorkbenchModule}: единственный класс поднимает все
 * бины модуля своим {@link ComponentScan}, а хост исключает биновые подпакеты
 * {@code app.modules.*} из широкого scan'а. Отсюда три свойства — отключаемость одной
 * property, односторонняя зависимость «модуль → хост» и удаляемость одной папкой.
 *
 * <h3>Зависимость от Workbench'а</h3>
 * Модуль не пишет SQL сам: наборы данных отчёта — это пакет запросов
 * ({@code app.modules.sqlworkbench.querypack}), а исполнение идёт через
 * {@code QueryService} Workbench'а с его датасорсами и read-only-контролем. Поэтому
 * включение требует, чтобы был включён и Workbench, — что и выражает условие ниже.
 * Если Workbench выключен или удалён, раздел отчётов на фронтенде исчезает сам:
 * конструктор подгружается динамическим {@code import()}, и его отсутствие ловит
 * error-boundary хоста, а бэкенд отвечает 404 на {@code /api/dcs/capabilities}.
 *
 * <h3>Что остаётся при удалении папки</h3>
 * Справочник отчётов {@code app.springbootcrm.reports} — часть хоста и переживает
 * удаление модуля: элементы сохранятся как JSON-каталог, просто перестанут
 * формироваться. Хост на модуль не ссылается ни одной строкой.
 */
@Configuration
@ConditionalOnExpression("${dcs.enabled:true} and ${sqlworkbench.enabled:true}")
@EnableConfigurationProperties(DcsProperties.class)
@ComponentScan(
        basePackages = "app.modules.dcs",
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = DcsModule.class))
public class DcsModule {
}
