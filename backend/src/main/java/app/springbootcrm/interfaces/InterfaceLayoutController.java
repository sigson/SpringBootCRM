package app.springbootcrm.interfaces;

import jakarta.validation.constraints.Size;
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
 * REST API справочника {@link InterfaceLayout}. Ошибки — через глобальный
 * {@code ErrorEnvelopeAdvice}. Запись enforce'ится admin-проверкой в сервисе.
 */
@RestController
@RequestMapping("/api/interface-layouts")
public class InterfaceLayoutController {

    private final InterfaceLayoutService service;

    public InterfaceLayoutController(InterfaceLayoutService service) {
        this.service = service;
    }

    @GetMapping
    public List<InterfaceLayoutDto> list() { return service.list(); }

    @GetMapping("/{id}")
    public InterfaceLayoutDto get(@PathVariable UUID id) { return service.get(id); }

    @PostMapping
    public ResponseEntity<InterfaceLayoutDto> create(@RequestBody CreateRequest req) {
        InterfaceLayoutDto dto = service.create(new InterfaceLayoutService.CreateRequest(
                req.code(), req.name(), req.layout(), req.enabled()));
        return ResponseEntity.status(HttpStatus.CREATED).body(dto);
    }

    @PutMapping("/{id}")
    public InterfaceLayoutDto update(@PathVariable UUID id, @RequestBody UpdateRequest req) {
        return service.update(id, new InterfaceLayoutService.UpdateRequest(
                req.name(), req.layout(), req.enabled()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    // -------- DTO --------

    public record CreateRequest(
            @Size(max = 50) String code,
            @Size(max = 200, message = "Name: up to 200 characters") String name,
            List<LayoutNode> layout,
            boolean enabled) {}

    public record UpdateRequest(
            @Size(max = 200, message = "Name: up to 200 characters") String name,
            List<LayoutNode> layout,
            Boolean enabled) {}
}
