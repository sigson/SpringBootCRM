package domain.core.bootstrap;

import domain.core.access.AccessLevel;

import java.lang.reflect.Field;

/**
 * Runtime-дескриптор одного {@code @FieldId}-поля агрегата (или embedded'а).
 * Хранит готовый {@link Field}-reference со {@code setAccessible(true)} — никакого
 * повторного {@code findField} в горячем пути.
 *
 */
public final class FieldDescriptor {

    private final long fieldId;
    private final String name;             // полный path ("cookies.security.ipAddress")
    private final String shortName;        // последний сегмент path'а
    private final String propertyName;     // hibernate property name
    private final long[] parentChain;      // цепочка fieldId'ов родителей от корня
    private final long parentFieldId;      // -1 для top-level
    private final Class<?> javaType;
    private final Field rawField;
    private final AccessLevel defaultAccess;
    private final boolean isAggregateReference;
    /**
     * TypeId'ы целевых агрегатов ссылки. Для моно-ссылки — один элемент; для
     * union-ссылки — несколько. Для не-ref-полей — пустой массив. На этапе
     * сбора метадаты (фаза 1) тут лежит sentinel {@code {-2}}, который фаза 2
     * ({@code resolveAggregateRefTypeIds}) заменяет реальными typeId'ами.
     */
    private final long[] referencedTypeIds;
    /**
     * {@code true}, если ссылка помечена маркером {@code AnyReference.class} — может
     * указывать на любой зарегистрированный тип с подходящим id-типом. В этом случае
     * {@link #referencedTypeIds} намеренно пуст (конкретные цели не перечисляются):
     * валидатор принимает любой зарегистрированный {@code targetTypeId}, а UI-слой
     * раскрывает выбор среди всех подходящих типов.
     */
    private boolean anyReference;
    private final boolean isElementCollection;
    private final Class<?> elementType;

    private FieldDescriptor[] parentDescriptorChain = new FieldDescriptor[0];

    public FieldDescriptor(long fieldId, String name, String shortName, String propertyName,
                           long[] parentChain, long parentFieldId,
                           Class<?> javaType, Field rawField,
                           AccessLevel defaultAccess,
                           boolean isAggregateReference, long[] referencedTypeIds,
                           boolean isElementCollection, Class<?> elementType) {
        this.fieldId = fieldId;
        this.name = name;
        this.shortName = shortName;
        this.propertyName = propertyName;
        this.parentChain = parentChain.clone();
        this.parentFieldId = parentFieldId;
        this.javaType = javaType;
        this.rawField = rawField;
        rawField.setAccessible(true);
        this.defaultAccess = defaultAccess;
        this.isAggregateReference = isAggregateReference;
        this.referencedTypeIds = referencedTypeIds == null ? new long[0] : referencedTypeIds.clone();
        this.isElementCollection = isElementCollection;
        this.elementType = elementType;
    }

    public long fieldId()                  { return fieldId; }
    public String name()                   { return name; }
    public String shortName()              { return shortName; }
    public String propertyName()           { return propertyName; }
    public long[] parentChain()            { return parentChain.clone(); }
    public long parentFieldId()            { return parentFieldId; }
    public Class<?> javaType()             { return javaType; }
    public Field rawField()                { return rawField; }
    public AccessLevel defaultAccess()     { return defaultAccess; }
    public boolean isAggregateReference()  { return isAggregateReference; }

    /** Все допустимые typeId'ы ссылки (≥1 для ref-поля; для union — несколько). */
    public long[] referencedTypeIds()      { return referencedTypeIds.clone(); }

    /** {@code true}, если ссылка может указывать более чем на один тип. */
    public boolean isUnionReference()      { return referencedTypeIds.length > 1; }

    /**
     * {@code true}, если ссылка помечена {@code AnyReference.class} — допустим любой
     * зарегистрированный тип (с подходящим id-типом). См. {@link #anyReference}.
     */
    public boolean isAnyReference()        { return anyReference; }

    /** Bootstrap-only: помечает поле как any-reference (вызывается из MetadataBootstrapper). */
    void setAnyReference(boolean v)        { this.anyReference = v; }

    /**
     * Первый (или единственный) целевой typeId — для моно-ссылок и для мест, где
     * достаточно одного значения (граф достижимости, простые резолверы).
     * Возвращает {@code -1L}, если ссылка не резолвлена.
     */
    public long referencedTypeId() {
        return referencedTypeIds.length == 0 ? -1L : referencedTypeIds[0];
    }

    public boolean isElementCollection()   { return isElementCollection; }
    public Class<?> elementType()          { return elementType; }

    /** Чтение значения из instance'а (для embedded — обход родительской цепочки). */
    public Object read(Object root) {
        try {
            Object container = walkParents(root);
            if (container == null) return null;
            return rawField.get(container);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Field read failed: " + name, e);
        }
    }

    public void write(Object root, Object value) {
        try {
            Object container = walkParents(root);
            if (container == null) {
                throw new IllegalStateException(
                        "Cannot write to " + name + ": parent is null");
            }
            rawField.set(container, value);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Field write failed: " + name, e);
        }
    }

    private Object walkParents(Object root) throws IllegalAccessException {
        if (parentChain.length == 0) return root;
        Object current = root;
        for (FieldDescriptor parent : parentDescriptorChain) {
            current = parent.rawField.get(current);
            if (current == null) return null;
        }
        return current;
    }

    void setParentDescriptorChain(FieldDescriptor[] chain) {
        this.parentDescriptorChain = chain;
    }
}
