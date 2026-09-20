package app.springbootcrm.metadata;

import app.springbootcrm.metadata.TypeRegistry.TypeDescriptor;
import domain.core.ddd.AbstractAggregate;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.lang.reflect.Field;
import java.util.List;

/**
 * SPI для резолва ссылок на агрегат типа T.
 *
 * <p>Вместо if/else-каскада по typeId каждый домен регистрирует свой {@code ReferenceProvider}
 * как Spring-bean. {@link ReferenceResolver} автоматически строит карту {@code typeId → provider}
 * и диспетчеризует за O(1).
 *
 * <p>Добавление нового агрегата = добавление одного {@code @Component}-bean'а, без правок
 * {@code ReferenceResolver}.
 *
 * @param <T> тип агрегата
 */
public interface ReferenceProvider<T extends AbstractAggregate<?>> {

    /** typeId агрегата, который обслуживает этот провайдер. */
    long typeId();

    /**
     * {@code true}, если провайдер сгенерирован {@code ReferenceProviderAutoRegistrar},
     * а не написан вручную. {@link ReferenceResolver} при коллизии typeId отдаёт приоритет
     * рукописному — это механизм переопределения дефолтного поведения типа.
     */
    default boolean generated() {
        return false;
    }

    /**
     * Список всех инстансов этого типа для построения picker'а.
     * Должен возвращать инстансы через {@code AggregateRepository}-репо: row-level
     * фильтры применятся автоматически через {@link domain.core.persistence.AccessFilterActivator}.
     *
     * @param query опциональная строка поиска (по code/name/displayName, на усмотрение провайдера)
     */
    List<T> list(String query);

    /**
     * Репозиторий агрегата как {@link JpaSpecificationExecutor} для SQL-фильтрации страниц.
     * {@code null} (метод не переопределён) → {@link ReferenceResolver} откатывается к
     * in-memory-фильтрации. Row-level security применяется автоматически через
     * {@link domain.core.persistence.AccessFilterActivator} (паттерн {@code find*}).
     */
    default JpaSpecificationExecutor<T> filterRepository() {
        return null;
    }

    /** Поиск по raw-id (UUID для всех агрегатов в SpringBootCRM). */
    java.util.Optional<T> findById(String idRaw);

    /**
     * Строит читаемую строку для отображения ссылки на запись через
     * {@link TypeDescriptor#displayPattern()} и {@link DisplayPatternRenderer}.
     */
    default String display(T entity, TypeDescriptor td) {
        return DisplayPatternRenderer.render(td.displayPattern(), entity);
    }

    /**
     * Извлекает справочный код агрегата (поле {@code code}). По умолчанию —
     * reflection-поиск поля с именем {@code "code"}; если у агрегата нет такого
     * поля, возвращается {@code null}. Конкретный {@code ReferenceProvider} может
     * переопределить метод, если имя поля отличается.
     */
    default String code(T entity) {
        return readStringField(entity, "code");
    }

    /**
     * Извлекает справочное наименование агрегата (поле {@code name}). Симметрично
     * {@link #code(AbstractAggregate)}.
     */
    default String name(T entity) {
        return readStringField(entity, "name");
    }

    /** Простой reflection-helper: читает String-поле или возвращает null. */
    private static String readStringField(Object entity, String fieldName) {
        if (entity == null) return null;
        Class<?> c = entity.getClass();
        while (c != null && c != Object.class) {
            try {
                Field f = c.getDeclaredField(fieldName);
                if (f.getType() != String.class) return null;
                f.setAccessible(true);
                return (String) f.get(entity);
            } catch (NoSuchFieldException ignored) {
                c = c.getSuperclass();
            } catch (IllegalAccessException e) {
                return null;
            }
        }
        return null;
    }
}
