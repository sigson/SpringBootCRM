package app.springbootcrm.interfaces;

import app.springbootcrm.access.AccessRole;
import app.springbootcrm.navigation.NavigationService;

import app.springbootcrm.auth.AdminCheck;
import app.springbootcrm.reference.CodeGenerator;
import domain.core.web.ValidationFailedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * CRUD-сервис справочника {@link InterfaceLayout}. Создание/редактирование/удаление —
 * только admin (как и {@code AccessRole}). Чтение — всем аутентифицированным (репо
 * {@code READ_ONLY}), чтобы {@code NavigationService} мог прочитать назначенный интерфейс.
 */
@Service
public class InterfaceLayoutService {

    private final InterfaceLayoutRepository repo;
    private final AdminCheck admin;
    private final CodeGenerator codeGen;

    public InterfaceLayoutService(InterfaceLayoutRepository repo,
                                  AdminCheck admin,
                                  CodeGenerator codeGen) {
        this.repo = repo;
        this.admin = admin;
        this.codeGen = codeGen;
    }

    @Transactional(readOnly = true)
    public List<InterfaceLayoutDto> list() {
        return repo.findAll().stream().map(InterfaceLayoutDto::of).toList();
    }

    @Transactional(readOnly = true)
    public InterfaceLayoutDto get(UUID id) {
        return InterfaceLayoutDto.of(find(id));
    }

    @Transactional
    public InterfaceLayoutDto create(CreateRequest req) {
        admin.requireAdmin();
        if (req.name() == null || req.name().isBlank()) {
            throw ValidationFailedException.ofField("name", "Name is required");
        }
        String code = resolveCode(req.code());
        InterfaceLayout i = new InterfaceLayout(
                UUID.randomUUID(), code, req.name(),
                req.layout(), req.enabled());
        return InterfaceLayoutDto.of(repo.save(i));
    }

    @Transactional
    public InterfaceLayoutDto update(UUID id, UpdateRequest req) {
        admin.requireAdmin();
        InterfaceLayout i = find(id);
        if (req.name() != null) {
            if (req.name().isBlank()) {
                throw ValidationFailedException.ofField("name", "Name is required");
            }
            i.setName(req.name());
        }
        if (req.layout() != null) i.setLayout(req.layout());
        if (req.enabled() != null) i.setEnabled(req.enabled());
        return InterfaceLayoutDto.of(repo.save(i));
    }

    @Transactional
    public void delete(UUID id) {
        admin.requireAdmin();
        if (!repo.existsById(id)) {
            throw new NoSuchElementException("Interface not found: " + id);
        }
        // Пользователи, которым назначен этот интерфейс, после удаления автоматически
        // получат дефолтный режим (NavigationService делает fallback, если ссылка не
        // резолвится). Специальной чистки ссылки не требуется.
        repo.deleteById(id);
    }

    // -------- internals --------

    private InterfaceLayout find(UUID id) {
        return repo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Interface not found: " + id));
    }

    private String resolveCode(String userSupplied) {
        if (userSupplied != null && !userSupplied.isBlank()) {
            String trimmed = userSupplied.trim();
            if (trimmed.length() > 50) {
                throw ValidationFailedException.ofField("code", "Code: up to 50 characters");
            }
            if (repo.findByCode(trimmed).isPresent()) {
                throw ValidationFailedException.ofField("code", "An interface with this code already exists");
            }
            return trimmed;
        }
        return codeGen.nextFor(InterfaceLayout.class, c -> repo.findByCode(c).isEmpty());
    }

    // -------- Request records --------

    public record CreateRequest(
            String code,
            String name,
            List<LayoutNode> layout,
            boolean enabled) {}

    public record UpdateRequest(
            String name,
            List<LayoutNode> layout,
            Boolean enabled) {}
}
