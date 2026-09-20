package domain.core.validation;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Рантайм-представление одного ограничения на реквизите: пара «экземпляр
 * аннотации + {@link FieldValidator}». Вся логика проверки живёт в default-методе
 * интерфейса-валидатора; здесь — только склейка и общая семантика «пустое
 * значение пропускаем» (кроме валидатора обязательности, см.
 * {@link FieldValidator#appliesToEmpty()}).
 *
 * <p>В классе нет перечня видов валидации и {@code switch} по ним: какие
 * аннотации являются ограничениями, определяет наличие зарегистрированного
 * {@link FieldValidator} для типа аннотации.
 */
public final class FieldConstraint {

    private final Annotation annotation;
    @SuppressWarnings("rawtypes")
    private final FieldValidator validator;

    FieldConstraint(Annotation annotation, FieldValidator<?> validator) {
        this.annotation = annotation;
        this.validator = validator;
    }

    /**
     * Проверяет значение, делегируя валидатору. {@code null} — значение валидно.
     * Пустое значение пропускается, если валидатор не объявил обратного.
     */
    @SuppressWarnings("unchecked")
    public String validate(Object value) {
        String s = value == null ? "" : String.valueOf(value).trim();
        if (s.isEmpty() && !validator.appliesToEmpty()) return null;
        ValidationResult r = validator.validate(annotation, s);
        return r.valid() ? null : r.message();
    }

    /** Параметры ограничения (min/max/maxLength/pattern…) для подсказок фронтенда. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> meta() {
        return validator.meta(annotation);
    }

    /**
     * Собирает все ограничения, объявленные аннотациями на данном поле. Аннотация
     * считается ограничением, если для её типа есть {@link FieldValidator}
     * (в карте {@code validators}); остальные аннотации игнорируются.
     */
    public static List<FieldConstraint> fromField(
            Field f, Map<Class<? extends Annotation>, FieldValidator<?>> validators) {
        List<FieldConstraint> out = new ArrayList<>(4);
        for (Annotation a : f.getAnnotations()) {
            FieldValidator<?> v = validators.get(a.annotationType());
            if (v != null) out.add(new FieldConstraint(a, v));
        }
        return out;
    }
}
