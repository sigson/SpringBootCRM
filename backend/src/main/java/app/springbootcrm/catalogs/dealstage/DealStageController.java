package app.springbootcrm.catalogs.dealstage;

import app.springbootcrm.catalogs.dealstage.DealStageDto;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Стандартный REST CRUD справочника почасовых ставок ({@code /api/deal-stages}). */
@RestController
@RequestMapping("/api/deal-stages")
public class DealStageController {

    private final DealStageService service;

    public DealStageController(DealStageService service) { this.service = service; }

    @GetMapping public List<DealStageDto> list() { return service.list(); }

    @GetMapping("/{id}") public DealStageDto get(@PathVariable UUID id) { return service.get(id); }

    @PostMapping
    public ResponseEntity<DealStageDto> create(@RequestBody MutationRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.create(req.code(), req.name(), req.probability()));
    }

    @PutMapping("/{id}")
    public DealStageDto update(@PathVariable UUID id, @RequestBody MutationRequest req) {
        return service.update(id, req.name(), req.probability());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    public record MutationRequest(String code, String name, BigDecimal probability) {}
}
