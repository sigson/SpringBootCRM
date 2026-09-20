package app.springbootcrm.validation;

import app.springbootcrm.metadata.TypeRegistry;
import app.springbootcrm.metadata.TypeRegistry.TypeDescriptor;
import domain.core.validation.FieldConstraint;
import domain.core.validation.FieldResult;
import domain.core.validation.FieldValidationRegistry;
import domain.core.validation.ValidationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * <h2>REST интерактивной/полной валидации реквизитов.</h2>
 *
 * <p>Единая точка для фронтенда. Тип адресуется по {@code slug} (как в
 * {@code /api/references/{slug}/…}); сервер резолвит его в {@code typeId} и
 * прогоняет ограничения из {@link FieldValidationRegistry}.
 *
 * <ul>
 *   <li>{@code POST /api/validation/{slug}/field} — проверить один реквизит
 *       (фронтенд вызывает по дебаунсу при вводе). Ответ — {@link FieldResult}
 *       с {@code meta} (min/max/maxLength…) для оверлей-панели над полем;</li>
 *   <li>{@code POST /api/validation/{slug}/object} — полная проверка всех
 *       реквизитов (то же, что выполняется на сервере при сохранении);</li>
 *   <li>{@code GET /api/validation/{slug}/constraints} — карта
 *       {@code поле -> meta} для предварительных подсказок UI.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/validation")
public class ValidationController {

    private final TypeRegistry registry;
    private final ValidationService validation;
    private final FieldValidationRegistry constraints;

    public ValidationController(TypeRegistry registry,
                                ValidationService validation,
                                FieldValidationRegistry constraints) {
        this.registry = registry;
        this.validation = validation;
        this.constraints = constraints;
    }

    /** Тело запроса проверки одного реквизита. */
    public record FieldRequest(String field, Object value) {}

    @PostMapping("/{slug}/field")
    public FieldResult validateField(@PathVariable String slug,
                                     @RequestBody FieldRequest req) {
        long typeId = typeIdOf(slug);
        return validation.validateField(typeId, req.field(), req.value());
    }

    @PostMapping("/{slug}/object")
    public FieldResult.ObjectResult validateObject(@PathVariable String slug,
                                                   @RequestBody Map<String, Object> values) {
        long typeId = typeIdOf(slug);
        return validation.validateObject(typeId, values);
    }

    /** Метаданные ограничений типа: {@code поле -> {min,max,maxLength,...}}. */
    @GetMapping("/{slug}/constraints")
    public Map<String, Map<String, Object>> constraints(@PathVariable String slug) {
        long typeId = typeIdOf(slug);
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<FieldConstraint>> e : constraints.forType(typeId).entrySet()) {
            Map<String, Object> meta = new LinkedHashMap<>();
            for (FieldConstraint c : e.getValue()) meta.putAll(c.meta());
            out.put(e.getKey(), meta);
        }
        return out;
    }

    private long typeIdOf(String slug) {
        TypeDescriptor td = registry.bySlug(slug);
        if (td == null) throw new NoSuchElementException("Unknown type: " + slug);
        return td.typeId();
    }
}
