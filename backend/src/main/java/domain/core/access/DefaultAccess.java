package domain.core.access;

/**
 * Enum-фасад над {@link AccessLevel}-комбинациями для использования в значениях аннотаций.
 *
 * <p>Java JLS §9.6.1 запрещает {@code record} в значениях аннотаций — поэтому используется enum.
 */
public enum DefaultAccess {

    HIDDEN     (AccessFlags.NONE,                          WriteMode.ANY),
    READ_ONLY  (AccessFlags.READ,                          WriteMode.ANY),
    WRITE_ONLY (AccessFlags.WRITE,                         WriteMode.WRITE_ONLY),
    READ_WRITE (AccessFlags.READ | AccessFlags.WRITE,      WriteMode.ANY),
    INIT_ONCE  (AccessFlags.READ | AccessFlags.WRITE,      WriteMode.MODIFY_EMPTY);

    public final int       flags;
    public final WriteMode mode;

    DefaultAccess(int f, WriteMode m) {
        this.flags = f;
        this.mode  = m;
    }

    public AccessLevel toLevel() {
        return new AccessLevel(flags, mode);
    }
}
