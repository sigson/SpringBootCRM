package domain.core.ddd.annotations;

import app.springbootcrm.user.User;

import domain.core.access.BypassPolicy;
import domain.core.access.UserClaim;

import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.annotation.ElementType;

/**
 * Объявляет row-level фильтр на агрегате.
 *
 * <ul>
 *   <li>{@code filterField} — property типа {@code AggregateReference<T,ID>}, по которой
 *       фильтруется. Должна соответствовать {@code [a-zA-Z_][a-zA-Z0-9_]*} (bootstrap
 *       fail-fast — исключает SQL-injection в native-SQL count'ах/reporting'е);</li>
 *   <li>{@code userClaim} — JWT-claim с разрешёнными значениями для filterField;</li>
 *   <li>{@code referencedTypeId} — typeId агрегата-цели (типизация параметров и bypass);</li>
 *   <li>{@code bypassPolicy} — {@link BypassPolicy#EXPLICIT_ONLY} (дефолт) или
 *       {@link BypassPolicy#AUTO_TRANSITIVE} (транзитивное замыкание через {@code AccessFilterGraph}).</li>
 * </ul>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Repeatable(AccessFiltered.List.class)
public @interface AccessFiltered {

    String filterField();

    UserClaim userClaim();

    long referencedTypeId();

    String filterName() default "";

    BypassPolicy bypassPolicy() default BypassPolicy.EXPLICIT_ONLY;

    @Target(ElementType.TYPE)
    @Retention(RetentionPolicy.RUNTIME)
    @interface List {
        AccessFiltered[] value();
    }

    /**
     * Per-type row-level фильтр для <b>union</b>-ссылочного поля: семантика отбора
     * зависит от того, на какой тип указывает значение строки (для {@code User} —
     * по {@code SELF_IDS}, для {@code Organization} — по {@code ORG_IDS}).
     * Объявляется по одной {@code @PerType} на вариант:
     * <pre>{@code
     * @AccessFiltered.PerType(typeId=User.TYPE_ID, filterField="owner",
     *                         userClaim=SELF_IDS, filterName="filter_owner_user")
     * @AccessFiltered.PerType(typeId=Org.TYPE_ID,  filterField="owner",
     *                         userClaim=ORG_IDS,  filterName="filter_owner_org")
     * }</pre>
     *
     * <p>Каждый {@code filterName} должен ссылаться на статический {@code @FilterDef}/{@code @Filter},
     * condition которого сам ограничивает строки своим дискриминатором, пропуская остальные:
     * <pre>{@code (owner_type_id = 9001 AND owner_id IN (:ids)) OR owner_type_id <> 9001}</pre>
     * {@code AccessFilterActivator} включает все фильтры одновременно, Hibernate их AND-склеивает,
     * и каждый покрывает свою долю строк.
     */
    @Target(ElementType.TYPE)
    @Retention(RetentionPolicy.RUNTIME)
    @Repeatable(PerType.List.class)
    @interface PerType {

        /** Дискриминатор — вариант union-поля, к которому применяется правило. */
        long typeId();

        String filterField();

        UserClaim userClaim();

        /** Тип, чьи id содержит claim (для типизации параметров). По умолчанию — {@link #typeId()}. */
        long referencedTypeId() default -1L;

        String filterName();

        BypassPolicy bypassPolicy() default BypassPolicy.EXPLICIT_ONLY;

        @Target(ElementType.TYPE)
        @Retention(RetentionPolicy.RUNTIME)
        @interface List {
            PerType[] value();
        }
    }
}
