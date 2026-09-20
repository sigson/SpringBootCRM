package app.springbootcrm.reports;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * REST API справочника отчётов. Ошибки — через глобальный {@code ErrorEnvelopeAdvice},
 * права — через access-checked репозиторий.
 *
 * <p>Движок компоновки ({@code app.modules.dcs}) обращается к отчётам <b>не</b> сюда, а
 * в {@link ReportService} через свой мост, — HTTP-«петли» внутри одного приложения нет.
 */
@RestController
@RequestMapping("/api/reports")
public class ReportController {

    private final ReportService service;

    public ReportController(ReportService service) { this.service = service; }

    /** Список без JSON-реквизитов схемы/настроек/макетов/форм. */
    @GetMapping
    public List<ReportDto> list() { return service.list(); }

    /** Отчёт целиком — вход конструктора. */
    @GetMapping("/{id}")
    public ReportDto get(@PathVariable UUID id) { return service.get(id); }

    @PostMapping
    public ResponseEntity<ReportDto> create(@RequestBody ReportService.MutationRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(req));
    }

    @PutMapping("/{id}")
    public ReportDto update(@PathVariable UUID id, @RequestBody ReportService.MutationRequest req) {
        return service.update(id, req);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
