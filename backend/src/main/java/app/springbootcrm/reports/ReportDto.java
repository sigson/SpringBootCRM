package app.springbootcrm.reports;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/**
 * Public-проекция {@link Report}. JSON-реквизиты отдаются деревьями, а не строками, —
 * конструктор отчёта на фронтенде работает с ними напрямую.
 */
public record ReportDto(
        UUID id,
        String code,
        String name,
        JsonNode scheme,
        JsonNode settings,
        JsonNode templates,
        JsonNode forms,
        String dataSourceId,
        boolean enabled,
        Instant createdAt,
        String createdBy,
        String updatedBy
) {
    public static ReportDto of(Report r) {
        return new ReportDto(
                r.getId(), r.getCode(), r.getName(),
                r.getScheme(), r.getSettings(), r.getTemplates(), r.getForms(),
                r.getDataSourceId(), r.isEnabled(),
                r.getCreatedAt(),
                r.getCreatedBy() == null ? null : r.getCreatedBy().targetIdRaw(),
                r.getUpdatedBy() == null ? null : r.getUpdatedBy().targetIdRaw());
    }

    /** Короткая проекция для списков: без тяжёлых JSON-реквизитов. */
    public static ReportDto summaryOf(Report r) {
        return new ReportDto(
                r.getId(), r.getCode(), r.getName(),
                null, null, null, null,
                r.getDataSourceId(), r.isEnabled(),
                r.getCreatedAt(),
                r.getCreatedBy() == null ? null : r.getCreatedBy().targetIdRaw(),
                r.getUpdatedBy() == null ? null : r.getUpdatedBy().targetIdRaw());
    }
}
