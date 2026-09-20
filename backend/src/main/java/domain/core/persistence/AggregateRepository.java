package domain.core.persistence;

import app.springbootcrm.catalogs.customer.Customer;
import app.springbootcrm.catalogs.customer.CustomerRepository;

import domain.core.ddd.AbstractAggregate;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.data.domain.Window;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.NoRepositoryBean;

import java.io.Serializable;
import java.util.List;
import java.util.Optional;

/**
 * Базовый repo-интерфейс. Все конкретные repo'ы расширяют это и аннотируются:
 * <pre>
 *   {@literal @}TypeId(1001)
 *   {@literal @}domain.core.ddd.annotations.AggregateRepository
 *   public interface CustomerRepository extends AggregateRepository&lt;Customer, UUID&gt; { ... }
 * </pre>
 *
 * <p>{@link #findByIdLocked(Object)} обязателен для {@code @AggregateLockingPolicy(PESSIMISTIC_*)}.
 */
@NoRepositoryBean
public interface AggregateRepository<T extends AbstractAggregate<ID>, ID extends Serializable>
        extends JpaRepository<T, ID>, JpaSpecificationExecutor<T> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from #{#entityName} e where e.id = ?1")
    Optional<T> findByIdLocked(ID id);

    /**
     * Завантажує ОДНУ сторінку рядків за {@link Specification} <b>без</b> {@code COUNT(*)}.
     *
     * <p>Стандартний {@code findAll(spec, Pageable)} на кожен виклик виконує ще й окремий
     * {@code count(...)} — на великих відфільтрованих наборах це повне сканування таблиці й
     * головна причина латентності пагінації. Оскільки total змінюється лише зі зміною фільтра,
     * фронтенд запитує його раз і кешує; цей метод обслуговує «наступні сторінки».
     *
     * <p>Реалізація — offset-пагінація через fluent {@code findBy(...).limit(...).scroll(offset)}
     * (Spring Data 3.x); {@code ORDER BY} бере зі {@link Specification}, тож порядок ідентичний
     * count-варіанту. Row-level security збережено: ім'я {@code find*} матчиться у
     * {@link AccessFilterActivator}, Hibernate-фільтри та admin-bypass активні.
     *
     * @param spec     специфікація (предикати + {@code ORDER BY})
     * @param pageable сторінка (offset = page*size, limit = size); порядок задає сама {@code spec}
     * @return рядки сторінки (без загальної кількості)
     */
    default List<T> findPageContent(Specification<T> spec, Pageable pageable) {
        long offset = pageable.getOffset();
        ScrollPosition position = (offset == 0)
                ? ScrollPosition.offset()                 // початок (offset 0)
                : ScrollPosition.offset(offset - 1);      // рядки ПІСЛЯ (offset-1) → починаючи з offset
        Window<T> window = findBy(spec, q -> q
                .limit(pageable.getPageSize())
                .scroll(position));
        return window.getContent();
    }
}
