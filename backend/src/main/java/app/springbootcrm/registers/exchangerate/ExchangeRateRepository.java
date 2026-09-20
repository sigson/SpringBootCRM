package app.springbootcrm.registers.exchangerate;

import domain.core.ddd.annotations.TypeId;
import domain.core.persistence.AggregateRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
@TypeId(ExchangeRate.TYPE_ID)
@domain.core.ddd.annotations.AggregateRepository
public interface ExchangeRateRepository extends AggregateRepository<ExchangeRate, UUID> {

    @Query("select r from ExchangeRate r order by r.rateDate desc, r.currencyCode asc")
    List<ExchangeRate> findAllSorted();

    @Query("select r from ExchangeRate r where r.currencyCode = ?1 order by r.rateDate desc")
    List<ExchangeRate> findByCurrency(String currencyCode);
}
