package domain.core.access;

/**
 * Унифицированная флаговая шкала прав.
 *
 * <p>Любая комбинация прав = OR битов; любая проверка = AND с маской.
 * <p>Импликация флагов раскрывается через {@link #expand(int)}:
 * <ul>
 *   <li>{@code ROOT_WRITE}  ⇒ ROOT_READ | ADMIN_WRITE | ADMIN_READ | WRITE | READ</li>
 *   <li>{@code ROOT_READ}   ⇒ ADMIN_READ | READ</li>
 *   <li>{@code ADMIN_WRITE} ⇒ ADMIN_READ | WRITE | READ</li>
 *   <li>{@code ADMIN_READ}  ⇒ READ</li>
 * </ul>
 *
 * <p><b>Расщепление WRITE на INSERT/UPDATE.</b> {@link #WRITE_INSERT} (bit 1) — создание
 * новых записей (PreInsert); {@link #WRITE_UPDATE} (bit 6) — изменение/удаление существующих
 * (PreUpdate, PreDelete). {@link #WRITE} = их объединение. Раздельные биты моделируют права
 * «только добавление» / «только редактирование» через {@code AccessMetric.typeFlags}.
 */
public final class AccessFlags {

    private AccessFlags() {}

    public static final int NONE         = 0;

    /** Plain READ — подчиняется всем фильтрам и field-level правилам. */
    public static final int READ         = 1 << 0;   // 0x01

    /** Право создавать новые экземпляры агрегата (insert). */
    public static final int WRITE_INSERT = 1 << 1;   // 0x02

    /** ADMIN_READ — обходит instance-level (Hibernate filters, ownAccess); не обходит field-level. */
    public static final int ADMIN_READ   = 1 << 2;   // 0x04
    /** ADMIN_WRITE — обходит instance-level; неявно включает оба WRITE-бита. */
    public static final int ADMIN_WRITE  = 1 << 3;   // 0x08

    /** ROOT_READ — дополнительно обходит field-level HIDDEN/WRITE_ONLY. */
    public static final int ROOT_READ    = 1 << 4;   // 0x10
    /** ROOT_WRITE — обходит вообще всё, включая INIT_ONCE на непустом значении. */
    public static final int ROOT_WRITE   = 1 << 5;   // 0x20

    /** Право изменять или удалять существующие экземпляры (update/delete). */
    public static final int WRITE_UPDATE = 1 << 6;   // 0x40

    /**
     * Совмещённый ярлык «WRITE как операция». Эквивалент {@code WRITE_INSERT | WRITE_UPDATE}.
     * Используется в {@link DefaultAccess#READ_WRITE}/{@code WRITE_ONLY}/{@code INIT_ONCE} —
     * на field-level различение insert/update нерелевантно, важен факт «можно писать в поле».
     * На repo-level биты должны выставляться раздельно для тонкого разграничения дозвола.
     */
    public static final int WRITE        = WRITE_INSERT | WRITE_UPDATE;   // 0x42

    /**
     * Расширяет флаги по правилу импликации. Идемпотентна:
     * {@code expand(expand(x)) == expand(x)}.
     *
     * <p>{@code WRITE_INSERT} и {@code WRITE_UPDATE} НЕ имплицируют друг друга
     * — это намеренно, чтобы выдача «только INSERT» не подразумевала «UPDATE».
     * Однако {@code ADMIN_WRITE}/{@code ROOT_WRITE} имплицируют ОБА, потому что
     * роль админа — «может всё».
     */
    public static int expand(int flags) {
        int r = flags;
        if ((r & ROOT_WRITE)  != 0) r |= ROOT_READ | ADMIN_WRITE | ADMIN_READ | WRITE | READ;
        if ((r & ROOT_READ)   != 0) r |= ADMIN_READ | READ;
        if ((r & ADMIN_WRITE) != 0) r |= ADMIN_READ | WRITE | READ;
        if ((r & ADMIN_READ)  != 0) r |= READ;
        return r;
    }

    /** Истина, если в flags есть все требуемые биты required (с учётом импликации). */
    public static boolean has(int flags, int required) {
        return (expand(flags) & required) == required;
    }
}
