package app.springbootcrm.catalogs.product;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * REST API справочника «Типи технологічного обладнання» (TER01D02, {@code /api/products}).
 *
 * <p>Обработка исключений — глобальным {@code domain.core.web.ErrorEnvelopeAdvice}.
 */
@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final ProductService service;

    public ProductController(ProductService service) { this.service = service; }

    @GetMapping
    public List<ProductDto> list() { return service.list(); }

    @GetMapping("/{id}")
    public ProductDto get(@PathVariable UUID id) { return service.get(id); }

    @PostMapping
    public ResponseEntity<ProductDto> create(
            @RequestBody ProductService.MutationRequest req) {
        ProductDto dto = service.create(req);
        return ResponseEntity.status(HttpStatus.CREATED).body(dto);
    }

    @PutMapping("/{id}")
    public ProductDto update(
            @PathVariable UUID id,
            @RequestBody ProductService.MutationRequest req) {
        return service.update(id, req);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
