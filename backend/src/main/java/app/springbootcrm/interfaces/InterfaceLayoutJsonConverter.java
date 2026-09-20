package app.springbootcrm.interfaces;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.List;

/**
 * Портабельный JSON-конвертер дерева {@link LayoutNode} ↔ {@code VARCHAR}-колонка
 * {@code layout_json}.
 *
 * <p>Повторяет паттерн {@code AccessMetricJsonConverter}: {@code AttributeConverter}
 * с VARCHAR-сериализацией, портабельный между H2 и Postgres. В production с Postgres
 * можно заменить на нативный {@code @JdbcTypeCode(SqlTypes.JSON)} для GIN-индексации.
 *
 * <p>{@code ObjectMapper} stateless/thread-safe, держим как static; без Spring DI,
 * т.к. конвертер может создаваться JPA вне Spring-контекста.
 * {@code FAIL_ON_UNKNOWN_PROPERTIES=false} делает десериализацию forward-совместимой.
 */
@Converter(autoApply = false)
public class InterfaceLayoutJsonConverter
        implements AttributeConverter<List<LayoutNode>, String> {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final TypeReference<List<LayoutNode>> LIST_TYPE = new TypeReference<>() {};

    @Override
    public String convertToDatabaseColumn(List<LayoutNode> attribute) {
        if (attribute == null || attribute.isEmpty()) return null;
        try {
            return MAPPER.writeValueAsString(attribute);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize interface layout", e);
        }
    }

    @Override
    public List<LayoutNode> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) return List.of();
        try {
            return MAPPER.readValue(dbData, LIST_TYPE);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize interface layout: " + dbData, e);
        }
    }
}
