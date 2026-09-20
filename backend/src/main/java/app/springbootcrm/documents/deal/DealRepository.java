package app.springbootcrm.documents.deal;

import domain.core.ddd.annotations.TypeId;
import domain.core.persistence.AggregateRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@TypeId(Deal.TYPE_ID)
@domain.core.ddd.annotations.AggregateRepository
public interface DealRepository extends AggregateRepository<Deal, UUID> {

    @Query("select n from Deal n order by n.code asc")
    List<Deal> findAllSorted();

    @Query("select n from Deal n where n.code = ?1")
    Optional<Deal> findByCode(String code);
}
