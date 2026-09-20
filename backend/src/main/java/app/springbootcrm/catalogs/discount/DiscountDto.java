package app.springbootcrm.catalogs.discount;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Discount projection: a named percentage that can be granted to a user. */
public record DiscountDto(UUID id, String code, String name,
                          BigDecimal value, Instant createdAt) {

    public static DiscountDto of(Discount discount) {
        return new DiscountDto(discount.getId(), discount.getCode(), discount.getName(),
                discount.getValue(), discount.getCreatedAt());
    }
}
