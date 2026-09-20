package app.springbootcrm.reports;

import app.springbootcrm.reference.CodeGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import domain.core.web.ValidationFailedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * CRUD-сервис справочника отчётов {@link Report}.
 *
 * <p>Прав не проверяет самостоятельно: репозиторий помечен {@code @AccessChecked(strict)},
 * поэтому чтение/запись отсекает ядро по гранту на typeId 9500 — как у обычных
 * справочников ({@code Product}, {@code Customer}), а не как у admin-only
 * {@code InterfaceLayout}. Аналитик с правом «ведение отчётов» правит схемы, не будучи
 * администратором.
 *
 * <p>JSON-реквизиты обновляются по принципу «null = не трогать»: конструктор на
 * фронтенде сохраняет вкладки по отдельности (схему, настройки, макеты, формы) и не
 * обязан присылать всё дерево целиком. Чтобы реквизит очистить, шлётся JSON-null
 * (узел {@code NullNode}), который здесь отличим от отсутствующего поля.
 */
@Service
public class ReportService {

    private final ReportRepository repo;
    private final CodeGenerator codeGen;

    public ReportService(ReportRepository repo, CodeGenerator codeGen) {
        this.repo = repo;
        this.codeGen = codeGen;
    }

    // ---------------------------------------------------------------- read

    /** Список без тяжёлых JSON-реквизитов. */
    @Transactional(readOnly = true)
    public List<ReportDto> list() {
        return repo.findAllSorted().stream().map(ReportDto::summaryOf).toList();
    }

    @Transactional(readOnly = true)
    public ReportDto get(UUID id) {
        return ReportDto.of(find(id));
    }

    /** Отчёт вместе со схемой — вход движка компоновки. */
    @Transactional(readOnly = true)
    public Report entity(UUID id) {
        return find(id);
    }

    // --------------------------------------------------------------- write

    @Transactional
    public ReportDto create(MutationRequest req) {
        String name = requireName(req.name());
        Report r = new Report(UUID.randomUUID(), resolveCodeForCreate(req.code()), name);
        apply(r, req);
        return ReportDto.of(repo.save(r));
    }

    @Transactional
    public ReportDto update(UUID id, MutationRequest req) {
        Report r = find(id);
        if (req.code() != null && !req.code().isBlank() && !req.code().equals(r.getCode())) {
            throw ValidationFailedException.ofField("code",
                    "The report code cannot be changed after creation");
        }
        if (req.name() != null) r.setName(requireName(req.name()));
        apply(r, req);
        return ReportDto.of(repo.save(r));
    }

    @Transactional
    public void delete(UUID id) {
        if (!repo.existsById(id)) throw new NoSuchElementException("Report not found: " + id);
        repo.deleteById(id);
    }

    // ------------------------------------------------------------ internals

    /** Присваивает только присланные реквизиты; отсутствующие остаются как были. */
    private void apply(Report r, MutationRequest req) {
        if (req.scheme() != null)    r.setScheme(nullIfJsonNull(req.scheme()));
        if (req.settings() != null)  r.setSettings(nullIfJsonNull(req.settings()));
        if (req.templates() != null) r.setTemplates(nullIfJsonNull(req.templates()));
        if (req.forms() != null)     r.setForms(nullIfJsonNull(req.forms()));
        if (req.dataSourceId() != null) {
            String ds = req.dataSourceId().trim();
            if (ds.length() > 100) {
                throw ValidationFailedException.ofField("dataSourceId", "Data source id: up to 100 characters");
            }
            r.setDataSourceId(ds.isEmpty() ? "main" : ds);
        }
        if (req.enabled() != null) r.setEnabled(req.enabled());
    }

    private static JsonNode nullIfJsonNull(JsonNode n) {
        return n == null || n.isNull() ? null : n;
    }

    private String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw ValidationFailedException.ofField("name", "Name is required");
        }
        String trimmed = name.trim();
        if (trimmed.length() > 200) {
            throw ValidationFailedException.ofField("name", "Name: up to 200 characters");
        }
        return trimmed;
    }

    private Report find(UUID id) {
        return repo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Report not found: " + id));
    }

    private String resolveCodeForCreate(String userSupplied) {
        if (userSupplied != null && !userSupplied.isBlank()) {
            String trimmed = userSupplied.trim();
            if (trimmed.length() > 50) {
                throw ValidationFailedException.ofField("code", "Code: up to 50 characters");
            }
            if (repo.findByCode(trimmed).isPresent()) {
                throw ValidationFailedException.ofField("code", "A report with this code already exists");
            }
            return trimmed;
        }
        return codeGen.nextFor(Report.class, c -> repo.findByCode(c).isEmpty());
    }

    /**
     * Единый запрос на создание и изменение. {@code null} у любого реквизита означает
     * «не менять» — частичное сохранение вкладок конструктора.
     */
    public record MutationRequest(
            String code,
            String name,
            JsonNode scheme,
            JsonNode settings,
            JsonNode templates,
            JsonNode forms,
            String dataSourceId,
            Boolean enabled) {}
}
