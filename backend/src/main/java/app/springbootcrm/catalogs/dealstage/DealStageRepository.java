package app.springbootcrm.catalogs.dealstage;

import domain.core.ddd.annotations.TypeId;
import domain.core.persistence.AggregateRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@TypeId(DealStage.TYPE_ID)
@domain.core.ddd.annotations.AggregateRepository
public interface DealStageRepository extends AggregateRepository<DealStage, UUID> {

    @Query("select p from DealStage p order by p.code asc")
    List<DealStage> findAllSorted();

    @Query("select p from DealStage p where p.code = ?1")
    Optional<DealStage> findByCode(String code);
}
