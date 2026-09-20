package app.springbootcrm.access;

import jakarta.validation.Valid;
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
import java.util.Map;
import java.util.UUID;

/**
 * REST API управления {@link AccessRole}.
 * Обработка исключений — в глобальном {@code ErrorEnvelopeAdvice}.
 */
@RestController
@RequestMapping("/api/access-roles")
public class AccessRoleController {

    private final AccessRoleService service;

    public AccessRoleController(AccessRoleService service) {
        this.service = service;
    }

    @GetMapping
    public List<AccessRoleDto> list() { return service.list(); }

    @GetMapping("/{id}")
    public AccessRoleDto get(@PathVariable UUID id) { return service.get(id); }

    @PostMapping
    public ResponseEntity<AccessRoleDto> create(@Valid @RequestBody CreateRequest req) {
        AccessRoleDto dto = service.create(new AccessRoleService.CreateRoleRequest(
                req.code, req.name, req.description, req.accessTemplate, req.enabled));
        return ResponseEntity.status(HttpStatus.CREATED).body(dto);
    }

    @PutMapping("/{id}")
    public AccessRoleDto update(@PathVariable UUID id, @Valid @RequestBody UpdateRequest req) {
        return service.update(id, new AccessRoleService.UpdateRoleRequest(
                req.name, req.description, req.accessTemplate, req.enabled));
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
            @Size(max = 500) String description,
            Map<String, Object> accessTemplate,
            boolean enabled) {}

    public record UpdateRequest(
            @Size(max = 200, message = "Name: up to 200 characters") String name,
            @Size(max = 500) String description,
            Map<String, Object> accessTemplate,
            Boolean enabled) {}
}
