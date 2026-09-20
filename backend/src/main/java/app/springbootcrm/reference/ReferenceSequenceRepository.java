package app.springbootcrm.reference;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

/**
 * Простой JpaRepository — НЕ AggregateRepository, чтобы НЕ участвовать
 * в AccessFilterActivator AOP-перехвате (это инфраструктура, доступ к ней
 * не должен зависеть от прав пользователя).
 */
public interface ReferenceSequenceRepository extends JpaRepository<ReferenceSequence, Long> {

    /** Найти строку счётчика с PESSIMISTIC_WRITE lock — для атомарного nextSeq++. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from ReferenceSequence s where s.typeId = ?1")
    Optional<ReferenceSequence> findByTypeIdLocked(long typeId);
}
