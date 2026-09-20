package app.springbootcrm.registers.exchangerate;

import domain.core.web.ValidationFailedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * CRUD-сервис регистра «Курс валют».
 *
 * <p>Разграничение доступа целиком обеспечивается ядром: repo/field-level
 * access-listener'ы проверяют флаги пользователя на {@code typeId=9202}
 * (как и для любого агрегата) и бросают {@code StructuredAccessDeniedException},
 * который превращается в единый error-envelope. Поэтому здесь — только
 * предметная валидация.
 */
@Service
public class ExchangeRateService {

    private final ExchangeRateRepository repo;

    public ExchangeRateService(ExchangeRateRepository repo) {
        this.repo = repo;
    }

    @Transactional(readOnly = true)
    public List<ExchangeRateDto> list() {
        return repo.findAllSorted().stream().map(ExchangeRateDto::of).toList();
    }

    @Transactional(readOnly = true)
    public ExchangeRateDto get(UUID id) {
        return ExchangeRateDto.of(repo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Exchange rate not found: " + id)));
    }

    @Transactional
    public ExchangeRateDto create(ExchangeRateDto.MutationRequest req) {
        validate(req);
        ExchangeRate r = new ExchangeRate(UUID.randomUUID(),
                req.rateDate(), req.currencyCode().trim().toUpperCase(), req.rate());
        return ExchangeRateDto.of(repo.save(r));
    }

    @Transactional
    public ExchangeRateDto update(UUID id, ExchangeRateDto.MutationRequest req) {
        validate(req);
        ExchangeRate r = repo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Exchange rate not found: " + id));
        r.setRateDate(req.rateDate());
        r.setCurrencyCode(req.currencyCode().trim().toUpperCase());
        r.setRate(req.rate());
        return ExchangeRateDto.of(repo.save(r));
    }

    @Transactional
    public void delete(UUID id) {
        if (!repo.existsById(id)) {
            throw new NoSuchElementException("Exchange rate not found: " + id);
        }
        repo.deleteById(id);
    }

    private void validate(ExchangeRateDto.MutationRequest req) {
        if (req.rateDate() == null) {
            throw ValidationFailedException.ofField("rateDate", "Date is required");
        }
        if (req.currencyCode() == null || req.currencyCode().isBlank()) {
            throw ValidationFailedException.ofField("currencyCode", "Currency is required");
        }
        if (req.currencyCode().length() > 10) {
            throw ValidationFailedException.ofField("currencyCode",
                    "Currency code must not exceed 10 characters");
        }
        if (req.rate() == null) {
            throw ValidationFailedException.ofField("rate", "Rate is required");
        }
        if (req.rate().signum() < 0) {
            throw ValidationFailedException.ofField("rate", "Rate cannot be negative");
        }
    }
}
