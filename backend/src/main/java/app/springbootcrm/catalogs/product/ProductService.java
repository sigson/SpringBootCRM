package app.springbootcrm.catalogs.product;

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
 * CRUD-сервис {@link Product} (typeId=4002).
 *
 * <p>Валидация:
 * <ul>
 *   <li><b>Код</b> — обязателен, уникален; принимается из формы или авто-генерируется
 *       через {@link CodeGenerator} (префикс «ОБ»).</li>
 *   <li><b>Найменування</b> — обязательно, уникально, допустимые символы (укр./лат.
 *       буквы, цифры, {@code . , ' _ ! - + ( ) / № *}), длина ≤ 20.</li>
 * </ul>
 *
 * <p>{@code RECORD_DATE}/{@code AUTHOR_ID} заполняются стандартным Spring Data Auditing
 * (createdAt/updatedAt, createdBy/updatedBy) — отдельной логики не требуется.
 */
@Service
public class ProductService {

    // Допустимые символы по TER01D02 (добавлен '!' к набору TER01D01).
    static final Pattern ALLOWED_CHARS = Pattern.compile(
            "^[\\p{IsLatin}\\p{IsCyrillic}0-9 .,'_!+*()/№\\-]+$");

    static final String ALLOWED_CHARS_MSG =
            "May contain only letters, digits and the symbols . , ' _ ! - + ( ) / *";

    static final int NAME_MAX = 20;

    private final ProductRepository repo;
    private final UserRepository users;
    private final CodeGenerator codeGen;

    public ProductService(ProductRepository repo,
                                UserRepository users,
                                CodeGenerator codeGen) {
        this.repo = repo;
        this.users = users;
        this.codeGen = codeGen;
    }

    // ============ READ ============

    @Transactional(readOnly = true)
    public List<ProductDto> list() {
        var entries = repo.findAllSorted();
        Map<UUID, String> usernamesByAuthor = resolveAuthorUsernames(entries);
        return entries.stream()
                .map(e -> ProductDto.of(e,
                        usernamesByAuthor.getOrDefault(extractAuthorId(e), authorFallback(e))))
                .toList();
    }

    @Transactional(readOnly = true)
    public ProductDto get(UUID id) {
        Product e = find(id);
        UUID authorId = extractAuthorId(e);
        String username = authorId == null ? authorFallback(e)
                : users.findById(authorId).map(User::getUsername).orElse("");
        return ProductDto.of(e, username);
    }

    // ============ WRITE ============

    @Transactional
    public ProductDto create(MutationRequest req) {
        String code = resolveCodeForCreate(req.code());
        validateName(req.name(), null);
        validateSku(req.sku());
        Product e = new Product(UUID.randomUUID(), code,
                req.name().trim(), req.sku(), req.unitPrice());
        Product saved = repo.save(e);
        return get(saved.getId());
    }

    @Transactional
    public ProductDto update(UUID id, MutationRequest req) {
        Product e = find(id);
        if (req.code() != null && !req.code().equals(e.getCode())) {
            throw ValidationFailedException.ofField("code",
                    "The record code cannot be changed after creation");
        }
        validateName(req.name(), id);
        validateSku(req.sku());
        e.setName(req.name().trim());
        e.setSku(req.sku());
        e.setUnitPrice(req.unitPrice());
        repo.save(e);
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
        return codeGen.nextFor(Product.class,
                candidate -> repo.findByCode(candidate).isEmpty());
    }

    /** Валидация наименования + контроль уникальности по NAME. */
    void validateName(String name, UUID selfId) {
        if (name == null || name.isBlank()) {
            throw ValidationFailedException.ofField("name", "This field is required");
        }
        String trimmed = name.trim();
        if (!ALLOWED_CHARS.matcher(trimmed).matches()) {
            throw ValidationFailedException.ofField("name", ALLOWED_CHARS_MSG);
        }
        if (trimmed.length() > NAME_MAX) {
            throw ValidationFailedException.ofField("name",
                    "Maximum length: " + NAME_MAX + " characters");
        }
        repo.findByName(trimmed).ifPresent(existing -> {
            if (selfId == null || !existing.getId().equals(selfId)) {
                throw ValidationFailedException.ofField("name",
                        "A record with this name already exists");
            }
        });
    }

    // ============ Helpers ============

    private Product find(UUID id) {
        return repo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Record not found: " + id));
    }

    private UUID extractAuthorId(Product e) {
        if (e.getUpdatedBy() == null || e.getUpdatedBy().targetIdRaw() == null) return null;
        try { return UUID.fromString(e.getUpdatedBy().targetIdRaw()); }
        catch (IllegalArgumentException ex) { return null; }
    }

    private String authorFallback(Product e) {
        if (e.getUpdatedBy() != null
                && "__SYSTEM__".equals(e.getUpdatedBy().targetIdRaw())) {
            return "SYSTEM";
        }
        return "";
    }

    private Map<UUID, String> resolveAuthorUsernames(List<Product> entries) {
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

    void validateSku(String sku) {
        if (sku != null && sku.length() > 40) {
            throw ValidationFailedException.ofField("sku",
                    "SKU must not exceed 40 characters");
        }
    }

    public record MutationRequest(String code, String name,
                                  String sku, java.math.BigDecimal unitPrice) {}
}
