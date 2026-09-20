package app.springbootcrm.documents.deal;

import app.springbootcrm.catalogs.customer.Customer;
import app.springbootcrm.catalogs.dealstage.DealStage;
import app.springbootcrm.catalogs.leadsource.LeadSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Instant;
import java.util.UUID;

/**
 * Deal projection. Catalog links are exposed as {@code <name>Id} + {@code <name>TypeId}
 * pairs — the same convention the tabular parts use — so the generic list can render a
 * REF column with display resolution and the generic editor can open a reference picker.
 */
public record DealDto(
        UUID id,
        String code,
        UUID leadSourceId,
        long leadSourceTypeId,
        UUID dealStageId,
        long dealStageTypeId,
        UUID customerId,
        long customerTypeId,
        BigDecimal amount,
        LocalDate expectedCloseDate,
        Instant createdAt
) {
    public static DealDto of(Deal deal) {
        return new DealDto(
                deal.getId(),
                deal.getCode(),
                deal.getLeadSourceId(),
                LeadSource.TYPE_ID,
                deal.getDealStageId(),
                DealStage.TYPE_ID,
                deal.getCustomerId(),
                Customer.TYPE_ID,
                deal.getAmount(),
                deal.getExpectedCloseDate(),
                deal.getCreatedAt());
    }
}
