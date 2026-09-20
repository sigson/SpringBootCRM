package domain.core.ddd;

import app.springbootcrm.metadata.TypeRegistry;

import java.io.Serializable;

/**
 * Маркер-цель для <b>union-ссылки на любой тип</b>.
 *
 * <p>Используется исключительно в {@code @ValidAggregateRef(targets = AnyReference.class, …)}:
 * указание {@code AnyReference.class} среди {@code targets} означает, что в поле
 * {@code AggregateReference} можно записать ссылку на <b>любой</b> зарегистрированный
 * агрегат, чей id-тип совпадает с {@code idType()} поля (физическое ограничение двух
 * колонок {@code ref_type_id} + {@code ref_id} никуда не девается — разные id-типы в
 * одной паре колонок непредставимы).
 *
 * <p>Это <b>не сущность</b>: класс никогда не аннотируется {@code @Entity}/{@code @TypeId},
 * не попадает в {@code MetadataSnapshot}, не мапится Hibernate'ом и не имеет таблицы.
 * Он существует только как compile-time литерал-маркер, распознаваемый:
 * <ul>
 *   <li>{@code MetadataBootstrapper} — помечает поле как «any-reference»
 *       ({@link FieldDescriptor#isAnyReference()}), не раскрывая конкретные typeId'ы;</li>
 *   <li>{@code ValidAggregateRefValidator} — принимает любой зарегистрированный
 *       {@code targetTypeId} с декодируемым {@code targetIdRaw};</li>
 *   <li>UI-слоем ({@code TypeRegistry}) — поле отдаётся фронту с флагом «any»,
 *       и пикер предлагает выбор среди всех подходящих типов.</li>
 * </ul>
 *
 * <p>Приватный конструктор не даёт ни инстанцировать маркер, ни наследоваться от него —
 * он только литерал {@code AnyReference.class}.
 */
public abstract class AnyReference extends AbstractAggregate<Serializable> {

    private AnyReference() {
        throw new UnsupportedOperationException(
                "AnyReference — маркер-цель union-ссылки, не предназначен для инстанцирования");
    }

    @Override
    public Serializable getId() {
        throw new UnsupportedOperationException("AnyReference is a marker, it has no identity");
    }
}
