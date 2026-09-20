package app.springbootcrm.catalogs.leadsource;

import domain.core.ddd.annotations.TypeId;
import domain.core.persistence.AggregateRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@TypeId(LeadSource.TYPE_ID)
@domain.core.ddd.annotations.AggregateRepository
public interface LeadSourceRepository extends AggregateRepository<LeadSource, UUID> {

    @Query("select t from LeadSource t order by t.code asc")
    List<LeadSource> findAllSorted();

    @Query("select t from LeadSource t where t.code = ?1")
    Optional<LeadSource> findByCode(String code);
}
