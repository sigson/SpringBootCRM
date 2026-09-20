package app.modules.dcs.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Настройки модуля компоновки данных (префикс {@code dcs.*}).
 *
 * <p>Модуль отключаем и удаляем одной папкой, как и Workbench. Разница одна: он
 * <b>требует</b> Workbench, потому что собирает наборы данных в связь его сборщиком
 * пакетов. Поэтому условие включения — обе property сразу (см. {@link DcsModule}).
 */
@ConfigurationProperties(prefix = "dcs")
public class DcsProperties {

    /** Глобальный выключатель модуля. */
    private boolean enabled = true;

    /** Базовый путь REST-API модуля. */
    private String basePath = "/api/dcs";

    /** Предел исходных записей по умолчанию, если отчёт не задал свой. */
    private int maxRows = 100_000;

    /** Датасорс Workbench'а, используемый, когда отчёт его не указал. */
    private String defaultDataSourceId = "main";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getBasePath() { return basePath; }
    public void setBasePath(String basePath) { this.basePath = basePath; }
    public int getMaxRows() { return maxRows; }
    public void setMaxRows(int maxRows) { this.maxRows = maxRows; }
    public String getDefaultDataSourceId() { return defaultDataSourceId; }
    public void setDefaultDataSourceId(String v) { this.defaultDataSourceId = v; }
}
