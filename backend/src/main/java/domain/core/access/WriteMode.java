package domain.core.access;

/**
 * Квалификатор записи.
 */
public enum WriteMode {

    /** Запись разрешена всегда при наличии бита WRITE. Дефолт. */
    ANY,

    /** Init-once: запись разрешена ТОЛЬКО пока текущее значение пустое. */
    MODIFY_EMPTY,

    /** Write-only: чтение запрещено даже при READ (если нет ROOT_READ). */
    WRITE_ONLY;

    /** При пересечении прав берём наиболее СТРОГИЙ режим. */
    public WriteMode strictest(WriteMode other) {
        if (this == WRITE_ONLY    || other == WRITE_ONLY)    return WRITE_ONLY;
        if (this == MODIFY_EMPTY  || other == MODIFY_EMPTY)  return MODIFY_EMPTY;
        return ANY;
    }
}
