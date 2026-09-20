package domain.core.web;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.BeanPropertyWriter;
import domain.core.access.AccessContext;
import domain.core.access.AccessContextHolder;
import domain.core.access.AccessLevel;
import domain.core.access.AccessResolver;
import domain.core.ddd.AbstractAggregate;

import java.util.Optional;

/**
 * BeanPropertyWriter, который перед сериализацией одного поля резолвит {@link AccessLevel}
 * через {@link AccessResolver} и маскирует {@code !canRead()} поля.
 *
 * <p>Не наследуем напрямую от {@link BeanPropertyWriter} — оборачиваем (delegation).
 * Это даёт устойчивость к смене внутреннего API Jackson'а.
 */
final class AccessAwarePropertyWriter extends BeanPropertyWriter {

    private final BeanPropertyWriter delegate;
    private final long typeId;
    private final long fieldId;
    private final AccessContextHolder holder;
    private final AccessResolver resolver;
    private final boolean maskWithNull;

    AccessAwarePropertyWriter(BeanPropertyWriter delegate,
                              long typeId, long fieldId,
                              AccessContextHolder holder,
                              AccessResolver resolver,
                              boolean maskWithNull) {
        super(delegate);
        this.delegate = delegate;
        this.typeId = typeId;
        this.fieldId = fieldId;
        this.holder = holder;
        this.resolver = resolver;
        this.maskWithNull = maskWithNull;
    }

    @Override
    public void serializeAsField(Object bean, JsonGenerator gen, SerializerProvider prov) throws Exception {
        if (shouldMask(bean)) {
            if (maskWithNull) {
                gen.writeFieldName(getName());
                gen.writeNull();
            }
            // else: skip
            return;
        }
        delegate.serializeAsField(bean, gen, prov);
    }

    @Override
    public void serializeAsElement(Object bean, JsonGenerator gen, SerializerProvider prov) throws Exception {
        if (shouldMask(bean)) {
            if (maskWithNull) gen.writeNull();
            return;
        }
        delegate.serializeAsElement(bean, gen, prov);
    }

    private boolean shouldMask(Object bean) {
        if (!(bean instanceof AbstractAggregate<?> agg)) return false;
        Optional<AccessContext> ctxOpt = holder.tryGet();
        if (ctxOpt.isEmpty()) return false;   // outbox/audit/no-context → не маскируем
        AccessContext ctx = ctxOpt.get();
        if (ctx.isSystemMaxPrivileged()) return false;
        AccessLevel lvl = resolver.resolve(typeId, fieldId, agg, ctx);
        return !lvl.canRead();
    }
}
