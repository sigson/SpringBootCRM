package app.springbootcrm.catalogs.customer;

import domain.core.ddd.annotations.TypeId;
import domain.core.persistence.AggregateRepository;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@TypeId(Customer.TYPE_ID)
@domain.core.ddd.annotations.AggregateRepository
public interface CustomerRepository
        extends AggregateRepository<Customer, UUID> {

    /** Сортировка по CODE asc. */
    Sort SORT_BY_CODE_ASC = Sort.by(Sort.Direction.ASC, "code");

    @Query("select c from Customer c order by c.code asc")
    List<Customer> findAllSorted();

    @Query("select c from Customer c where c.code = ?1")
    Optional<Customer> findByCode(String code);
}
