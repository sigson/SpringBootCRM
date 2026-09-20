package app.modules.dcs.bridge;

import app.modules.dcs.model.DcsSchema;
import app.modules.dcs.model.DcsSettings;
import app.modules.dcs.model.SettingsBundle;
import app.springbootcrm.reports.Report;
import app.springbootcrm.reports.ReportService;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * <h2>Единственная точка связи модуля с хостом.</h2>
 *
 * <p>Схемы и настройки отчётов хранит справочник хоста, потому что это обычные данные
 * со своим доступом, кодом, аудитом и списком. Модуль читает их через этот мост —
 * прямым вызовом сервиса, а не HTTP-запросом к самому себе.
 *
 * <p>Направление зависимости строго одно: {@code app.modules.dcs → app.springbootcrm}.
 * Обратной ссылки нет нигде, поэтому хост собирается и работает без модуля.
 *
 * <p>Разбор JSON-реквизитов в типизированную модель происходит здесь же: наружу мост
 * отдаёт {@link Loaded}, а не сырое дерево, — дальше по модулю сырых узлов уже нет.
 */
@Component("dcsReportStoreBridge")
public class ReportStoreBridge {

    /** Отчёт, разобранный в модель модуля. */
    public record Loaded(
            UUID id, String code, String name,
            DcsSchema schema,
            SettingsBundle settings,
            JsonNode templates,
            JsonNode forms,
            String dataSourceId) {}

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final ReportService reports;

    public ReportStoreBridge(ReportService reports) { this.reports = reports; }

    public Loaded load(UUID id) {
        Report r = reports.entity(id);
        return new Loaded(
                r.getId(), r.getCode(), r.getName(),
                parseSchema(r.getScheme()),
                parseSettings(r.getSettings()),
                r.getTemplates(), r.getForms(),
                r.getDataSourceId() == null || r.getDataSourceId().isBlank() ? "main" : r.getDataSourceId());
    }

    public DcsSchema parseSchema(JsonNode node) {
        if (node == null || node.isNull()) return new DcsSchema();
        return MAPPER.convertValue(node, DcsSchema.class);
    }

    /**
     * Реквизит настроек может быть как конвертом {@code {default, variants}}, так и
     * самими настройками: ранние схемы и ручная правка JSON приносят второй вид.
     * Отличаем по наличию {@code structure} на верхнем уровне — принимать оба дешевле,
     * чем заставлять пользователя мигрировать данные руками.
     */
    public SettingsBundle parseSettings(JsonNode node) {
        if (node == null || node.isNull()) return new SettingsBundle();
        if (node.has("defaultSettings") || node.has("variants")) {
            return MAPPER.convertValue(node, SettingsBundle.class);
        }
        SettingsBundle bundle = new SettingsBundle();
        bundle.defaultSettings = MAPPER.convertValue(node, DcsSettings.class);
        return bundle;
    }

    public DcsSettings parseUserSettings(JsonNode node) {
        if (node == null || node.isNull()) return null;
        return MAPPER.convertValue(node, DcsSettings.class);
    }

    public ObjectMapper mapper() { return MAPPER; }
}
