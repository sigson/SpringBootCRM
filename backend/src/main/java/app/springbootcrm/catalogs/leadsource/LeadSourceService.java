package app.springbootcrm.catalogs.leadsource;

import app.springbootcrm.access.AccessRole;

import app.springbootcrm.reference.CodeGenerator;
import app.springbootcrm.catalogs.leadsource.LeadSourceDto;
import domain.core.validation.ValidationService;
import domain.core.web.ValidationFailedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Стандартный CRUD-сервис справочника {@link LeadSource} (typeId=4101). Доступ
 * enforce'ится стандартными access-aware listener'ами ядра (PreInsert→WRITE_INSERT,
 * PreUpdate→WRITE_UPDATE), без явного admin-гейта: пользователь с соответствующими
 * grant'ами через AccessRole может создавать/редактировать/удалять. Код генерируется
 * через {@link CodeGenerator}. Валидация реквизитов (code/name) — единой системой
 * через {@link ValidationService} (ограничения объявлены на полях Entity).
 */
@Service
public class LeadSourceService {

    private final LeadSourceRepository repo;
    private final CodeGenerator codeGen;
    private final ValidationService validation;

    public LeadSourceService(LeadSourceRepository repo, CodeGenerator codeGen,
                             ValidationService validation) {
        this.repo = repo;
        this.codeGen = codeGen;
        this.validation = validation;
    }

    @Transactional(readOnly = true)
    public List<LeadSourceDto> list() {
        return repo.findAllSorted().stream().map(LeadSourceDto::of).toList();
    }

    @Transactional(readOnly = true)
    public LeadSourceDto get(UUID id) {
        return LeadSourceDto.of(find(id));
    }

    @Transactional
    public LeadSourceDto create(String code, String name) {
        String resolved = resolveCode(code);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("code", resolved);
        values.put("name", name == null ? null : name.trim());
        validation.assertValid(LeadSource.TYPE_ID, values);

        LeadSource t = new LeadSource(UUID.randomUUID(), resolved, name.trim());
        return LeadSourceDto.of(repo.save(t));
    }

    @Transactional
    public LeadSourceDto update(UUID id, String name) {
        LeadSource t = find(id);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("code", t.getCode());
        values.put("name", name != null ? name.trim() : t.getName());
        validation.assertValid(LeadSource.TYPE_ID, values);

        if (name != null) t.setName(name.trim());
        return LeadSourceDto.of(repo.save(t));
    }

    @Transactional
    public void delete(UUID id) {
        if (!repo.existsById(id)) {
            throw new NoSuchElementException("Lead source not found: " + id);
        }
        repo.deleteById(id);
    }

    private LeadSource find(UUID id) {
        return repo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Lead source not found: " + id));
    }

    private String resolveCode(String code) {
        if (code != null && !code.isBlank()) {
            String c = code.trim();
            if (repo.findByCode(c).isPresent()) {
                throw ValidationFailedException.ofField("code", "A lead source with this code already exists");
            }
            return c;
        }
        return codeGen.nextFor(LeadSource.class, c -> repo.findByCode(c).isEmpty());
    }
}
