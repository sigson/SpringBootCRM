package app.springbootcrm.catalogs.product;

import domain.core.ddd.annotations.TypeId;
import domain.core.persistence.AggregateRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@TypeId(Product.TYPE_ID)
@domain.core.ddd.annotations.AggregateRepository
public interface ProductRepository extends AggregateRepository<Product, UUID> {

    /**
     * Початковий (дефолтний) порядок видачі — по CODE asc. Це лише сортування «за
     * замовчуванням» до взаємодії користувача: інтерактивне <b>багатоколоночне</b>
     * сортування та фільтрація (швидкі/глобальні/розширені) по <i>всіх</i> колонках
     * виконує generic-список ({@code applyClientQuery}) клієнтськи над {@code GET apiBase}.
     */
    @Query("select e from Product e order by e.code asc")
    List<Product> findAllSorted();

    @Query("select e from Product e where e.code = ?1")
    Optional<Product> findByCode(String code);

    /** Для контроля уникальности NAME. */
    @Query("select e from Product e where e.name = ?1")
    Optional<Product> findByName(String name);
}
