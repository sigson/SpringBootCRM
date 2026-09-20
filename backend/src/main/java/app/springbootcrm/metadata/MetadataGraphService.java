package app.springbootcrm.metadata;

import app.springbootcrm.reporting.ReferenceLookupViewSynchronizer;
import domain.core.bootstrap.AggregateDescriptor;
import domain.core.bootstrap.FieldDescriptor;
import domain.core.bootstrap.MetadataSnapshot;
import domain.core.bootstrap.MetadataSnapshotProvider;
import jakarta.annotation.PostConstruct;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Id;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;

/**
 * Строит «граф бизнес-объектов» для <b>ссылочного режима</b> конструктора запросов.
 *
 * <p>Бэкенд здесь — лишь проводник в мир Java-метаданных и структуры БД: он отдаёт,
 * какие есть типы, как они ложатся в таблицы, какие реквизиты ссылочные (и на какие
 * типы могут указывать — с учётом union), а также физические имена колонок
 * дискриминатора/FK. Вся «магия джойнов» — генерация конкретного SQL под конкретный
 * запрос — делается конструктором на фронтенде поверх этого графа.
 *
 * <p>Граф строится один раз на старте из {@link MetadataSnapshot} (immutable),
 * поэтому эндпоинт отдаёт закешированный объект.
 */
@Service
@DependsOn("metadataBootstrapper")
public class MetadataGraphService {

    private final MetadataSnapshotProvider snapshots;
    private volatile GraphResponse cached;

    public MetadataGraphService(MetadataSnapshotProvider snapshots) {
        this.snapshots = snapshots;
    }

    @PostConstruct
    public void build() {
        MetadataSnapshot snap = snapshots.get();
        List<GraphType> types = new ArrayList<>();
        for (AggregateDescriptor agg : snap.allAggregates()) {
            UiAggregate ui = agg.javaClass().getAnnotation(UiAggregate.class);
            if (ui == null) continue;                       // не UI-тип — в граф не попадает
            types.add(buildType(agg, ui, snap));
        }
        types.sort((a, b) -> a.pluralLabel().compareToIgnoreCase(b.pluralLabel()));
        this.cached = new GraphResponse(types);
    }

    public GraphResponse graph() {
        return cached != null ? cached : new GraphResponse(List.of());
    }

    // ------------------------------------------------------------------ build

    private GraphType buildType(AggregateDescriptor agg, UiAggregate ui, MetadataSnapshot snap) {
        String idColumn = extractIdColumn(agg.javaClass());
        List<GraphField> fields = new ArrayList<>();
        for (FieldDescriptor fd : agg.fields()) {
            if (fd.parentChain().length > 0) continue;      // только top-level реквизиты
            if (isInternalField(fd)) continue;              // служебные — не реквизиты
            fields.add(buildField(fd, snap));
        }
        return new GraphType(
                agg.typeId(), ui.slug(), agg.tableName(), idColumn,
                ui.singularLabel(), ui.pluralLabel(), ui.displayPattern(),
                AggregateClassification.isReference(agg.javaClass()), fields);
    }

    private GraphField buildField(FieldDescriptor fd, MetadataSnapshot snap) {
        String label = labelOf(fd);
        if (fd.isAggregateReference()) {
            String discriminatorCol = extractRefColumn(fd.rawField(), "targetTypeId",
                    fd.shortName() + "_type_id");
            String idCol = extractRefColumn(fd.rawField(), "targetIdRaw",
                    fd.shortName() + "_id");

            // Any-reference: ссылка на любой тип. Раскрываем для конструктора запросов
            // полный перечень совместимых типов (тип-иерархия), но БЕЗ union-view —
            // общих для всех объектов БД реквизитов гарантированно нет, поэтому ссылка
            // разворачивается только в выбор конкретного типа (см. фронт RefDeref).
            if (fd.isAnyReference()) {
                domain.core.ddd.annotations.ValidAggregateRef var =
                        fd.rawField().getAnnotation(domain.core.ddd.annotations.ValidAggregateRef.class);
                Class<?> idType = (var != null) ? var.idType() : java.util.UUID.class;
                List<Long> anyTargets = anyReferenceTargets(snap, idType);
                return new GraphField(fd.shortName(), label, "REF", null,
                        anyTargets, discriminatorCol, idCol, /*unionView*/ null, /*anyReference*/ true);
            }

            List<Long> refTypeIds = new ArrayList<>();
            for (long t : fd.referencedTypeIds()) if (t > 0) refTypeIds.add(t);
            String unionView = null;
            if (refTypeIds.size() >= 2) {
                TreeSet<Long> set = new TreeSet<>(refTypeIds);
                unionView = ReferenceLookupViewSynchronizer.unionViewName(set);
            }
            return new GraphField(fd.shortName(), label, "REF", null,
                    refTypeIds, discriminatorCol, idCol, unionView, /*anyReference*/ false);
        }
        String column = extractScalarColumn(fd.rawField(), fd.shortName());
        return new GraphField(fd.shortName(), label, "SCALAR", column,
                List.of(), null, null, null, /*anyReference*/ false);
    }

