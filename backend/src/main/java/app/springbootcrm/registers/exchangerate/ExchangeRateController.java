package app.springbootcrm.registers.exchangerate;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * REST API регистра «Курс валют». Ошибки доступа/валидации обрабатывает
 * глобальный {@code ErrorEnvelopeAdvice}.
 */
@RestController
@RequestMapping("/api/exchange-rates")
public class ExchangeRateController {

    private final ExchangeRateService service;

    public ExchangeRateController(ExchangeRateService service) {
        this.service = service;
    }

    @GetMapping
    public List<ExchangeRateDto> list() { return service.list(); }

    @GetMapping("/{id}")
    public ExchangeRateDto get(@PathVariable UUID id) { return service.get(id); }

    @PostMapping
    public ResponseEntity<ExchangeRateDto> create(
            @RequestBody ExchangeRateDto.MutationRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(req));
    }

    @PutMapping("/{id}")
    public ExchangeRateDto update(@PathVariable UUID id,
                                  @RequestBody ExchangeRateDto.MutationRequest req) {
        return service.update(id, req);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
