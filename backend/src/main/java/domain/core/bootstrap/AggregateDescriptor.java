package domain.core.bootstrap;

import domain.core.access.AccessLevel;

import java.io.Serializable;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/** Иммутабельный runtime-дескриптор одного агрегата. */
public final class AggregateDescriptor {

    private final long typeId;
    private final Class<?> javaClass;
    private final String tableName;
    private final Class<? extends Serializable> idClass;
    private final boolean softDelete;
    private final boolean accessFiltered;
    private final AccessLevel defaultRepoAccess;
    private final int prefetchDepth;
    private final List<FieldDescriptor> fields;
    private final Map<Long, FieldDescriptor> fieldByFieldId;
    private final Map<String, FieldDescriptor> fieldByPropertyName;
    private final Map<String, FieldDescriptor> fieldByShortName;

    public AggregateDescriptor(long typeId, Class<?> javaClass, String tableName,
                               Class<? extends Serializable> idClass,
                               boolean softDelete, boolean accessFiltered,
                               AccessLevel defaultRepoAccess, int prefetchDepth,
                               List<FieldDescriptor> fields,
                               Map<Long, FieldDescriptor> fieldByFieldId,
                               Map<String, FieldDescriptor> fieldByPropertyName,
                               Map<String, FieldDescriptor> fieldByShortName) {
        this.typeId = typeId;
        this.javaClass = javaClass;
        this.tableName = tableName;
        this.idClass = idClass;
        this.softDelete = softDelete;
        this.accessFiltered = accessFiltered;
        this.defaultRepoAccess = defaultRepoAccess;
        this.prefetchDepth = prefetchDepth;
        this.fields = List.copyOf(fields);
        this.fieldByFieldId = Map.copyOf(fieldByFieldId);
        this.fieldByPropertyName = Map.copyOf(fieldByPropertyName);
        this.fieldByShortName = Map.copyOf(fieldByShortName);
    }

    public long typeId()                          { return typeId; }
    public Class<?> javaClass()                   { return javaClass; }
    public String tableName()                     { return tableName; }
    public Class<? extends Serializable> idClass() { return idClass; }
    public boolean softDelete()                   { return softDelete; }
    public boolean isAccessFiltered()             { return accessFiltered; }
    public AccessLevel defaultRepoAccess()        { return defaultRepoAccess; }
    public int prefetchDepth()                    { return prefetchDepth; }
    public List<FieldDescriptor> fields()         { return fields; }

    public FieldDescriptor field(long fieldId) {
        return fieldByFieldId.get(fieldId);
    }

    public FieldDescriptor fieldByPropertyName(String prop) {
        return fieldByPropertyName.get(prop);
    }

    public FieldDescriptor fieldByName(String name) {
        return fieldByShortName.get(name);
    }

    /**
     * Резолв FieldDescriptor по полному path'у от корня агрегата.
     * Stack — родительские fieldId'ы; propertyName — имя текущего leaf-property.
     */
    public FieldDescriptor fieldByPath(Deque<Long> parentStack, String propertyName) {
        for (FieldDescriptor fd : fields) {
            if (!fd.shortName().equals(propertyName)) continue;
            if (matchesStack(fd, parentStack)) return fd;
        }
        return null;
    }

    private boolean matchesStack(FieldDescriptor fd, Deque<Long> parentStack) {
        long[] expected = parentStack.stream().mapToLong(Long::longValue).toArray();
        if (fd.parentChain().length != expected.length) return false;
        return Arrays.equals(fd.parentChain(), expected);
    }
}