    /**
     * Тип-иерархия для any-reference: все UI-типы графа, чей id-класс совпадает с
     * {@code idType} ссылки (физически совместимы для cast-join по id-колонке).
     * Табличные части исключаем — они не являются самостоятельными целями ссылки.
     */
    private List<Long> anyReferenceTargets(MetadataSnapshot snap, Class<?> idType) {
        List<Long> out = new ArrayList<>();
        for (AggregateDescriptor agg : snap.allAggregates()) {
            if (agg.javaClass().getAnnotation(UiAggregate.class) == null) continue;
            if (snap.tabularOwnerTypeId(agg.typeId()) != null) continue;
            Class<?> idc = snap.idClassByTypeIdOrNull(agg.typeId());
            if (idc != null && idc.equals(idType)) out.add(agg.typeId());
        }
        out.sort(Comparator.naturalOrder());
        return out;
    }

    private static boolean isInternalField(FieldDescriptor fd) {
        String n = fd.shortName();
        // Технические поля доступа/версионирования — НЕ реквизиты (их не показываем).
        if (n.equals("version") || n.equals("ownAccess") || n.equals("access")) return true;
        // AccessMetric-поле (own_access) — тоже служебное.
        if (fd.javaType().getSimpleName().equals("AccessMetric")) return true;
        // Аудит-поля (createdAt/updatedAt/createdBy/updatedBy) — полноценные реквизиты
        // (авторы/даты), которые пользователь должен видеть в ссылочном дереве. У них
        // нет @UiField, поэтому лейбл берёт fallback на java-имя поля (см. labelOf).
        // createdBy/updatedBy — ссылочные реквизиты (AggregateReference), поэтому
        // становятся навигируемыми, как и любая другая ссылка.
        return false;
    }

    private static String labelOf(FieldDescriptor fd) {
        UiField uif = fd.rawField().getAnnotation(UiField.class);
        if (uif != null && !uif.label().isEmpty()) return uif.label();
        return fd.shortName();
    }

    // ---------------------------------------------------------- column lookup

    private static String extractIdColumn(Class<?> cls) {
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.isAnnotationPresent(Id.class)) {
                    Column col = f.getAnnotation(Column.class);
                    if (col != null && !col.name().isEmpty()) return col.name();
                    return f.getName();
                }
            }
        }
        return "id";
    }

    private static String extractScalarColumn(Field f, String fallback) {
        Column c = f.getAnnotation(Column.class);
        if (c != null && !c.name().isEmpty()) return c.name();
        return fallback;
    }

    /** Колонка одного из под-атрибутов AggregateReference (targetTypeId/targetIdRaw). */
    private static String extractRefColumn(Field f, String attrName, String fallback) {
        AttributeOverrides overrides = f.getAnnotation(AttributeOverrides.class);
        if (overrides != null) {
            for (AttributeOverride a : overrides.value()) {
                if (attrName.equals(a.name()) && !a.column().name().isEmpty()) {
                    return a.column().name();
                }
            }
        }
        AttributeOverride single = f.getAnnotation(AttributeOverride.class);
        if (single != null && attrName.equals(single.name()) && !single.column().name().isEmpty()) {
            return single.column().name();
        }
        return fallback;
    }

    // ----------------------------------------------------------------- shapes

    public record GraphResponse(List<GraphType> types) {}

    public record GraphType(
            long typeId,
            String slug,
            String table,
            String idColumn,
            String singularLabel,
            String pluralLabel,
            String displayPattern,
            boolean isReference,
            List<GraphField> fields
    ) {}

    /**
     * Один реквизит типа.
     * <ul>
     *   <li>{@code kind="SCALAR"} → заполнен {@code column} (физическая колонка);</li>
     *   <li>{@code kind="REF"} → заполнены {@code refTypeIds} (1=моно, ≥2=union),
     *       {@code refTypeIdColumn} (колонка-дискриминатор) и {@code refIdColumn}
     *       (колонка-FK); для union — также {@code unionViewName} (узкая
     *       reference-lookup view для duck-typing'а стандартных реквизитов).</li>
     * </ul>
     */
    public record GraphField(
            String name,
            String label,
            String kind,
            String column,
            List<Long> refTypeIds,
            String refTypeIdColumn,
            String refIdColumn,
            String unionViewName,
            /**
             * {@code true} — реквизит помечен маркером {@code AnyReference}: ссылка на
             * любой тип. {@code refTypeIds} раскрыт в полную тип-иерархию (все совместимые
             * по id-типу типы), {@code unionViewName} = {@code null} (общих реквизитов нет).
             * Конструктор запросов разворачивает такую ссылку только в выбор конкретного типа.
             */
            boolean anyReference
    ) {}
}
