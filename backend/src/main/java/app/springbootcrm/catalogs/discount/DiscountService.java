package app.springbootcrm.catalogs.discount;

import app.springbootcrm.reference.CodeGenerator;
import app.springbootcrm.catalogs.discount.DiscountDto;
import domain.core.validation.ValidationService;
import domain.core.web.ValidationFailedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

/** Стандартный CRUD-сервис справочника {@link Discount} (typeId=4103). */
@Service
public class DiscountService {

    private final DiscountRepository repo;
    private final CodeGenerator codeGen;
    private final ValidationService validation;

    public DiscountService(DiscountRepository repo, CodeGenerator codeGen,
                              ValidationService validation) {
        this.repo = repo;
        this.codeGen = codeGen;
        this.validation = validation;
    }

    @Transactional(readOnly = true)
    public List<DiscountDto> list() {
        return repo.findAllSorted().stream().map(DiscountDto::of).toList();
    }

    @Transactional(readOnly = true)
    public DiscountDto get(UUID id) { return DiscountDto.of(find(id)); }

    @Transactional
    public DiscountDto create(String code, String name, BigDecimal value) {
        // Сначала разрешаем код (принятый клиентский или авто-сгенерированный),
        // затем валидируем уже финальное состояние объекта единой системой
        // (бины-ограничения на полях Entity), а не россыпью ручных проверок.
        String resolved = resolveCode(code);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("code", resolved);
        values.put("name", name == null ? null : name.trim());
        values.put("value", value);
        validation.assertValid(Discount.TYPE_ID, values);

        Discount c = new Discount(UUID.randomUUID(), resolved, name.trim(), value);
        return DiscountDto.of(repo.save(c));
    }

    @Transactional
    public DiscountDto update(UUID id, String name, BigDecimal value) {
        Discount c = find(id);
        // Валидируем итоговое состояние объекта (новые значения там, где заданы,
        // иначе текущие) — тот же набор ограничений, что и при создании.
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("code", c.getCode());
        values.put("name", name != null ? name.trim() : c.getName());
        values.put("value", value != null ? value : c.getValue());
        validation.assertValid(Discount.TYPE_ID, values);

        if (name != null) c.setName(name.trim());
        if (value != null) c.setValue(value);
        return DiscountDto.of(repo.save(c));
    }

    @Transactional
    public void delete(UUID id) {
        if (!repo.existsById(id)) {
            throw new NoSuchElementException("Discount not found: " + id);
        }
        repo.deleteById(id);
    }

    private Discount find(UUID id) {
        return repo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Discount not found: " + id));
    }

    private String resolveCode(String code) {
        if (code != null && !code.isBlank()) {
            String c = code.trim();
            if (repo.findByCode(c).isPresent()) {
                throw ValidationFailedException.ofField("code", "A discount with this code already exists");
            }
            return c;
        }
        return codeGen.nextFor(Discount.class, c -> repo.findByCode(c).isEmpty());
    }
}
