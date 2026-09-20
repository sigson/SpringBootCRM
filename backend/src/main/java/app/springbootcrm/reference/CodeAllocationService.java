package app.springbootcrm.reference;

import app.springbootcrm.user.User;

import org.springframework.stereotype.Service;

import java.util.function.Predicate;

/**
 * Тонкий фасад над {@link CodeGenerator} для REST-endpoint'а
 * {@code GET /api/references/{slug}/next-code}.
 *
 * <p>Bound generic-параметра — {@code Class<?>}, т.к. User наследует
 * {@code UserAggregate}, а не {@link AbstractReferenceAggregate}. CodeGenerator
 * проверяет аннотацию {@code @Reference}, а не type-hierarchy.
 *
 * <p>Счётчик инкрементируется даже если запись в итоге не создастся (fail-safe
 * против race conditions).
 */
@Service
public class CodeAllocationService {

    private final CodeGenerator generator;

    public CodeAllocationService(CodeGenerator generator) {
        this.generator = generator;
    }

    public String allocate(Class<?> refClass, Predicate<String> uniquenessFn) {
        return generator.nextFor(refClass, uniquenessFn);
    }

    /**
     * Блокова видача {@code count} послідовних кодів за одну транзакцію — для
     * масової генерації тестових даних. Уникает {@code count} окремих
     * REQUIRES_NEW-викликів.
     */
    public java.util.List<String> allocateBlock(Class<?> refClass, int count) {
        return generator.nextBlock(refClass, count);
    }
}
