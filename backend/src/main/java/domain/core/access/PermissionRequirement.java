package domain.core.access;

import app.springbootcrm.auth.AdminCheck;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Структурированное описание «каких прав не хватило для выполнения действия».
 *
 * <p>Создаётся в точке проверки доступа (Hibernate-listener'ы, {@code AdminCheck},
 * сервисы) и переносится в {@link StructuredAccessDeniedException} → REST-envelope.
 * Фронтенд показывает его в центральном модальном окне «недостаточно прав»:
 * пользователь видит, какой именно бит/typeId нужно выдать, вместо общего отказа.
 *
 * <p>Запись иммутабельна, equals/hashCode по значению — для дедупликации одинаковых
 * требований (например, нескольких полей одного типа).
 *
 * @param typeId         typeId агрегата, к которому относится требование. {@code null} —
 *                       global-flag (например, ADMIN_READ).
 * @param typeLabel      Human-readable имя агрегата для UI. Может быть {@code null} —
 *                       тогда фронт отрендерит по typeId.
 * @param requiredFlags  Битовая маска {@link AccessFlags}, которую должна содержать
 *                       эффективная метрика пользователя (после {@link AccessFlags#expand}).
 * @param scope          Контекст: {@code REPO} (repo-операция), {@code FIELD} (поле),
 *                       {@code GLOBAL} (флаг в globalFlags), {@code INSTANCE}
 *                       (per-instance ограничение, например own_access).
 * @param fieldName      Имя поля для {@code FIELD}-scope ({@code null} для остальных).
 */
public record PermissionRequirement(
        Long typeId,
        String typeLabel,
        int requiredFlags,
        Scope scope,
        String fieldName
) {

    public enum Scope {
        /** Право на repo-операцию (insert/update/delete над типом). */
        REPO,
        /** Право на конкретное поле агрегата. */
        FIELD,
        /** Глобальный флаг в {@code AccessMetric.globalFlags}. */
        GLOBAL,
        /** Per-instance ограничение (Hibernate-filter, ownAccess). */
        INSTANCE
    }

    /** Фабрика repo-level требования. */
    public static PermissionRequirement repo(long typeId, int requiredFlags) {
        return new PermissionRequirement(typeId, null, requiredFlags, Scope.REPO, null);
    }

    /** Фабрика field-level требования. */
    public static PermissionRequirement field(long typeId, String fieldName, int requiredFlags) {
        return new PermissionRequirement(typeId, null, requiredFlags, Scope.FIELD, fieldName);
    }

    /** Фабрика global-flag требования (например {@code ADMIN_READ}). */
    public static PermissionRequirement global(int requiredFlags) {
        return new PermissionRequirement(null, null, requiredFlags, Scope.GLOBAL, null);
    }

    /**
     * Преобразует {@link #requiredFlags} в список человекочитаемых имён битов
     * (например {@code ["READ", "WRITE_INSERT"]}). Раскрывает комбинированную
     * константу {@link AccessFlags#WRITE} в отдельные биты. Порядок стабильный.
     */
    public List<String> requiredFlagNames() {
        int f = requiredFlags;
        List<String> out = new ArrayList<>();
        if ((f & AccessFlags.READ)         != 0) out.add("READ");
        if ((f & AccessFlags.WRITE_INSERT) != 0) out.add("WRITE_INSERT");
        if ((f & AccessFlags.ADMIN_READ)   != 0) out.add("ADMIN_READ");
        if ((f & AccessFlags.ADMIN_WRITE)  != 0) out.add("ADMIN_WRITE");
        if ((f & AccessFlags.ROOT_READ)    != 0) out.add("ROOT_READ");
        if ((f & AccessFlags.ROOT_WRITE)   != 0) out.add("ROOT_WRITE");
        if ((f & AccessFlags.WRITE_UPDATE) != 0) out.add("WRITE_UPDATE");
        return Collections.unmodifiableList(out);
    }

    /** Копия с обновлённым typeLabel (заполняется в REST-слое). */
    public PermissionRequirement withTypeLabel(String label) {
        return new PermissionRequirement(typeId, label, requiredFlags, scope, fieldName);
    }
}
