package app.springbootcrm.documents.deal;

import app.springbootcrm.catalogs.dealstage.DealStage;
import app.springbootcrm.catalogs.customer.CustomerRepository;
import app.springbootcrm.catalogs.dealstage.DealStageRepository;
import app.springbootcrm.catalogs.leadsource.LeadSource;
import app.springbootcrm.catalogs.leadsource.LeadSourceRepository;

import app.springbootcrm.reference.CodeGenerator;
import app.springbootcrm.documents.deal.DealDto;
import domain.core.web.ValidationFailedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Стандартный CRUD-сервис ремонтных норм {@link Deal} (typeId=4104).
 *
 * <p>Код принимается из формы (или авто-генерируется через {@link CodeGenerator} с
 * префиксом «РН»); уникальность проверяется всегда. Ссылки на справочники
 * ({@link LeadSource}, {@link DealStage}) валидируются на существование — это
 * предметная проверка поверх инфраструктурной {@code @ValidAggregateRef}. Доступ
 * enforce'ится стандартными access-aware listener'ами ядра.
 */
@Service
public class DealService {

    private final DealRepository repo;
    private final LeadSourceRepository leadSources;
    private final DealStageRepository dealStages;
    private final CustomerRepository customers;
    private final CodeGenerator codeGen;

    public DealService(DealRepository repo,
                       LeadSourceRepository leadSources,
                       DealStageRepository dealStages,
                       CustomerRepository customers,
                       CodeGenerator codeGen) {
        this.repo = repo;
        this.leadSources = leadSources;
        this.dealStages = dealStages;
        this.customers = customers;
        this.codeGen = codeGen;
    }

    @Transactional(readOnly = true)
    public List<DealDto> list() {
        return repo.findAllSorted().stream().map(DealDto::of).toList();
    }

    @Transactional(readOnly = true)
    public DealDto get(UUID id) {
        return DealDto.of(find(id));
    }

    @Transactional
    public DealDto create(String code, UUID leadSourceId, UUID dealStageId,
                          UUID customerId, BigDecimal amount, LocalDate expectedCloseDate) {
        String resolved = resolveCode(code);
        validateRefs(leadSourceId, dealStageId, customerId);
        validateAmount(amount);

        Deal n = new Deal(UUID.randomUUID(), resolved, null, null, null,
                amount, expectedCloseDate);
        n.setLeadSourceId(leadSourceId);
        n.setDealStageId(dealStageId);
        n.setCustomerId(customerId);
        return DealDto.of(repo.save(n));
    }

    @Transactional
    public DealDto update(UUID id, UUID leadSourceId, UUID dealStageId,
                          UUID customerId, BigDecimal amount, LocalDate expectedCloseDate) {
        Deal n = find(id);
        UUID effLeadSource = leadSourceId != null ? leadSourceId : n.getLeadSourceId();
        UUID effDealStage = dealStageId != null ? dealStageId : n.getDealStageId();
        UUID effCustomer = customerId != null ? customerId : n.getCustomerId();
        BigDecimal effAmount = amount != null ? amount : n.getAmount();
        validateRefs(effLeadSource, effDealStage, effCustomer);
        validateAmount(effAmount);

        n.setLeadSourceId(effLeadSource);
        n.setDealStageId(effDealStage);
        n.setCustomerId(effCustomer);
        n.setAmount(effAmount);
        if (expectedCloseDate != null) n.setExpectedCloseDate(expectedCloseDate);
        return DealDto.of(repo.save(n));
    }

    @Transactional
    public void delete(UUID id) {
        if (!repo.existsById(id)) {
            throw new NoSuchElementException("Deal not found: " + id);
        }
        repo.deleteById(id);
    }

    private Deal find(UUID id) {
        return repo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Deal not found: " + id));
    }

    private void validateRefs(UUID leadSourceId, UUID dealStageId, UUID customerId) {
        if (leadSourceId == null) {
            throw ValidationFailedException.ofField("leadSourceId", "Lead source is required");
        }
        if (!leadSources.existsById(leadSourceId)) {
            throw ValidationFailedException.ofField("leadSourceId",
                    "Lead source not found: " + leadSourceId);
        }
        if (dealStageId == null) {
            throw ValidationFailedException.ofField("dealStageId", "Deal stage is required");
        }
        if (!dealStages.existsById(dealStageId)) {
            throw ValidationFailedException.ofField("dealStageId",
                    "Deal stage not found: " + dealStageId);
        }
        if (customerId == null) {
            throw ValidationFailedException.ofField("customerId", "Customer is required");
        }
        if (!customers.existsById(customerId)) {
            throw ValidationFailedException.ofField("customerId",
                    "Customer not found: " + customerId);
        }
    }

    private void validateAmount(BigDecimal amount) {
        if (amount == null) {
            throw ValidationFailedException.ofField("amount", "Amount is required");
        }
        if (amount.signum() < 0) {
            throw ValidationFailedException.ofField("amount", "Amount cannot be negative");
        }
    }

    private String resolveCode(String code) {
        if (code != null && !code.isBlank()) {
            String c = code.trim();
            if (repo.findByCode(c).isPresent()) {
                throw ValidationFailedException.ofField("code", "A deal with this code already exists");
            }
            return c;
        }
        return codeGen.nextFor(Deal.class, c -> repo.findByCode(c).isEmpty());
    }
}
