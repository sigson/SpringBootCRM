package app.springbootcrm.registers.userdiscount;

import domain.core.ddd.annotations.TypeId;
import domain.core.persistence.AggregateRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
@TypeId(UserDiscount.TYPE_ID)
@domain.core.ddd.annotations.AggregateRepository
public interface UserDiscountRepository extends AggregateRepository<UserDiscount, UUID> {

    /**
     * Строки табличной части конкретного пользователя-владельца.
     *
     * <p>Фильтруем по сырому id владельца ({@code ownerRef.targetIdRaw}). Mapping-state
     * {@code AggregateReferenceUuidState.targetIdRaw} — UUID-колонка ({@code owner_ref_id}),
     * поэтому параметр обязан быть {@link UUID}.
     */
    @Query("select uc from UserDiscount uc where uc.ownerRef.targetIdRaw = ?1 order by uc.limitPercent desc")
    List<UserDiscount> findByOwner(UUID ownerUserId);
}
