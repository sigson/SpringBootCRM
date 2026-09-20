package app.springbootcrm.documents.activity;

import domain.core.ddd.annotations.TypeId;
import domain.core.persistence.AggregateRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@TypeId(Activity.TYPE_ID)
@domain.core.ddd.annotations.AggregateRepository
public interface ActivityRepository extends AggregateRepository<Activity, UUID> {

    /**
     * Список событий, упорядоченных по дате начала. Через {@code AccessFilterActivator}
     * AOP сюда автоматически добавляется row-level фильтр {@code filter_activity_owner}
     * для не-admin'ов.
     */
    @Query("select e from Activity e order by e.startsAt asc")
    List<Activity> findAllSorted();

    /**
     * Pageable-вариант для chunked-загрузки (виртуальный скролл / страницы).
     * Row-level фильтр {@code filter_activity_owner} применяется тем же AOP
     * (метод матчится паттерном {@code find*}). {@code Page} возвращает и содержимое,
     * и {@code totalElements} — фронт использует total для расчёта высоты скролла.
     */
    @Query("select e from Activity e order by e.startsAt asc")
    Page<Activity> findAllPaged(Pageable pageable);

    /**
     * Поиск по id, на который РАСПРОСТРАНЯЕТСЯ row-level фильтр
     * {@code filter_activity_owner}.
     *
     * <p>Hibernate не применяет {@code @Filter} к загрузке по первичному ключу
     * ({@code findById} / {@code session.get}) — только к запросам. Из-за этого
     * {@code findById} возвращал чужую строку, и отказ давал уже post-load-контроль
     * доступа: ответ 403 вместо 404, то есть подтверждение, что запись с таким id
     * существует. HQL-запрос ниже фильтруется, поэтому чужая активность просто
     * не находится.
     */
    @Query("select e from Activity e where e.id = :id")
    Optional<Activity> findVisibleById(@Param("id") UUID id);
}
