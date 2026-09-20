package app.modules.dcs.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;

/**
 * Содержимое реквизита {@code settings} отчёта: настройки по умолчанию плюс
 * именованные <b>варианты отчёта</b>.
 *
 * <p>Вариант — это сохранённый набор настроек под своим именем («Продажи по менеджерам»,
 * «То же с детализацией по дням»). Держать варианты рядом с настройками по умолчанию, а
 * не отдельным справочником, правильно потому, что вариант бессмыслен в отрыве от схемы:
 * он ссылается на её поля и умирает вместе с ней.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class SettingsBundle {

    /** Настройки по умолчанию. */
    public DcsSettings defaultSettings = new DcsSettings();

    /** Именованные варианты. */
    public List<Variant> variants = new ArrayList<>();

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Variant {
        public String id;
        public String name;
        public DcsSettings settings;
    }

    public Variant variant(String id) {
        if (id == null || variants == null) return null;
        return variants.stream().filter(v -> v != null && id.equals(v.id)).findFirst().orElse(null);
    }
}
