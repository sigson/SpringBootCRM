package app.springbootcrm.registers.exchangerate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** Public-проекция записи регистра «Курс валют». */
public record ExchangeRateDto(
        UUID id,
        LocalDate rateDate,
        String currencyCode,
        BigDecimal rate
) {
    public static ExchangeRateDto of(ExchangeRate r) {
        return new ExchangeRateDto(r.getId(), r.getRateDate(), r.getCurrencyCode(), r.getRate());
    }

    /** Запрос на создание/изменение записи регистра. */
    public record MutationRequest(
            LocalDate rateDate,
            String currencyCode,
            BigDecimal rate) {}
}
