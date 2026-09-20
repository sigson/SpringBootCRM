package domain.core.eventing;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface OutboxJpaRepository extends JpaRepository<OutboxEntity, Long> {
}
