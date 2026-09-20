package app.springbootcrm.catalogs.product;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * Проекция типа оборудования для списка TER01D02: код, наименование, автор, дата.
 *
 * <p>{@code recordDate} форматируется как {@code ДД.ММ.ГГГГ}.
 * {@code authorUsername} — username последнего изменившего запись (цепочка updated_by → users).
 * {@code createdAt} — данные синтетической колонки «Дата запису» (ISO Instant);
 * {@code createdBy}/{@code updatedBy} — raw-ссылки для скрытых колонок «Автор»/«Корректировка».
 */
public record ProductDto(
        UUID id,
        String code,
        String name,
        String sku,
        java.math.BigDecimal unitPrice,
        String recordDate,       // "dd.MM.yyyy"
        UUID authorId,
        String authorUsername,
        Instant createdAt,
        String createdBy,
        String updatedBy
) {

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("dd.MM.yyyy");

    public static ProductDto of(Product e, String authorUsername) {
        Instant when = e.getUpdatedAt() != null ? e.getUpdatedAt() : e.getCreatedAt();
        String recordDate = when == null ? "" :
                LocalDateTime.ofInstant(when, ZoneId.systemDefault()).format(DATE_FMT);

        UUID authorId = null;
        if (e.getUpdatedBy() != null && e.getUpdatedBy().targetIdRaw() != null) {
            try {
                authorId = UUID.fromString(e.getUpdatedBy().targetIdRaw());
            } catch (IllegalArgumentException ignored) {
                // SYSTEM-маркер: оставляем null
            }
        }
        return new ProductDto(
                e.getId(),
                e.getCode(),
                e.getName(),
                e.getSku(),
                e.getUnitPrice(),
                recordDate,
                authorId,
                authorUsername,
                e.getCreatedAt(),
                e.getCreatedBy() == null ? null : e.getCreatedBy().targetIdRaw(),
                e.getUpdatedBy() == null ? null : e.getUpdatedBy().targetIdRaw()
        );
    }
}
