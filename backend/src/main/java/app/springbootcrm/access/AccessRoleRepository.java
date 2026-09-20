package app.springbootcrm.access;

import domain.core.ddd.annotations.TypeId;
import domain.core.persistence.AggregateRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
@TypeId(AccessRole.TYPE_ID)
@domain.core.ddd.annotations.AggregateRepository
public interface AccessRoleRepository extends AggregateRepository<AccessRole, UUID> {

    @Query("select r from AccessRole r where r.code = ?1")
    Optional<AccessRole> findByCode(String code);
}
