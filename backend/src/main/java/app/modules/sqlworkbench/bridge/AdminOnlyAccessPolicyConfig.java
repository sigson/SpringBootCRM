package app.modules.sqlworkbench.bridge;

import app.modules.sqlworkbench.security.AccessContext;
import app.modules.sqlworkbench.security.WorkbenchAccessPolicy;
import app.modules.sqlworkbench.security.WorkbenchAction;
import app.springbootcrm.auth.AdminCheck;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.EnumSet;
import java.util.Set;

/**
 * <h2>Мост между модулем «SQL Workbench» и системой доступа хоста.</h2>
 *
 * <p>Это <b>единственное</b> место, где модуль ссылается на основную кодовую базу
 * ({@code app.springbootcrm.auth.AdminCheck}). Зависимость направлена «модуль → хост»,
 * поэтому хост ничего не знает про модуль и компилируется без него.
 *
 * <p>Регистрирует {@link WorkbenchAccessPolicy} как {@code @ConditionalOnMissingBean}:
 * если владелец продукта захочет свою, более тонкую политику (например, разрешить
 * READ аналитикам) — он просто объявит свой бин {@code WorkbenchAccessPolicy} в хосте,
 * и этот дефолт уступит ему.
 *
 * <h3>Стандартное поведение — admin-only</h3>
 * Все действия {@link WorkbenchAction} (просмотр датасорсов, метаданные, SELECT, CRUD,
 * конструктор) разрешены только пользователям с admin-флагом
 * ({@code AdminCheck.isAdmin()}). Зеркалит раздел «Генерация данных»: и на бэкенде
 * (здесь), и на фронтенде (guard {@code isAdmin}).
 *
 * <p>Отдельно: запись данных ({@code INSERT/UPDATE/DELETE}, управление датасорсами)
 * по умолчанию требует <b>root</b>-флага — дополнительный предохранитель против
 * случайной мутации основной БД. Переключить на «любой admin» можно, заменив этот
 * бин в хосте.
 */
@Configuration
public class AdminOnlyAccessPolicyConfig {

    /** Действия, меняющие данные/конфигурацию, требуют повышенного (root) права. */
    private static final Set<WorkbenchAction> WRITE_ACTIONS = EnumSet.of(
            WorkbenchAction.INSERT_DATA, WorkbenchAction.UPDATE_DATA,
            WorkbenchAction.DELETE_DATA, WorkbenchAction.MANAGE_DATASOURCES);

    @Bean
    @ConditionalOnMissingBean(WorkbenchAccessPolicy.class)
    public WorkbenchAccessPolicy sqlWorkbenchAdminOnlyPolicy(AdminCheck adminCheck) {
        return new AdminOnlyAccessPolicy(adminCheck);
    }

    /**
     * Политика «только администратор»: чтение — admin, запись/конфиг — root.
     */
    static final class AdminOnlyAccessPolicy implements WorkbenchAccessPolicy {
        private final AdminCheck adminCheck;

        AdminOnlyAccessPolicy(AdminCheck adminCheck) { this.adminCheck = adminCheck; }

        @Override
        public boolean isAllowed(WorkbenchAction action, AccessContext ctx) {
            if (!adminCheck.isAdmin()) return false;
            if (WRITE_ACTIONS.contains(action)) return adminCheck.isRoot();
            return true;
        }
    }
}
