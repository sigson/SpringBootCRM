package app.springbootcrm.interfaces;

import domain.core.ddd.annotations.TypeId;
import domain.core.persistence.AggregateRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
@TypeId(InterfaceLayout.TYPE_ID)
@domain.core.ddd.annotations.AggregateRepository
public interface InterfaceLayoutRepository
        extends AggregateRepository<InterfaceLayout, UUID> {

    @Query("select i from InterfaceLayout i where i.code = ?1")
    Optional<InterfaceLayout> findByCode(String code);
}
