package app.springbootcrm.catalogs.customer;

import app.springbootcrm.reference.Reference;

import app.springbootcrm.reference.CodeGenerator;
import app.springbootcrm.user.User;
import app.springbootcrm.user.UserRepository;
import domain.core.web.ValidationFailedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * CRUD-сервис {@link Customer} с валидацией.
 *
 * <p>{@code code} принимается из запроса (пользователь мог отредактировать его на
 * форме), а если пустой — генерируется через {@link CodeGenerator}; уникальность
 * проверяется всегда. Ошибки валидации — через {@link ValidationFailedException}.
 */
@Service
public class CustomerService {

    // ---- Регулярки валидации ----

    static final Pattern ALLOWED_CHARS = Pattern.compile(
            "^[\\p{IsLatin}\\p{IsCyrillic}0-9 .,'_+*()/№\\-]+$");

    static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    static final String ALLOWED_CHARS_MSG =
            "May contain only letters, digits and the symbols . , ' _ - + * ( ) /";

    private final CustomerRepository repo;
    private final UserRepository users;
    private final CodeGenerator codeGen;

    public CustomerService(CustomerRepository repo,
                                     UserRepository users,
                                     CodeGenerator codeGen) {
        this.repo = repo;
        this.users = users;
        this.codeGen = codeGen;
    }

    // ============ READ ============

    @Transactional(readOnly = true)
    public List<CustomerDto> list() {
        var entries = repo.findAllSorted();
        Map<UUID, String> usernamesByAuthor = resolveAuthorUsernames(entries);
        return entries.stream()
                .map(tc -> CustomerDto.of(tc,
                        usernamesByAuthor.getOrDefault(extractAuthorId(tc), authorFallback(tc))))
                .toList();
    }

    @Transactional(readOnly = true)
    public CustomerDto get(UUID id) {
        Customer tc = repo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Record not found: " + id));
        UUID authorId = extractAuthorId(tc);
        String username = authorId == null ? authorFallback(tc)
                : users.findById(authorId).map(User::getUsername).orElse("");
        return CustomerDto.of(tc, username);
    }

    // ============ WRITE — защищены ядром через PreInsert/Update/DeleteListener ============

    @Transactional
    public CustomerDto create(MutationRequest req) {
        String code = resolveCodeForCreate(req.code());
        validateName(req.name());
        validateContacts(req);
        Customer tc = new Customer(
                UUID.randomUUID(),
                code,
                req.name(),
                req.email(),
                req.phone(),
                req.city(),
                req.notes());
        Customer saved = repo.save(tc);
        return get(saved.getId());
    }

    @Transactional
    public CustomerDto update(UUID id, MutationRequest req) {
        Customer tc = repo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Record not found: " + id));
        if (req.code() != null && !req.code().equals(tc.getCode())) {
            throw ValidationFailedException.ofField("code",
                    "The record code cannot be changed after creation");
        }
        validateName(req.name());
        validateContacts(req);
        tc.setName(req.name());
        tc.setEmail(req.email());
        tc.setPhone(req.phone());
        tc.setCity(req.city());
        tc.setNotes(req.notes());
        repo.save(tc);
        return get(id);
    }

    @Transactional
    public void delete(UUID id) {
        if (!repo.existsById(id)) {
            throw new NoSuchElementException("Record not found: " + id);
        }
        repo.deleteById(id);
    }

    // ============ Validation ============

    /**
     * Вычисляет код для новой записи:
     * <ul>
     *   <li>если пользователь подставил свой code — принимаем (проверяем только
     *       уникальность и базовый формат);</li>
     *   <li>иначе генерируем через {@link CodeGenerator} (с префиксом «ТС» по
     *       {@code @Reference} на сущности).</li>
     * </ul>
     */
    private String resolveCodeForCreate(String userSupplied) {
        if (userSupplied != null && !userSupplied.isBlank()) {
            String trimmed = userSupplied.trim();
            if (trimmed.length() > 50) {
                throw ValidationFailedException.ofField("code",
                        "Code must not exceed 50 characters");
            }
            if (repo.findByCode(trimmed).isPresent()) {
                throw ValidationFailedException.ofField("code",
                        "A record with this code already exists");
            }
            return trimmed;
        }
        // Auto-generate: счётчик инкрементируется гарантированно, даже если
        // создание в итоге провалится (REQUIRES_NEW в bumpAndGet).
        return codeGen.nextFor(Customer.class,
                candidate -> repo.findByCode(candidate).isEmpty());
    }

    void validateName(String name) {
        if (name == null || name.isBlank()) {
            throw ValidationFailedException.ofField("name", "This field is required");
        }
        if (!ALLOWED_CHARS.matcher(name).matches()) {
            throw ValidationFailedException.ofField("name", ALLOWED_CHARS_MSG);
        }
        if (name.length() > 200) {
            throw ValidationFailedException.ofField("name",
                    "Name must not exceed 200 characters");
        }
    }

    void validateContacts(MutationRequest req) {
        if (req.email() != null && !req.email().isBlank()) {
            if (req.email().length() > 160) {
                throw ValidationFailedException.ofField("email",
                        "Email must not exceed 160 characters");
            }
            if (!EMAIL.matcher(req.email().trim()).matches()) {
                throw ValidationFailedException.ofField("email", "Enter a valid email address");
            }
        }
        if (req.phone() != null && req.phone().length() > 40) {
            throw ValidationFailedException.ofField("phone",
                    "Phone must not exceed 40 characters");
        }
        if (req.city() != null && req.city().length() > 120) {
            throw ValidationFailedException.ofField("city",
                    "City must not exceed 120 characters");
        }
        if (req.notes() != null && req.notes().length() > 500) {
            throw ValidationFailedException.ofField("notes",
                    "Notes must not exceed 500 characters");
        }
    }

    // ============ Helpers ============

    private UUID extractAuthorId(Customer tc) {
        if (tc.getUpdatedBy() == null || tc.getUpdatedBy().targetIdRaw() == null) return null;
        try { return UUID.fromString(tc.getUpdatedBy().targetIdRaw()); }
        catch (IllegalArgumentException e) { return null; }
    }

    private String authorFallback(Customer tc) {
        if (tc.getUpdatedBy() != null
                && "__SYSTEM__".equals(tc.getUpdatedBy().targetIdRaw())) {
            return "SYSTEM";
        }
        return "";
    }

    private Map<UUID, String> resolveAuthorUsernames(List<Customer> entries) {
        var authorIds = entries.stream()
                .map(this::extractAuthorId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        if (authorIds.isEmpty()) return new HashMap<>();
        Map<UUID, String> out = new HashMap<>();
        for (User u : users.findAllById(authorIds)) {
            out.put(u.getId(), u.getUsername());
        }
        return out;
    }

    // ============ Types ============

    public record MutationRequest(String code, String name, String email,
                                  String phone, String city, String notes) {}
}
