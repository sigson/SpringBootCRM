package app.springbootcrm.catalogs.customer;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * Проекция технического состояния для списка: код, наименование, автор, дата + описание.
 *
 * <p>{@code recordDate} форматируется как {@code ДД.ММ.ГГГГ}. {@code authorUsername} —
 * username пользователя, последним изменившего запись (берётся по цепочке updated_by → users).
 */
public record CustomerDto(
        UUID id,
        String code,
        String name,
        String email,
        String phone,
        String city,
        String notes,
        String recordDate,       // "dd.MM.yyyy"
        UUID authorId,
        String authorUsername,
        /* Дата створення (ISO Instant) — дані синтетичної колонки «Дата запису». */
        Instant createdAt,
        /* Raw audit-ссылки для скрытых колонок «Автор»/«Корректировка». */
        String createdBy,
        String updatedBy
) {

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("dd.MM.yyyy");

    public static CustomerDto of(Customer tc, String authorUsername) {
        Instant when = tc.getUpdatedAt() != null ? tc.getUpdatedAt() : tc.getCreatedAt();
        String recordDate = when == null ? "" :
                LocalDateTime.ofInstant(when, ZoneId.systemDefault()).format(DATE_FMT);

        UUID authorId = null;
        if (tc.getUpdatedBy() != null && tc.getUpdatedBy().targetIdRaw() != null) {
            try {
                authorId = UUID.fromString(tc.getUpdatedBy().targetIdRaw());
            } catch (IllegalArgumentException ignored) {
                // SYSTEM-маркер: оставляем null
            }
        }
        return new CustomerDto(
                tc.getId(),
                tc.getCode(),
                tc.getName(),
                tc.getEmail(),
                tc.getPhone(),
                tc.getCity(),
                tc.getNotes(),
                recordDate,
                authorId,
                authorUsername,
                tc.getCreatedAt(),
                tc.getCreatedBy() == null ? null : tc.getCreatedBy().targetIdRaw(),
                tc.getUpdatedBy() == null ? null : tc.getUpdatedBy().targetIdRaw()
        );
    }
}
