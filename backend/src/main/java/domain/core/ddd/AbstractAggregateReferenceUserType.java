package domain.core.ddd;

import domain.core.access.AccessContext;
import org.hibernate.HibernateException;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.metamodel.spi.ValueAccess;
import org.hibernate.usertype.CompositeUserType;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * База для {@code AggregateReferenceXxxUserType}-семейства.
 *
 * <p>Контракт Hibernate 6.6 {@link CompositeUserType}: {@link #embeddable()} возвращает
 * реально аннотированный {@code @Embeddable}-класс, а {@code disassemble}/{@code assemble}/
 * {@code replace} идут без {@code SharedSessionContractImplementor}-параметра.
 */
public abstract class AbstractAggregateReferenceUserType<ID extends Serializable>
        implements CompositeUserType<AggregateReference<?, ?>> {

    /** Mapping-class — реально аннотированный {@code @Embeddable}. */
    protected abstract Class<?> mappingEmbeddable();

    /** Конкретный Java-тип id-колонки (UUID/Long/String). */
    protected abstract Class<ID> idClass();

    @Override
    public Object getPropertyValue(AggregateReference<?, ?> component, int property) {
        if (component == null) return null;
        // Hibernate 6 индексирует properties embeddable'а в алфавитном порядке имён:
        //   0 = targetIdRaw ('I' < 'T'), 1 = targetTypeId.
        return switch (property) {
            case 0 -> decodeForColumn(component.targetIdRaw());
            case 1 -> component.targetTypeId();
            default -> throw new HibernateException("Unknown property index: " + property);
        };
    }

    @Override
    public AggregateReference<?, ?> instantiate(ValueAccess values, SessionFactoryImplementor sf) {
        // Тот же алфавитный порядок, что и в getPropertyValue.
        Object id   = values.getValue(0, idClass());     // targetIdRaw
        Long typeId = values.getValue(1, Long.class);    // targetTypeId
        if (typeId == null || id == null) return null;
        String raw = isSystemSentinel(id) ? AccessContext.SYSTEM_PRINCIPAL_ID : IdCodec.encode(id);
        return AggregateReference.ofRaw(typeId, raw);
    }

    @Override public Class<?> embeddable() { return mappingEmbeddable(); }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public Class<AggregateReference<?, ?>> returnedClass() {
        return (Class) AggregateReference.class;
    }

    @Override
    public boolean equals(AggregateReference<?, ?> a, AggregateReference<?, ?> b) {
        return Objects.equals(a, b);
    }

    @Override
    public int hashCode(AggregateReference<?, ?> r) {
        return r == null ? 0 : r.hashCode();
    }

    @Override
    public AggregateReference<?, ?> deepCopy(AggregateReference<?, ?> v) {
        return v;   // immutable
    }

    @Override public boolean isMutable() { return false; }

    @Override
    public Serializable disassemble(AggregateReference<?, ?> v) {
        return v;
    }

    @Override
    public AggregateReference<?, ?> assemble(Serializable s, Object owner) {
        return (AggregateReference<?, ?>) s;
    }

    @Override
    public AggregateReference<?, ?> replace(AggregateReference<?, ?> det, AggregateReference<?, ?> man,
                                            Object owner) {
        return det;
    }

    @SuppressWarnings("unchecked")
    private ID decodeForColumn(String raw) {
        if (raw == null) return null;
        if (AccessContext.SYSTEM_PRINCIPAL_ID.equals(raw)) {
            // Round-trip "__SYSTEM__" → зарезервированное значение типа колонки → "__SYSTEM__".
            return (ID) systemSentinelForIdClass();
        }
        return (ID) IdCodec.decode(raw, idClass());
    }

    /**
     * Зарезервированное значение колонки для {@link AccessContext#SYSTEM_PRINCIPAL_ID},
     * не совпадающее с генерируемыми id: {@code UUID} → nil-UUID, {@code Long}/{@code Integer}
     * → {@code 0} (приложение использует строго положительные id), {@code String} → {@code "__SYSTEM__"}.
     */
    private Object systemSentinelForIdClass() {
        Class<ID> t = idClass();
        if (t == UUID.class)    return new UUID(0L, 0L);
        if (t == Long.class)    return 0L;
        if (t == Integer.class) return 0;
        if (t == String.class)  return AccessContext.SYSTEM_PRINCIPAL_ID;
        throw new IdCodec.UnsupportedIdException(t);
    }

    /** Симметричный обратный тест для {@link #systemSentinelForIdClass()}. */
    private boolean isSystemSentinel(Object id) {
        Class<ID> t = idClass();
        if (t == UUID.class)
            return id instanceof UUID u && u.getMostSignificantBits() == 0 && u.getLeastSignificantBits() == 0;
        if (t == Long.class)    return id instanceof Long l && l == 0L;
        if (t == Integer.class) return id instanceof Integer i && i == 0;
        if (t == String.class)  return AccessContext.SYSTEM_PRINCIPAL_ID.equals(id);
        return false;
    }
}
