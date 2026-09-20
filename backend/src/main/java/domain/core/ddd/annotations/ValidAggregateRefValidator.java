package domain.core.ddd.annotations;

import domain.core.bootstrap.MetadataSnapshotProvider;
import domain.core.ddd.AbstractAggregate;
import domain.core.ddd.AggregateReference;
import domain.core.ddd.IdCodec;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.io.Serializable;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Runtime-валидатор {@code AggregateReference}-полей.
 *
 * <p>{@code MetadataSnapshotProvider} инжектится через сеттер (Spring fills it in via
 * {@code ConstraintValidatorFactory} с {@code SpringConstraintValidatorFactory}).
 * Если provider null'ом не подвязан — валидатор не падает (валидация считается прошедшей,
 * чтобы Bean Validation не блокировал bootstrap-time).
 */
public class ValidAggregateRefValidator
        implements ConstraintValidator<ValidAggregateRef, AggregateReference<?, ?>> {

    private static volatile MetadataSnapshotProvider PROVIDER;

    /** Вызывается из конфигурации в bootstrap-stage. */
    public static void setProvider(MetadataSnapshotProvider p) {
        PROVIDER = Objects.requireNonNull(p);
    }

    /** Множество допустимых targetTypeId (для union — несколько). Пусто на bootstrap-stage. */
    private Set<Long> allowedTypeIds = Set.of();
    private Class<? extends Serializable> expectedIdType;
    /**
     * {@code true}, если среди {@code targets} присутствует маркер {@code AnyReference.class} —
     * принимается любой зарегистрированный {@code targetTypeId} (с id-типом = {@code expectedIdType})
     * и декодируемым {@code targetIdRaw}, без явного перечня {@link #allowedTypeIds}.
     */
    private boolean acceptAny = false;

    @Override
    public void initialize(ValidAggregateRef a) {
        this.expectedIdType = a.idType();
        for (Class<? extends AbstractAggregate<?>> target : a.targets()) {
            if (target == domain.core.ddd.AnyReference.class) { this.acceptAny = true; }
        }
        var p = PROVIDER;
        if (p != null) {
            p.staticGet().ifPresent(snap -> {
                Set<Long> ids = new HashSet<>();
                for (Class<? extends AbstractAggregate<?>> target : a.targets()) {
                    if (target == domain.core.ddd.AnyReference.class) continue;   // не резолвим маркер
                    try { ids.add(snap.typeIdOf(target)); }
                    catch (RuntimeException ignored) { /* не зарегистрирован — поймает bootstrap */ }
                }
                this.allowedTypeIds = Set.copyOf(ids);
            });
        }
    }

    @Override
    public boolean isValid(AggregateReference<?, ?> v, ConstraintValidatorContext ctx) {
        if (v == null) return true;                 // @NotNull — отдельная аннотация
        if (acceptAny) {
            // any-reference: цель — любой зарегистрированный тип с подходящим id-типом.
            var p = PROVIDER;
            if (p == null) return true;             // bootstrap stage — не валидируем
            return p.staticGet().map(snap -> {
                Class<? extends Serializable> targetIdClass =
                        snap.idClassByTypeIdOrNull(v.targetTypeId());
                if (targetIdClass == null) return false;                 // тип не зарегистрирован
                if (!targetIdClass.equals(expectedIdType)) return false; // несовместимый id-тип
                return IdCodec.tryDecode(v.targetIdRaw(), expectedIdType).isPresent();
            }).orElse(true);
        }
        if (allowedTypeIds.isEmpty()) return true;  // bootstrap stage — не валидируем
        if (!allowedTypeIds.contains(v.targetTypeId())) return false;
        Optional<? extends Serializable> decoded = IdCodec.tryDecode(v.targetIdRaw(), expectedIdType);
        return decoded.isPresent();
    }
}
