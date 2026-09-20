package app.springbootcrm.catalogs.discount;

import domain.core.ddd.annotations.TypeId;
import domain.core.persistence.AggregateRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@TypeId(Discount.TYPE_ID)
@domain.core.ddd.annotations.AggregateRepository
public interface DiscountRepository extends AggregateRepository<Discount, UUID> {

    @Query("select c from Discount c order by c.code asc")
    List<Discount> findAllSorted();

    @Query("select c from Discount c where c.code = ?1")
    Optional<Discount> findByCode(String code);
}
