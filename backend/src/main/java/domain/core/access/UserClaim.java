package domain.core.access;

import app.springbootcrm.auth.JwtAuthenticationFilter;

/**
 * Type-safe имена JWT claim'ов, используемых для row-level фильтрации.
 *
 * <p>{@code UserClaim} используется в {@code @AccessFiltered.userClaim()}: имя клейма
 * передаётся в {@code ClaimsExtractor}, который извлекает множество значений из JWT
 * и передаёт их Hibernate-фильтру.
 */
public enum UserClaim {

    ORG_IDS         ("orgIds"),
    CUSTOMER_IDS    ("customerIds"),
    PROJECT_IDS     ("projectIds"),
    DEPARTMENT_IDS  ("departmentIds"),

    /**
     * UUID-набор «свои» сущности — единственный elem'ент list'а — это id текущего
     * пользователя. JwtAuthenticationFilter автоматически кладёт его в details
     * после успешной верификации токена. Используется для row-level фильтрации
     * на агрегатах с per-user owner'ом (например, Calendar — требование заказчика
     * «видны только свои записи»).
     */
    SELF_IDS        ("selfIds");

    public final String jwtName;

    UserClaim(String n) {
        this.jwtName = n;
    }
}
