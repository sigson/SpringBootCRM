package app.springbootcrm.catalogs.leadsource;

import app.springbootcrm.catalogs.leadsource.LeadSourceDto;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** Стандартный REST CRUD справочника типов ремонта ({@code /api/lead-sources}). */
@RestController
@RequestMapping("/api/lead-sources")
public class LeadSourceController {

    private final LeadSourceService service;

    public LeadSourceController(LeadSourceService service) { this.service = service; }

    @GetMapping public List<LeadSourceDto> list() { return service.list(); }

    @GetMapping("/{id}") public LeadSourceDto get(@PathVariable UUID id) { return service.get(id); }

    @PostMapping
    public ResponseEntity<LeadSourceDto> create(@RequestBody MutationRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(req.code(), req.name()));
    }

    @PutMapping("/{id}")
    public LeadSourceDto update(@PathVariable UUID id, @RequestBody MutationRequest req) {
        return service.update(id, req.name());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    public record MutationRequest(String code, String name) {}
}
