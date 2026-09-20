package app.springbootcrm.catalogs.customer;

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
 * REST API справочника «Техническое состояние».
 *
 * <p>Собственных {@code @ExceptionHandler}'ов НЕТ — обработка всех исключений
 * вынесена в глобальный {@code domain.core.web.ErrorEnvelopeAdvice}, который
 * отдаёт клиенту унифицированный {@code ErrorEnvelope} (единый формат ошибок
 * доступа и точные валидационные ошибки).
 */
@RestController
@RequestMapping("/api/customers")
public class CustomerController {

    private final CustomerService service;

    public CustomerController(CustomerService service) {
        this.service = service;
    }

    @GetMapping
    public List<CustomerDto> list() { return service.list(); }

    @GetMapping("/{id}")
    public CustomerDto get(@PathVariable UUID id) { return service.get(id); }

    @PostMapping
    public ResponseEntity<CustomerDto> create(
            @RequestBody CustomerService.MutationRequest req) {
        CustomerDto dto = service.create(req);
        return ResponseEntity.status(HttpStatus.CREATED).body(dto);
    }

    @PutMapping("/{id}")
    public CustomerDto update(
            @PathVariable UUID id,
            @RequestBody CustomerService.MutationRequest req) {
        return service.update(id, req);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
