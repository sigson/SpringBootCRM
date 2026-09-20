package app.springbootcrm.metadata;

import app.springbootcrm.metadata.TypeRegistry.TypeDescriptor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.NoSuchElementException;

/**
 * REST API метаданных доменных типов.
 *
 * <p><b>Ключевые эндпоинты:</b>
 * <ul>
 *   <li>{@code GET /api/metadata/types}        — список всех доменных типов-агрегатов;</li>
 *   <li>{@code GET /api/metadata/types/{slug}} — описание одного типа.</li>
 * </ul>
 *
 * <p>На основе этих метаданных UI динамически строит:
 * <ul>
 *   <li>список модулей на дашборде;</li>
 *   <li>URL'ы вида {@code /references/{slug}/{id}};</li>
 *   <li>конструктор ролей доступа — для каждого типа UI предлагает чекбоксы
 *       стандартизованных флагов (READ / WRITE / ADMIN_READ / ADMIN_WRITE).</li>
 * </ul>
 *
 * <p>Права выражаются исключительно через {@code typeFlags} в {@code AccessMetric} —
 * комбинацией битов {@link domain.core.access.AccessFlags}
 * (READ / WRITE_INSERT / WRITE_UPDATE / ADMIN_*).
 */
@RestController
@RequestMapping("/api/metadata")
public class MetadataController {

    private final TypeRegistry registry;
    private final MetadataGraphService graphService;

    public MetadataController(TypeRegistry registry, MetadataGraphService graphService) {
        this.registry = registry;
        this.graphService = graphService;
    }

    @GetMapping("/types")
    public List<TypeDescriptor> listTypes() {
        return registry.all();
    }

    @GetMapping("/types/{slug}")
    public TypeDescriptor byTypeSlug(@PathVariable("slug") String slug) {
        TypeDescriptor td = registry.bySlug(slug);
        if (td == null) {
            throw new NoSuchElementException("Unknown type: " + slug);
        }
        return td;
    }

    /**
     * Граф бизнес-объектов для ссылочного режима конструктора запросов:
     * типы → таблицы/колонки, ссылочные реквизиты с union-целями и физическими
     * колонками дискриминатора/FK. «Магия джойнов» строится на фронтенде.
     */
    @GetMapping("/graph")
    public MetadataGraphService.GraphResponse graph() {
        return graphService.graph();
    }
}
