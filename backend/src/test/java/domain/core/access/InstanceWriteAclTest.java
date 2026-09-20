package domain.core.access;

import domain.core.ddd.AggregateReference;
import domain.core.ddd.UserAggregate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Построчный (per-instance) ACL записи.
 *
 * <p>До этих тестов {@code effectiveOwnAccessFor} всегда возвращал
 * {@link AccessLevel#READ_WRITE}, игнорируя пользователя: whitelist на экземпляре
 * существовал в модели и в документации, но ничего не ограничивал. Тесты фиксируют
 * обе стороны контракта — и что ACL <i>ограничивает</i>, и что его отсутствие
 * <i>не ограничивает</i>.
 */
class InstanceWriteAclTest {

    private static final long USER_TYPE = 9001L;
    private static final long DOC_TYPE = 4104L;

    private static final String ALICE = UUID.randomUUID().toString();
    private static final String BOB = UUID.randomUUID().toString();
    private static final String DOC_ID = UUID.randomUUID().toString();

    /**
     * Контекст с заданным принципалом. Для резолва построчного ACL нужен только
     * {@code principalRef}, поэтому остальные поля контекста не заполняются.
     * Сырой каст к параметризованному типу повторяет то, что делает продакшен-резолвер
     * ({@code JwtPrincipalRefResolver}): конкретный класс пользователя здесь не важен.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static AccessContext ctxFor(String principalId) {
        AggregateReference ref = AggregateReference.ofRaw(USER_TYPE, principalId);
        return new AccessContext(
                null, (AggregateReference<? extends UserAggregate<?>, ?>) ref,
                null, null, null, "test");
    }

    private static AccessContext systemCtx() {
        return ctxFor(AccessContext.SYSTEM_PRINCIPAL_ID);
    }

    private static AccessLevel resolve(AccessMetric ownAccess, AccessMetric userMetric,
                                       AccessContext ctx) {
        return AccessResolver.effectiveOwnAccessFor(ownAccess, userMetric, DOC_TYPE, DOC_ID, ctx);
    }

    @Nested
    @DisplayName("ACL не задан")
    class NoAcl {

        @Test
        @DisplayName("Пустой whitelist не ограничивает никого")
        void empty_acl_does_not_restrict() {
            assertThat(resolve(AccessMetric.empty(), AccessMetric.empty(), ctxFor(ALICE)))
                    .isEqualTo(AccessLevel.READ_WRITE);
        }

        @Test
        @DisplayName("Отсутствующий ownAccess не ограничивает")
        void null_own_access_does_not_restrict() {
            assertThat(resolve(null, AccessMetric.empty(), ctxFor(ALICE)))
                    .isEqualTo(AccessLevel.READ_WRITE);
        }

        @Test
        @DisplayName("Whitelist на стороне пользователя сам по себе ничего не закрывает")
        void user_side_grant_alone_does_not_restrict() {
            AccessMetric user = AccessMetric.empty().withInstanceWhitelist(DOC_TYPE, DOC_ID);
            assertThat(resolve(AccessMetric.empty(), user, ctxFor(ALICE)))
                    .isEqualTo(AccessLevel.READ_WRITE);
        }
    }

    @Nested
    @DisplayName("ACL задан на экземпляре")
    class WithAcl {

        private final AccessMetric ownAccess =
                AccessMetric.empty().withInstanceWhitelist(USER_TYPE, ALICE);

        @Test
        @DisplayName("Субъект из whitelist'а пишет")
        void whitelisted_principal_may_write() {
            assertThat(resolve(ownAccess, AccessMetric.empty(), ctxFor(ALICE)))
                    .isEqualTo(AccessLevel.READ_WRITE);
        }

        @Test
        @DisplayName("Субъект вне whitelist'а теряет запись, но сохраняет чтение")
        void non_whitelisted_principal_loses_write() {
            AccessLevel level = resolve(ownAccess, AccessMetric.empty(), ctxFor(BOB));

            assertThat(level).isEqualTo(AccessLevel.READ_ONLY);
            assertThat(level.canRead()).isTrue();
            assertThat(level.canUpdate("x")).isFalse();
            assertThat(level.canInsert()).isFalse();
        }

        @Test
        @DisplayName("Совпадение по id, но по чужому типу субъекта не засчитывается")
        void principal_type_must_match() {
            AccessMetric aclForOtherType =
                    AccessMetric.empty().withInstanceWhitelist(DOC_TYPE, ALICE);

            assertThat(resolve(aclForOtherType, AccessMetric.empty(), ctxFor(ALICE)))
                    .isEqualTo(AccessLevel.READ_ONLY);
        }

        @Test
        @DisplayName("Право, выданное пользователю на конкретную запись, тоже открывает запись")
        void user_side_grant_opens_write() {
            AccessMetric user = AccessMetric.empty().withInstanceWhitelist(DOC_TYPE, DOC_ID);

            assertThat(resolve(ownAccess, user, ctxFor(BOB))).isEqualTo(AccessLevel.READ_WRITE);
        }

        @Test
        @DisplayName("Право на другую запись того же типа не открывает эту")
        void user_side_grant_is_per_instance() {
            AccessMetric user = AccessMetric.empty()
                    .withInstanceWhitelist(DOC_TYPE, UUID.randomUUID().toString());

            assertThat(resolve(ownAccess, user, ctxFor(BOB))).isEqualTo(AccessLevel.READ_ONLY);
        }

        @Test
        @DisplayName("Системный контекст пишет всегда — он не субъект доступа")
        void system_context_bypasses_the_acl() {
            assertThat(resolve(ownAccess, AccessMetric.empty(), systemCtx()))
                    .isEqualTo(AccessLevel.READ_WRITE);
        }

        @Test
        @DisplayName("Без контекста запись закрыта: неизвестный субъект не в whitelist'е")
        void unknown_principal_is_denied() {
            assertThat(resolve(ownAccess, AccessMetric.empty(), null))
                    .isEqualTo(AccessLevel.READ_ONLY);
        }

        @Test
        @DisplayName("Whitelist переживает union метрик (склейку ролей)")
        void acl_survives_union() {
            AccessMetric fromRoleA = AccessMetric.empty().withInstanceWhitelist(USER_TYPE, ALICE);
            AccessMetric fromRoleB = AccessMetric.empty().withInstanceWhitelist(USER_TYPE, BOB);
            AccessMetric merged = fromRoleA.union(fromRoleB);

            assertThat(resolve(merged, AccessMetric.empty(), ctxFor(ALICE)))
                    .isEqualTo(AccessLevel.READ_WRITE);
            assertThat(resolve(merged, AccessMetric.empty(), ctxFor(BOB)))
                    .isEqualTo(AccessLevel.READ_WRITE);
        }
    }

    @Test
    @DisplayName("ACL только сужает: пересечение с READ_ONLY снимает запись, но не чтение")
    void acl_narrows_by_intersection() {
        AccessLevel restricted = AccessLevel.READ_WRITE.intersect(AccessLevel.READ_ONLY);

        assertThat(restricted.canRead()).isTrue();
        assertThat(restricted.canModify()).isFalse();

        // admin/root-bypass добавляется union'ом уже после пересечения и возвращает запись.
        AccessLevel withBypass = restricted.union(new AccessLevel(AccessFlags.ROOT_WRITE, WriteMode.ANY));
        assertThat(withBypass.canModify()).isTrue();
    }
}
