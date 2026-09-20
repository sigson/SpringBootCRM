package app.springbootcrm.registers.userdiscount;

import app.springbootcrm.catalogs.discount.Discount;

import java.math.BigDecimal;
import java.util.UUID;

/** Public-проекция строки табличной части «Коэффициенты пользователя». */
public record UserDiscountDto(
        UUID id,
        UUID ownerId,
        UUID discountId,
        long discountTypeId,
        BigDecimal limitPercent
) {
    public static UserDiscountDto of(UserDiscount uc) {
        return new UserDiscountDto(
                uc.getId(),
                uc.getOwnerId(),
                uc.getDiscountId(),
                Discount.TYPE_ID,
                uc.getLimitPercent());
    }

    /** Запрос на создание/изменение строки ТЧ. */
    public record MutationRequest(
            UUID discountId,
            BigDecimal limitPercent) {}
}
