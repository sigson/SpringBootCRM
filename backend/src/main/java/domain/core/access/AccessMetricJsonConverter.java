package domain.core.access;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Портабельный JSON-конвертер для {@link AccessMetricPayload}.
 *
 * <p>В production-среде с Postgres рекомендуется заменить на нативный
 * {@code @JdbcTypeCode(SqlTypes.JSON)} — он даёт
 * более эффективную фильтрацию через GIN-индексы. Для портабельности (H2 + Postgres)
 * используем {@code AttributeConverter} с {@code VARCHAR}-сериализацией.
 *
 * <p>{@code ObjectMapper} здесь — без access-modifier'а (это {@code outboxObjectMapper}-семья):
 * payload пользователя должен сохраняться в полном виде, без masking'а. Но и без
 * Spring DI — конвертер может создаваться JPA вне Spring-контекста.
 */
@Converter(autoApply = false)
public class AccessMetricJsonConverter
        implements AttributeConverter<AccessMetricPayload, String> {

    /** Stateless и thread-safe — можно держать как static. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String convertToDatabaseColumn(AccessMetricPayload attribute) {
        if (attribute == null) return null;
        try {
            return MAPPER.writeValueAsString(attribute);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize AccessMetricPayload", e);
        }
    }

    @Override
    public AccessMetricPayload convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isEmpty()) return AccessMetricPayload.empty();
        try {
            return MAPPER.readValue(dbData, AccessMetricPayload.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize AccessMetricPayload: " + dbData, e);
        }
    }
}
