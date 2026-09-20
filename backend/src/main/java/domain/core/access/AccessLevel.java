package domain.core.access;

import java.util.Collection;
import java.util.Map;

/**
 * Уровень доступа = (flags, mode). Канонический инвариант:
 * {@code flags} ВСЕГДА хранится в expanded-форме — нормализация в compact-конструкторе.
 *
 * <p>Это гарантирует консистентность {@link #equals(Object)}, {@link #hashCode()},
 * {@link #toString()}, {@link #intersect(AccessLevel)}, {@link #union(AccessLevel)}
 * независимо от способа создания.
 *
 * <p>Запись проверяется раздельно по фазе жизненного цикла
 * ({@link AccessFlags#WRITE_INSERT}/{@link AccessFlags#WRITE_UPDATE}) через три предиката:
 * <ul>
 *   <li>{@link #canInsert()} — для {@code PreInsertEventListener} (создание новой записи);</li>
 *   <li>{@link #canUpdate(Object)} — для {@code PreUpdateEventListener} с учётом WriteMode на поле;</li>
 *   <li>{@link #canModify()} — repo-level «может ли пользователь хоть что-то update/delete на типе»;
 *       идентичен {@link #canDelete()}.</li>
 * </ul>
 */
public record AccessLevel(int flags, WriteMode mode) {

    /** Канонический инвариант: flags всегда нормализован. */
    public AccessLevel {
        flags = AccessFlags.expand(flags);
        if (mode == null) mode = WriteMode.ANY;
    }

    public static final AccessLevel HIDDEN     = new AccessLevel(AccessFlags.NONE,  WriteMode.ANY);
    public static final AccessLevel READ_ONLY  = new AccessLevel(AccessFlags.READ,  WriteMode.ANY);
    public static final AccessLevel WRITE_ONLY = new AccessLevel(AccessFlags.WRITE, WriteMode.WRITE_ONLY);
    public static final AccessLevel READ_WRITE =
            new AccessLevel(AccessFlags.READ | AccessFlags.WRITE, WriteMode.ANY);
    public static final AccessLevel INIT_ONCE  =
            new AccessLevel(AccessFlags.READ | AccessFlags.WRITE, WriteMode.MODIFY_EMPTY);

    public boolean canRead() {
        if ((flags & AccessFlags.ROOT_READ) != 0) return true;     // root_read обходит WRITE_ONLY/HIDDEN
        if (mode == WriteMode.WRITE_ONLY)         return false;
        return (flags & AccessFlags.READ) != 0;
    }

    /**
     * Может ли субъект СОЗДАТЬ новую запись (insert). На insert значения «до» нет,
     * поэтому {@code MODIFY_EMPTY} (init-once) тоже разрешает.
     */
    public boolean canInsert() {
        if ((flags & AccessFlags.ROOT_WRITE) != 0)   return true;
        return (flags & AccessFlags.WRITE_INSERT) != 0;
    }

    /**
     * Может ли субъект ИЗМЕНИТЬ существующее значение поля. Учитывает {@code WriteMode}:
     * {@code MODIFY_EMPTY} разрешает только если текущее значение пустое.
     */
    public boolean canUpdate(Object currentValue) {
        if ((flags & AccessFlags.ROOT_WRITE) != 0)   return true;
        if ((flags & AccessFlags.WRITE_UPDATE) == 0) return false;
        if (mode == WriteMode.MODIFY_EMPTY)          return !isPresent(currentValue);
        return true;
    }

    /**
     * Repo-level: может ли субъект выполнить любую update/delete-операцию на типе.
     * {@code WriteMode} здесь не релевантен (применяется только к полю); проверяем
     * исключительно {@link AccessFlags#WRITE_UPDATE}.
     */
    public boolean canModify() {
        if ((flags & AccessFlags.ROOT_WRITE) != 0)   return true;
        return (flags & AccessFlags.WRITE_UPDATE) != 0;
    }

    /** Эквивалент {@link #canModify()} — DELETE концептуально UPDATE с обнулением. */
    public boolean canDelete() { return canModify(); }

    /**
     * Возвращает true, если возможна хотя бы одна из операций (INSERT или UPDATE).
     *
     * @deprecated используйте {@link #canInsert()} / {@link #canUpdate(Object)} раздельно.
     */
    @Deprecated
    public boolean canWrite(Object currentValue) {
        return canInsert() || canUpdate(currentValue);
    }

    public boolean bypassesInstanceFilters() {
        return (flags & (AccessFlags.ADMIN_READ | AccessFlags.ADMIN_WRITE
                       | AccessFlags.ROOT_READ  | AccessFlags.ROOT_WRITE)) != 0;
    }

    /** Пересечение прав: AND-маска флагов, наиболее строгий WriteMode. */
    public AccessLevel intersect(AccessLevel other) {
        return new AccessLevel(this.flags & other.flags, this.mode.strictest(other.mode));
    }

    /** Объединение прав (union): OR-маска флагов; ANY-мода берётся, если хоть у одного ANY. */
    public AccessLevel union(AccessLevel other) {
        WriteMode m = (this.mode == WriteMode.ANY || other.mode == WriteMode.ANY)
                ? WriteMode.ANY : this.mode.strictest(other.mode);
        return new AccessLevel(this.flags | other.flags, m);
    }

    private static boolean isPresent(Object v) {
        if (v == null) return false;
        if (v instanceof CharSequence cs) return !cs.isEmpty();
        if (v instanceof Collection<?> c) return !c.isEmpty();
        if (v instanceof Map<?, ?> m)     return !m.isEmpty();
        return true;
    }
}
