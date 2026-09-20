package domain.core.web;

import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializationConfig;
import com.fasterxml.jackson.databind.ser.BeanPropertyWriter;
import com.fasterxml.jackson.databind.ser.BeanSerializerModifier;
import domain.core.access.AccessContext;
import domain.core.access.AccessContextHolder;
import domain.core.access.AccessLevel;
import domain.core.access.AccessResolver;
import domain.core.bootstrap.AggregateDescriptor;
import domain.core.bootstrap.FieldDescriptor;
import domain.core.bootstrap.MetadataSnapshot;
import domain.core.bootstrap.MetadataSnapshotProvider;
import domain.core.ddd.AbstractAggregate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Jackson-модификатор сериализации, реализующий field-level READ-маскирование.
 *
 * <p><b>Зачем.</b> Поля агрегата имеют {@code @FieldId(defaultAccess=…)}. Сериализация
 * должна автоматически маскировать поля с уровнем доступа, ниже текущего пользователя
 * — без ручных {@code basic()/full()} DTO-проекций в app-коде.
 *
 * <p><b>Как.</b> Для каждого {@link AbstractAggregate}-класса при первой сериализации
 * Jackson вызывает {@link #changeProperties} → мы строим список writer'ов: каждый
 * оборачивается в {@link AccessAwarePropertyWriter}, который на каждом вызове
 * {@code serializeAsField} читает текущий {@link AccessContext} и резолвит
 * {@link AccessLevel} через {@link AccessResolver}.
 *
 * <p>Если {@code !lvl.canRead()} — поле:
 * <ul>
 *   <li>пропускается (не сериализуется), если {@code maskWithNull=false};</li>
 *   <li>выдаётся как {@code null}, если {@code maskWithNull=true} (по умолчанию).</li>
 * </ul>
 * Выдача {@code null} удобнее для клиента: схема ответа стабильна, поле просто пустое.
 *
 * <p><b>Когда не маскируем:</b> отсутствует {@link AccessContext} (например, прямая
 * сериализация в outbox) — все поля проходят без проверок. Это сознательная семантика:
 * core-каналы (outbox, audit log) пишут «как есть», маскирование — для outbound REST.
 *
 * <p>Регистрируется в {@link AccessAwareObjectMapperConfig}.
 */
public class AccessAwareSerializerModifier extends BeanSerializerModifier {

    private final MetadataSnapshotProvider snapshots;
    private final AccessContextHolder holder;
    private final AccessResolver resolver;
    private final boolean maskWithNull;

    public AccessAwareSerializerModifier(MetadataSnapshotProvider snapshots,
                                          AccessContextHolder holder,
                                          AccessResolver resolver,
                                          boolean maskWithNull) {
        this.snapshots = snapshots;
        this.holder = holder;
        this.resolver = resolver;
        this.maskWithNull = maskWithNull;
    }

    @Override
    public List<BeanPropertyWriter> changeProperties(SerializationConfig config,
                                                      BeanDescription beanDesc,
                                                      List<BeanPropertyWriter> beanProperties) {
        Class<?> beanCls = beanDesc.getBeanClass();
        if (!AbstractAggregate.class.isAssignableFrom(beanCls)) {
            return beanProperties;
        }
        MetadataSnapshot snap = snapshots.get();
        long typeId;
        try {
            typeId = snap.typeIdOf(beanCls);
        } catch (IllegalStateException e) {
            return beanProperties;   // не tracked агрегат
        }
        AggregateDescriptor desc = snap.aggregate(typeId);

        // Имена JSON-полей, которые мы хотим суметь скрыть
        Set<String> wrapped = new HashSet<>();
        List<BeanPropertyWriter> out = new ArrayList<>(beanProperties.size());
        for (BeanPropertyWriter w : beanProperties) {
            String propName = w.getName();
            FieldDescriptor fd = desc.fieldByPropertyName(propName);
            if (fd == null) {
                // Поле не имеет @FieldId — пропускаем без обёртки
                out.add(w);
                continue;
            }
            out.add(new AccessAwarePropertyWriter(w, typeId, fd.fieldId(), holder, resolver, maskWithNull));
            wrapped.add(propName);
        }
        return out;
    }
}
