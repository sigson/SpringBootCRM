package app.springbootcrm.metadata;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Рендерит {@code displayPattern}-строку через reflection-подстановку полей.
 *
 * <p>Поддерживает плейсхолдеры вида {@code {fieldName}}. Резолв: сначала ищет
 * геттер {@code getFieldName()} / {@code isFieldName()}, затем публичное/доступное поле.
 *
 * <p>Если плейсхолдер не резолвится — вставляет пустую строку (тихий fallback):
 * ошибки рендера UI-слоя не должны ронять весь request.
 */
final class DisplayPatternRenderer {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-zA-Z_][a-zA-Z0-9_]*)\\}");

    private DisplayPatternRenderer() {}

    static String render(String pattern, Object entity) {
        if (pattern == null || pattern.isEmpty() || entity == null) return String.valueOf(entity);
        Matcher m = PLACEHOLDER.matcher(pattern);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String field = m.group(1);
            Object val = resolve(entity, field);
            m.appendReplacement(out, Matcher.quoteReplacement(val == null ? "" : String.valueOf(val)));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static Object resolve(Object obj, String name) {
        String getter = "get" + Character.toUpperCase(name.charAt(0)) + name.substring(1);
        String boolGetter = "is" + Character.toUpperCase(name.charAt(0)) + name.substring(1);
        Class<?> cls = obj.getClass();
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            // Геттер getX()
            try { Method m = c.getDeclaredMethod(getter); m.setAccessible(true); return m.invoke(obj); }
            catch (NoSuchMethodException ignored) {} catch (ReflectiveOperationException e) { return null; }
            // Геттер isX()
            try { Method m = c.getDeclaredMethod(boolGetter); m.setAccessible(true); return m.invoke(obj); }
            catch (NoSuchMethodException ignored) {} catch (ReflectiveOperationException e) { return null; }
            // Прямой доступ к полю
            try { Field f = c.getDeclaredField(name); f.setAccessible(true); return f.get(obj); }
            catch (NoSuchFieldException ignored) {} catch (ReflectiveOperationException e) { return null; }
        }
        return null;
    }
}
