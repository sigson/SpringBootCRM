package app.springbootcrm.user;

import app.springbootcrm.reference.CodeGenerator;

import domain.core.ddd.annotations.TypeId;
import domain.core.persistence.AggregateRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@TypeId(User.TYPE_ID)
@domain.core.ddd.annotations.AggregateRepository
public interface UserRepository extends AggregateRepository<User, UUID> {

    @Query("select u from User u where u.username = ?1")
    Optional<User> findByUsername(String username);

    /** Поиск по справочному коду (для uniqueness-check в CodeGenerator). */
    @Query("select u from User u where u.code = ?1")
    Optional<User> findByCode(String code);

    /**
     * Пользователи с назначенной ролью {@code roleId}.
     *
     * <p>Роль одна ({@link domain.core.ddd.AggregateReference}), поэтому фильтруем
     * по {@code role.targetIdRaw}.
     *
     * <p>Mapping-state {@code AggregateReferenceUuidState.targetIdRaw} — это
     * <b>UUID</b>-колонка ({@code role_id UUID}), поэтому JPQL-путь
     * {@code u.role.targetIdRaw} имеет тип {@link UUID}. Параметр обязан быть
     * {@link UUID}, иначе Hibernate бросает
     * «Argument of type java.lang.String did not match parameter type java.util.UUID».
     */
    @Query("select u from User u where u.role.targetIdRaw = ?1")
    List<User> findByRoleId(UUID roleId);
}
