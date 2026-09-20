package app.springbootcrm.catalogs.leadsource;

import java.time.Instant;
import java.util.UUID;

/**
 * Lead source projection (read-only catalog).
 *
 * <p>{@code createdAt} is the synthetic audit column published by {@code TypeRegistry}
 * for every audited aggregate. It is serialized as an ISO instant and rendered by the
 * generic list; the edit form does not show it.
 */
public record LeadSourceDto(UUID id, String code, String name, Instant createdAt) {

    public static LeadSourceDto of(LeadSource source) {
        return new LeadSourceDto(source.getId(), source.getCode(),
                source.getName(), source.getCreatedAt());
    }
}
