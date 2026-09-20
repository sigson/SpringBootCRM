package app.springbootcrm.documents.deal;

import app.springbootcrm.documents.deal.DealDto;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Стандартный REST CRUD ремонтных норм ({@code /api/deals}).
 *
 * <p>Тело запроса использует конвенцию generic-редактора для ссылок: моно-REF
 * передаётся ключом {@code <name>Id} ({@code leadSourceId}, {@code dealStageId}).
 * Обработка ошибок — глобальным {@code ErrorEnvelopeAdvice}.
 */
@RestController
@RequestMapping("/api/deals")
public class DealController {

    private final DealService service;

    public DealController(DealService service) { this.service = service; }

    @GetMapping public List<DealDto> list() { return service.list(); }

    @GetMapping("/{id}") public DealDto get(@PathVariable UUID id) { return service.get(id); }

    @PostMapping
    public ResponseEntity<DealDto> create(@RequestBody MutationRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(
                service.create(req.code(), req.leadSourceId(), req.dealStageId(),
                        req.customerId(), req.amount(), req.expectedCloseDate()));
    }

    @PutMapping("/{id}")
    public DealDto update(@PathVariable UUID id, @RequestBody MutationRequest req) {
        return service.update(id, req.leadSourceId(), req.dealStageId(),
                req.customerId(), req.amount(), req.expectedCloseDate());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    public record MutationRequest(
            String code,
            UUID leadSourceId,
            UUID dealStageId,
            UUID customerId,
            BigDecimal amount,
            java.time.LocalDate expectedCloseDate) {}
}
