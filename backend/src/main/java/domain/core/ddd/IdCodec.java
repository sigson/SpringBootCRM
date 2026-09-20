package domain.core.ddd;

import java.io.Serializable;
import java.util.Optional;
import java.util.UUID;

/**
 * Универсальное кодирование ID разных типов в строку и обратно.
 *
 * <p>Используется в {@code AggregateReference.targetIdRaw}, outbox-payload'ах,
 * cache-ключах. Поддерживает UUID, Long, Integer, String. (TSID можно добавить
 * как extension-point в production через регистрацию через ServiceLoader.)
 */
public final class IdCodec {

    private IdCodec() {}

    public static String encode(Object id) {
        if (id == null) throw new IllegalArgumentException("id is null");
        if (id instanceof UUID u)        return u.toString();
        if (id instanceof Long l)        return l.toString();
        if (id instanceof Integer i)     return i.toString();
        if (id instanceof String s)      return s;
        throw new UnsupportedIdException(id.getClass());
    }

    @SuppressWarnings("unchecked")
    public static <ID> ID decode(String raw, Class<ID> type) {
        if (type == UUID.class)    return (ID) UUID.fromString(raw);
        if (type == Long.class)    return (ID) Long.valueOf(raw);
        if (type == Integer.class) return (ID) Integer.valueOf(raw);
        if (type == String.class)  return (ID) raw;
        throw new UnsupportedIdException(type);
    }

    public static <ID> Optional<ID> tryDecode(String raw, Class<ID> type) {
        try {
            return Optional.of(decode(raw, type));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /** Маркерный сентинель «несуществующего» значения для конкретного id-типа. */
    public static Object denyAllSentinel(Class<? extends Serializable> idType) {
        if (idType == UUID.class)    return new UUID(0L, 0L);
        if (idType == Long.class)    return Long.MIN_VALUE;
        if (idType == Integer.class) return Integer.MIN_VALUE;
        if (idType == String.class)  return "__DENY_ALL__";
        throw new UnsupportedIdException(idType);
    }

    public static final class UnsupportedIdException extends RuntimeException {
        public UnsupportedIdException(Class<?> idType) {
            super("Unsupported id type: " + idType.getName());
        }
    }
}
