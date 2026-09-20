package domain.core.access;

/**
 * Направление проекции прав.
 *
 * <p>{@code OUTBOUND} — сериализация для клиента; применяются read-rules.
 * <p>{@code INBOUND} — десериализация от клиента (применение DTO к агрегату);
 * применяются write-rules.
 */
public enum ProjectionDirection {
    OUTBOUND,
    INBOUND
}
