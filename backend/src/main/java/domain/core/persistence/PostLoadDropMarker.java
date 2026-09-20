package domain.core.persistence;

import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Request-scoped маркер «отфильтрованных» (но загруженных) сущностей.
 * Используется {@code PostLoadAccessCheckListener}'ом в режиме {@code MARK_AND_DROP}.
 *
 * <p>Отмеченные entity ВСЁ ЕЩЁ managed (не nullятся, не выбрасываются из persistence-context'а)
 * — это позволяет dirty-check'у Hibernate'а работать корректно. Фильтрация происходит на
 * уровне ответа {@code Page}/итератора в {@code AccessAwarePage}.
 *
 */
@Component
@RequestScope(proxyMode = org.springframework.context.annotation.ScopedProxyMode.TARGET_CLASS)
public class PostLoadDropMarker {

    private final Map<Object, Boolean> dropped =
            Collections.synchronizedMap(new IdentityHashMap<>());
    private final AtomicInteger droppedCount = new AtomicInteger();

    public void mark(Object entity) {
        if (entity == null) return;
        if (dropped.put(entity, Boolean.TRUE) == null) {
            droppedCount.incrementAndGet();
        }
    }

    public boolean isMarked(Object entity) {
        return entity != null && Boolean.TRUE.equals(dropped.get(entity));
    }

    public int droppedCount() { return droppedCount.get(); }

    public boolean hadAnyDrops() { return droppedCount.get() > 0; }
}
