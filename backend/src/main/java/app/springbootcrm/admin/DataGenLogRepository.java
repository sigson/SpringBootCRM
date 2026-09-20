package app.springbootcrm.admin;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Репозиторій журналу згенерованих даних. Звичайний {@link JpaRepository}
 * (без {@code @TypeId}), тому {@code RepositoryRegistry} його не реєструє як
 * agg-репозиторій.
 */
public interface DataGenLogRepository extends JpaRepository<DataGenLog, UUID> {

    /** Усі записи журналу, новіші — першими (для детермінованого порядку зачистки). */
    List<DataGenLog> findAllByOrderByCreatedAtDesc();

    /**
     * Bulk-видалення всіх записів журналу заданого типу (для «очистити весь тип»).
     * {@code @Modifying} — DML без завантаження сутностей у контекст.
     */
    @Modifying
    @Transactional
    @Query("delete from DataGenLog l where l.typeId = :typeId")
    int deleteByTypeId(long typeId);
}
