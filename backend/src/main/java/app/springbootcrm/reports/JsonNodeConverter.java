package app.springbootcrm.reports;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * JSON-реквизит ↔ {@code VARCHAR}-колонка, портабельно между H2 и PostgreSQL.
 *
 * <p>Тот же приём, что у {@code InterfaceLayoutJsonConverter}, но без привязки к
 * конкретной Java-модели: хранится «сырое» дерево {@link JsonNode}. Это осознанное
 * решение — {@link Report} держит схему компоновки, макеты и формы, чьи <b>структуры
 * принадлежат не хосту, а модулю компоновки</b> ({@code app.modules.dcs}) и его
 * конструктору на фронтенде. Хост остаётся хранилищем: модель компоновки может
 * развиваться без миграций и правок справочника, а при удалении модуля справочник
 * продолжает работать как обычный JSON-каталог.
 *
 * <p>{@code ObjectMapper} stateless/thread-safe, поэтому static: конвертер может
 * создаваться JPA вне Spring-контекста.
 */
@Converter(autoApply = false)
public class JsonNodeConverter implements AttributeConverter<JsonNode, String> {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Override
    public String convertToDatabaseColumn(JsonNode attribute) {
        if (attribute == null || attribute.isNull() || attribute.isEmpty()) return null;
        try {
            return MAPPER.writeValueAsString(attribute);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize the report JSON attribute", e);
        }
    }

    @Override
    public JsonNode convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) return null;
        try {
            return MAPPER.readTree(dbData);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize the report JSON attribute", e);
        }
    }
}
