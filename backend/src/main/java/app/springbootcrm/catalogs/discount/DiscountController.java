package app.springbootcrm.catalogs.discount;

import app.springbootcrm.catalogs.discount.DiscountDto;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Стандартный REST CRUD справочника коэффициентов ({@code /api/discounts}). */
@RestController
@RequestMapping("/api/discounts")
public class DiscountController {

    private final DiscountService service;

    public DiscountController(DiscountService service) { this.service = service; }

    @GetMapping public List<DiscountDto> list() { return service.list(); }

    @GetMapping("/{id}") public DiscountDto get(@PathVariable UUID id) { return service.get(id); }

    @PostMapping
    public ResponseEntity<DiscountDto> create(@RequestBody MutationRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.create(req.code(), req.name(), req.value()));
    }

    @PutMapping("/{id}")
    public DiscountDto update(@PathVariable UUID id, @RequestBody MutationRequest req) {
        return service.update(id, req.name(), req.value());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    public record MutationRequest(String code, String name, BigDecimal value) {}
}
