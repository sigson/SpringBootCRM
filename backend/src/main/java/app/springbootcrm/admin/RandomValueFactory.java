package app.springbootcrm.admin;

import domain.core.bootstrap.FieldDescriptor;
import domain.core.validation.NumberRange;
import domain.core.validation.TextLength;
import jakarta.persistence.Column;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Генератор випадкових <b>скалярних</b> значень реквізиту з урахуванням обмежень
 * метамоделі (довжина рядка, числовий діапазон, тип Java). Ссылочні поля
 * генеруються окремо в {@link DataGenService} (потрібен доступ до репозиторіїв
 * цільових типів).
 *
 * <p>Усі значення-рядки за можливості несуть маркер-флаг, аби згенеровані записи
 * легко впізнавалися візуально (надійне видалення все одно йде через
 * {@link DataGenLog}).
 */
final class RandomValueFactory {

    private RandomValueFactory() {}

    /** Маркер у текстових полях (дзеркало {@link DataGenService#GEN_FLAG}). */
    static final String FLAG = DataGenService.GEN_FLAG;

    /**
     * Випадкове значення для скалярного (не-ref) поля {@code fd}. Може повернути
     * {@code null}, якщо тип не підтримується (тоді поле просто не заповнюється).
     *
     * @param flagged якщо {@code true} — текстове значення міститиме {@link #FLAG}
     *                (для головного «назвового» поля довідника)
     */
    static Object forField(FieldDescriptor fd, boolean flagged) {
        Class<?> t = fd.javaType();
        ThreadLocalRandom rnd = ThreadLocalRandom.current();

        if (t == String.class) {
            return randomString(fd, flagged);
        }
        if (t == boolean.class || t == Boolean.class) {
            return rnd.nextBoolean();
        }
        if (t == BigDecimal.class) {
            return randomDecimal(fd);
        }
        if (t == int.class || t == Integer.class) {
            int[] r = intRange(fd);
            return rnd.nextInt(r[0], r[1]);
        }
        if (t == long.class || t == Long.class) {
            int[] r = intRange(fd);
            return (long) rnd.nextInt(r[0], r[1]);
        }
        if (t == double.class || t == Double.class) {
            return randomDecimal(fd).doubleValue();
        }
        if (t == float.class || t == Float.class) {
            return randomDecimal(fd).floatValue();
        }
        if (t == short.class || t == Short.class) {
            return (short) rnd.nextInt(0, 1000);
        }
        if (t == LocalDate.class) {
            return LocalDate.now().plusDays(rnd.nextLong(-365, 365));
        }
        if (t == Instant.class) {
            return Instant.now()
                    .plus(rnd.nextLong(-30L * 24, 30L * 24), ChronoUnit.HOURS)
                    .truncatedTo(ChronoUnit.MINUTES);
        }
        if (t.isEnum()) {
            Object[] consts = t.getEnumConstants();
            return consts.length == 0 ? null : consts[rnd.nextInt(consts.length)];
        }
        return null;   // непідтримуваний тип — пропускаємо
    }

    // ------------------------------------------------------------------ strings

    private static String randomString(FieldDescriptor fd, boolean flagged) {
        int max = maxLen(fd);
        int min = minLen(fd);
        String suffix = shortRandom();

        // EMAIL-евристика за іменем поля.
        String name = fd.shortName().toLowerCase();
        String base;
        if (name.contains("email")) {
            base = "gen_" + suffix + "@gen.local";
        } else if (name.contains("login") || name.equals("username")) {
            base = "gen_user_" + suffix;
        } else if (flagged) {
            base = FLAG + " " + capitalize(fd.shortName()) + " " + suffix;
        } else {
            base = capitalize(fd.shortName()) + " " + suffix;
        }

        // Доведення до [min, max].
        if (base.length() > max) {
            base = base.substring(0, max);
        }
        while (base.length() < min) {
            base = base + "x";
            if (base.length() > max) { base = base.substring(0, max); break; }
        }
        return base;
    }

    private static int maxLen(FieldDescriptor fd) {
        TextLength tl = fd.rawField().getAnnotation(TextLength.class);
        if (tl != null && tl.max() > 0 && tl.max() < Integer.MAX_VALUE) return tl.max();
        Column col = fd.rawField().getAnnotation(Column.class);
        if (col != null && col.length() > 0 && col.length() < 4000) return col.length();
        return 64;
    }

    private static int minLen(FieldDescriptor fd) {
        TextLength tl = fd.rawField().getAnnotation(TextLength.class);
        return tl != null ? Math.max(0, tl.min()) : 0;
    }

    // ------------------------------------------------------------------ numbers

    /** Числовий діапазон [lo, hi) для цілих, узгоджений з {@link NumberRange}. */
    private static int[] intRange(FieldDescriptor fd) {
        NumberRange nr = fd.rawField().getAnnotation(NumberRange.class);
        int lo = 0, hi = 1000;
        if (nr != null) {
            if (nr.min() != Double.NEGATIVE_INFINITY) {
                lo = (int) Math.ceil(nr.min());
                if (!nr.minInclusive()) lo += 1;
            }
            if (nr.max() != Double.POSITIVE_INFINITY) {
                hi = (int) Math.floor(nr.max());
                if (nr.maxInclusive()) hi += 1;   // bound exclusive у nextInt
            }
        }
        if (hi <= lo) hi = lo + 1;
        return new int[]{lo, hi};
    }

    /** Випадковий {@link BigDecimal} у межах {@link NumberRange} (scale 2). */
    private static BigDecimal randomDecimal(FieldDescriptor fd) {
        NumberRange nr = fd.rawField().getAnnotation(NumberRange.class);
        double lo = 0d, hi = 1000d;
        if (nr != null) {
            if (nr.min() != Double.NEGATIVE_INFINITY) lo = nr.min();
            if (nr.max() != Double.POSITIVE_INFINITY) hi = nr.max();
        }
        // Звужуємо межі, щоб не впертися в exclusive-границі.
        double span = hi - lo;
        double pad = span > 0 ? span * 0.001 : 0.0;
        double effLo = lo + (nr != null && !nr.minInclusive() ? Math.max(pad, 0.01) : 0);
        double effHi = hi - (nr != null && !nr.maxInclusive() ? Math.max(pad, 0.01) : 0);
        if (effHi <= effLo) effHi = effLo + 0.01;
        double v = ThreadLocalRandom.current().nextDouble(effLo, effHi);
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP);
    }

    // ------------------------------------------------------------------ helpers

    static String shortRandom() {
        return java.util.UUID.randomUUID().toString().substring(0, 8);
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** Чи є поле обов'язковим (NOT NULL у БД або @Required). */
    static boolean isRequired(FieldDescriptor fd) {
        Field raw = fd.rawField();
        if (raw.isAnnotationPresent(domain.core.validation.Required.class)) return true;
        Column col = raw.getAnnotation(Column.class);
        return col != null && !col.nullable();
    }
}
