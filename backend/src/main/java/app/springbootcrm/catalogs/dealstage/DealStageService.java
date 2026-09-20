package app.springbootcrm.catalogs.dealstage;

import app.springbootcrm.reference.CodeGenerator;
import app.springbootcrm.catalogs.dealstage.DealStageDto;
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

/** Стандартный CRUD-сервис справочника {@link DealStage} (typeId=4102). */
@Service
public class DealStageService {

    private final DealStageRepository repo;
    private final CodeGenerator codeGen;
    private final ValidationService validation;

    public DealStageService(DealStageRepository repo, CodeGenerator codeGen,
                                ValidationService validation) {
        this.repo = repo;
        this.codeGen = codeGen;
        this.validation = validation;
    }

    @Transactional(readOnly = true)
    public List<DealStageDto> list() {
        return repo.findAllSorted().stream().map(DealStageDto::of).toList();
    }

    @Transactional(readOnly = true)
    public DealStageDto get(UUID id) { return DealStageDto.of(find(id)); }

    @Transactional
    public DealStageDto create(String code, String name, BigDecimal probability) {
        String resolved = resolveCode(code);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("code", resolved);
        values.put("name", name == null ? null : name.trim());
        values.put("probability", probability);
        validation.assertValid(DealStage.TYPE_ID, values);

        DealStage p = new DealStage(UUID.randomUUID(), resolved, name.trim(), probability);
        return DealStageDto.of(repo.save(p));
    }

    @Transactional
    public DealStageDto update(UUID id, String name, BigDecimal probability) {
        DealStage p = find(id);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("code", p.getCode());
        values.put("name", name != null ? name.trim() : p.getName());
        values.put("probability", probability != null ? probability : p.getProbability());
        validation.assertValid(DealStage.TYPE_ID, values);

        if (name != null) p.setName(name.trim());
        if (probability != null) p.setProbability(probability);
        return DealStageDto.of(repo.save(p));
    }

    @Transactional
    public void delete(UUID id) {
        if (!repo.existsById(id)) {
            throw new NoSuchElementException("Deal stage not found: " + id);
        }
        repo.deleteById(id);
    }

    private DealStage find(UUID id) {
        return repo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Deal stage not found: " + id));
    }

    private String resolveCode(String code) {
        if (code != null && !code.isBlank()) {
            String c = code.trim();
            if (repo.findByCode(c).isPresent()) {
                throw ValidationFailedException.ofField("code", "A deal stage with this code already exists");
            }
            return c;
        }
        return codeGen.nextFor(DealStage.class, c -> repo.findByCode(c).isEmpty());
    }
}
