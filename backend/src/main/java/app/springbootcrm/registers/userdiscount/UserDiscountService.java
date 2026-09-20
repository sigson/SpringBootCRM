package app.springbootcrm.registers.userdiscount;

import app.springbootcrm.user.User;

import app.springbootcrm.catalogs.discount.DiscountRepository;
import app.springbootcrm.user.UserRepository;
import domain.core.web.ValidationFailedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Сервис табличной части «Коэффициенты пользователя».
 *
 * <p>Строки всегда живут в контексте владельца-пользователя. Доступ к ним ядро
 * пробрасывает автоматически от прав на агрегат {@code User} (typeId=9001) —
 * отдельных проверок здесь нет, кроме предметной валидации и существования
 * связанных сущностей.
 */
@Service
public class UserDiscountService {

    private final UserDiscountRepository repo;
    private final UserRepository users;
    private final DiscountRepository discounts;

    public UserDiscountService(UserDiscountRepository repo,
                                  UserRepository users,
                                  DiscountRepository discounts) {
        this.repo = repo;
        this.users = users;
        this.discounts = discounts;
    }

    @Transactional(readOnly = true)
    public List<UserDiscountDto> listForOwner(UUID ownerId) {
        requireUser(ownerId);
        return repo.findByOwner(ownerId).stream().map(UserDiscountDto::of).toList();
    }

    @Transactional(readOnly = true)
    public UserDiscountDto get(UUID id) {
        return UserDiscountDto.of(repo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Tabular row not found: " + id)));
    }

    @Transactional
    public UserDiscountDto create(UUID ownerId, UserDiscountDto.MutationRequest req) {
        requireUser(ownerId);
        validate(req);
        UserDiscount uc = new UserDiscount(
                UUID.randomUUID(),
                ownerId,
                null,
                req.limitPercent());
        uc.setDiscountId(req.discountId());
        return UserDiscountDto.of(repo.save(uc));
    }

    @Transactional
    public UserDiscountDto update(UUID id, UserDiscountDto.MutationRequest req) {
        validate(req);
        UserDiscount uc = repo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Tabular row not found: " + id));
        uc.setDiscountId(req.discountId());
        uc.setLimitPercent(req.limitPercent());
        return UserDiscountDto.of(repo.save(uc));
    }

    @Transactional
    public void delete(UUID id) {
        if (!repo.existsById(id)) {
            throw new NoSuchElementException("Tabular row not found: " + id);
        }
        repo.deleteById(id);
    }

    private void requireUser(UUID ownerId) {
        if (ownerId == null || !users.existsById(ownerId)) {
            throw new NoSuchElementException("Owner user not found: " + ownerId);
        }
    }

    private void validate(UserDiscountDto.MutationRequest req) {
        if (req.discountId() == null) {
            throw ValidationFailedException.ofField("discountId",
                    "Discount is required");
        }
        if (!discounts.existsById(req.discountId())) {
            throw ValidationFailedException.ofField("discountId",
                    "Discount not found: " + req.discountId());
        }
        if (req.limitPercent() == null) {
            throw ValidationFailedException.ofField("limitPercent", "Value is required");
        }
    }
}
