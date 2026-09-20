package app.springbootcrm.documents.activity;

import app.springbootcrm.common.AdvancedFilterParam;
import app.springbootcrm.common.PageResponse;

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
 * REST API «Календарь».
 * Исключения обрабатываются глобально через {@code ErrorEnvelopeAdvice}.
 */
@RestController
@RequestMapping("/api/activities")
public class ActivityController {

    private final ActivityService service;

    public ActivityController(ActivityService service) {
        this.service = service;
    }

    @GetMapping
    public List<ActivityDto> list() { return service.list(); }

    /**
     * Постраничная загрузка (chunked). Пример:
     * {@code GET /api/activities/page?page=0&size=100}.
     *
     * <p><b>Фильтрация/поиск/сортировка на сервере.</b> Поддерживаются те же
     * параметры, что и у {@code /api/references/{slug}/page}:
     * <ul>
     *   <li>{@code search} — подстрочный поиск по title/description/owner-display;</li>
     *   <li>{@code cf_<columnId>} — quick-фильтры по колонкам (substring);</li>
     *   <li>{@code af_<columnId>_<op>} — advanced-фильтры с операторами;</li>
     *   <li>{@code sortBy}/{@code sortDir} — серверная сортировка.</li>
     * </ul>
     * Полноценная фильтрация сосуществует с chunked-загрузкой.
     */
    @GetMapping("/page")
    public app.springbootcrm.common.PageResponse<ActivityDto> listPaged(
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "0") int page,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "100") int size,
            @org.springframework.web.bind.annotation.RequestParam(value = "search", required = false) String search,
            @org.springframework.web.bind.annotation.RequestParam(value = "sortBy", required = false) String sortBy,
            @org.springframework.web.bind.annotation.RequestParam(value = "sortDir", required = false) String sortDir,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "true") boolean count,
            @org.springframework.web.bind.annotation.RequestParam Map<String, String> allParams) {
        Map<String, String> columnFilters = new java.util.HashMap<>();
        java.util.List<app.springbootcrm.common.AdvancedFilterParam> advanced = new java.util.ArrayList<>();
        for (Map.Entry<String, String> e : allParams.entrySet()) {
            String k = e.getKey();
            if (k.startsWith("cf_")) {
                columnFilters.put(k.substring(3), e.getValue());
            } else if (k.startsWith("af_")) {
                // Формат: af_<columnId>_<op>=<value>  или  af_<columnId>_<op>=<v1>|<v2>|...
                String rest = k.substring(3);
                int sep = rest.lastIndexOf('_');
                if (sep <= 0) continue;
                String columnId = rest.substring(0, sep);
                String op = rest.substring(sep + 1);
                advanced.add(new app.springbootcrm.common.AdvancedFilterParam(columnId, op, e.getValue()));
            }
        }
        return service.listPaged(page, size, search, columnFilters, advanced, sortBy, sortDir, count);
    }

    @GetMapping("/{id}")
    public ActivityDto get(@PathVariable UUID id) { return service.get(id); }

    @PostMapping
    public ResponseEntity<ActivityDto> create(
            @RequestBody ActivityService.MutationRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(req));
    }

    @PutMapping("/{id}")
    public ActivityDto update(@PathVariable UUID id,
                                   @RequestBody ActivityService.MutationRequest req) {
        return service.update(id, req);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
