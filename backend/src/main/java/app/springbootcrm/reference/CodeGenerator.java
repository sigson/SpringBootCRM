package app.springbootcrm.reference;

import domain.core.ddd.annotations.TypeId;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Сервис атомарной генерации последовательных кодов для справочников.
 *
 * <p>{@link #nextFor(Class, Predicate)} инкрементирует счётчик в БД (таблица
 * {@code reference_sequences}, по {@code type_id}) и возвращает отформатированный код.
 * Инкремент идёт в отдельной транзакции {@code REQUIRES_NEW}, поэтому rollback внешней
 * транзакции его не откатывает (счётчик растёт, даже если запись не создана) — это
 * исключает гонку между конкурентными создателями.
 *
 * <ul>
 *   <li>Если строки-счётчика для {@code typeId} нет (свежая установка / зачистка),
 *       seed берётся из максимума существующих кодов агрегата.</li>
 *   <li>Потолок — min(10^digits - 1, {@code 9999999}), digits = codeWidth - prefixLen;
 *       при превышении — {@link CodeSequenceExhaustedException}.</li>
 *   <li>{@code uniquenessCheck} ({@link Predicate}) пропускает занятые пользователем коды:
 *       генератор инкрементирует дальше (до 16 попыток), затем
 *       {@link CodeSequenceCollisionException}.</li>
 * </ul>
 */
@Service
public class CodeGenerator {

    private static final Logger log = LoggerFactory.getLogger(CodeGenerator.class);

    // Потолок кода определяется ИСКЛЮЧИТЕЛЬНО разрядностью codeWidth (см. ceilingForDigits):
    // жёсткой константы-потолка нет — codeWidth=100 даёт длину кода 100.

    /** Сколько повторных попыток делаем, если пользователь уже занял сгенерированный код. */
    private static final int  COLLISION_RETRY_LIMIT = 16;

    private final ReferenceSequenceRepository sequences;
    private final CodeGenerator self;

    @PersistenceContext
    private EntityManager em;

    /**
     * <b>Self-injection</b> через {@code @Lazy}-proxy нужен, чтобы вызов
     * {@code bumpAndGet} шёл через Spring AOP — иначе {@code @Transactional(REQUIRES_NEW)}
     * не сработает (внутренние вызовы метода того же bean'а минуют AOP-proxy).
     */
    public CodeGenerator(ReferenceSequenceRepository sequences,
                         @org.springframework.context.annotation.Lazy CodeGenerator self) {
        this.sequences = sequences;
        this.self = self;
    }

    // ============================================================================
    // Public API
    // ============================================================================

    /**
     * Генерирует следующий код. {@code uniquenessCheck} обязателен, чтобы генератор
     * мог пропустить коды, которые пользователь уже занял вручную.
     *
     * <pre>{@code
     *   @Transactional
     *   public Foo create(...) {
     *       String code = codeGenerator.nextFor(Foo.class,
     *                                            c -> !fooRepo.existsByCode(c));
     *       ...
     *   }
     * }</pre>
     */
    public String nextFor(Class<?> referenceClass,
                          Predicate<String> uniquenessCheck) {
        Reference ref = referenceClass.getAnnotation(Reference.class);
        if (ref == null) {
            throw new UnknownReferenceException(
                    "Class " + referenceClass.getSimpleName() + " is not annotated with @Reference");
        }
        TypeId typeAnn = referenceClass.getAnnotation(TypeId.class);
        if (typeAnn == null) {
            throw new UnknownReferenceException(
                    "Class " + referenceClass.getSimpleName() + " is not annotated with @TypeId");
        }
        return nextForTypeId(typeAnn.value(), ref.prefix(), ref.codeWidth(),
                referenceClass, uniquenessCheck);
    }

    /**
     * Низкоуровневый вариант: вызывается из {@link #nextFor(Class, Predicate)}
     * и из тестов. {@code aggregateClass} может быть {@code null} — тогда seed-from-DB
     * пропускается.
     */
    public String nextForTypeId(long typeId, String prefix, int codeWidth,
                                Class<?> aggregateClass,
                                Predicate<String> uniquenessCheck) {
        if (codeWidth <= 0) {
            throw new UnknownReferenceException(
                    "codeWidth = " + codeWidth + " for typeId=" + typeId +
                            " - code auto-generation is disabled");
        }
        int prefixLen = prefix == null ? 0 : prefix.length();
        int digits = Math.max(1, codeWidth - prefixLen);
        long effectiveCeiling = ceilingForDigits(digits);

        for (int attempt = 0; attempt < COLLISION_RETRY_LIMIT; attempt++) {
            long next = self.bumpAndGet(typeId, aggregateClass, prefix);
            if (next > effectiveCeiling) {
                throw new CodeSequenceExhaustedException(
                        "The code counter for typeId=" + typeId + " (prefix='" +
                                (prefix == null ? "" : prefix) + "', digits=" + digits +
                                ") reached its ceiling " + effectiveCeiling +
                                ". Increase codeWidth in @Reference, or clear the table.");
            }
            String candidate = formatCode(prefix, codeWidth, next);
            if (uniquenessCheck == null || uniquenessCheck.test(candidate)) {
                return candidate;
            }
            log.debug("CodeGenerator: code '{}' already taken (typeId={}), retrying",
                    candidate, typeId);
        }
        throw new CodeSequenceCollisionException(
                "Could not generate a unique code for typeId=" + typeId +
                        " in " + COLLISION_RETRY_LIMIT + " attempts - too many collisions");
    }

    /**
     * Форматирует код по шаблону {@code prefix + leftPad(value, codeWidth - prefix.length)}.
     */
    public static String formatCode(String prefix, int codeWidth, long value) {
        String p = prefix == null ? "" : prefix;
        if (codeWidth <= 0) return p + value;
        int digits = Math.max(1, codeWidth - p.length());
        String fmt = "%0" + digits + "d";
        return p + String.format(fmt, value);
    }

    /**
     * Потолок последовательности для заданного числа разрядов: {@code 10^digits - 1}.
     *
     * <p>Потолок определяется ИСКЛЮЧИТЕЛЬНО разрядностью ({@code codeWidth - prefixLen}),
     * а не какой-либо вшитой константой: {@code @Reference(codeWidth = 100)} → длина
     * кода 100. Счётчик — {@code long}, поэтому для {@code digits >= 19} (когда {@code 10^digits}
     * выходит за {@code Long.MAX_VALUE}) потолок насыщается до {@code Long.MAX_VALUE} —
     * практически безгранично (раньше исчерпается сам тип). Без {@code Math.pow}/double —
     * чисто целочисленное накопление без потери точности.
     */
    static long ceilingForDigits(int digits) {
        long ceiling = 1L;
        for (int i = 0; i < digits; i++) {
            if (ceiling > Long.MAX_VALUE / 10L) {
                return Long.MAX_VALUE;   // 10^digits превышает long — насыщаем
            }
            ceiling *= 10L;
        }
        return ceiling - 1L;
    }

    // ============================================================================
    // Internal — атомарный инкремент в отдельной транзакции
    // ============================================================================

    /**
     * Инкрементирует счётчик в собственной REQUIRES_NEW-транзакции — чтобы rollback
     * внешней транзакции создателя не откатил счётчик.
     *
     * <p>{@code public} (не {@code private}) — чтобы Spring AOP мог создать proxy.
     * Вызывать только через self-injected proxy {@link #self}.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long bumpAndGet(long typeId,
                           Class<?> aggregateClass,
                           String prefix) {
        ReferenceSequence seq = sequences.findByTypeIdLocked(typeId)
                .orElseGet(() -> {
                    long seedNext = computeSeedNext(aggregateClass, prefix);
                    log.info("CodeGenerator: seeding reference_sequences for typeId={} with nextSeq={}",
                            typeId, seedNext);
                    ReferenceSequence init = new ReferenceSequence(typeId, seedNext);
                    return sequences.saveAndFlush(init);
                });
        long current = seq.getNextSeq();
        seq.setNextSeq(current + 1);
        sequences.save(seq);
        return current;
    }

    // ============================================================================
    // Блокова видача кодів (для масової генерації тестових даних)
    // ============================================================================

    /**
     * Видає {@code count} послідовних кодів за <b>одну</b> транзакцію (один інкремент
     * лічильника на весь блок), а не {@code count} окремих REQUIRES_NEW-викликів —
     * прибирає вузьке місце масової генерації (інакше на 1 млн записів — 1 млн крихітних
     * транзакцій лише заради кодів).
     *
     * <p><b>Без</b> перевірки колізій з ручними кодами: для тестового наповнення це
     * прийнятно (лічильник монотонний, seed-from-DB стартує після наявних кодів).
     * Для одиничного створення (CRUD) лишається {@link #nextFor(Class, java.util.function.Predicate)}
     * з повним захистом.
     */
    public java.util.List<String> nextBlock(Class<?> referenceClass, int count) {
        Reference ref = referenceClass.getAnnotation(Reference.class);
        if (ref == null) {
            throw new UnknownReferenceException(
                    "Class " + referenceClass.getSimpleName() + " is not annotated with @Reference");
        }
        TypeId typeAnn = referenceClass.getAnnotation(TypeId.class);
        if (typeAnn == null) {
            throw new UnknownReferenceException(
                    "Class " + referenceClass.getSimpleName() + " is not annotated with @TypeId");
        }
        return nextBlockForTypeId(typeAnn.value(), ref.prefix(), ref.codeWidth(),
                referenceClass, count);
    }

    public java.util.List<String> nextBlockForTypeId(long typeId, String prefix, int codeWidth,
                                                     Class<?> aggregateClass, int count) {
        if (count <= 0) return java.util.List.of();
        if (codeWidth <= 0) {
            throw new UnknownReferenceException(
                    "codeWidth = " + codeWidth + " for typeId=" + typeId + " - code auto-generation is disabled");
        }
        int prefixLen = prefix == null ? 0 : prefix.length();
        int digits = Math.max(1, codeWidth - prefixLen);
        long effectiveCeiling = ceilingForDigits(digits);

        long first = self.bumpBlockAndGet(typeId, aggregateClass, prefix, count);
        long last = first + count - 1;
        if (last > effectiveCeiling) {
            throw new CodeSequenceExhaustedException(
                    "The code counter for typeId=" + typeId + " (prefix='" +
                            (prefix == null ? "" : prefix) + "', digits=" + digits +
                            ") reached its ceiling " + effectiveCeiling + " while block-allocating " +
                            count + " codes. Reduce the count or increase codeWidth.");
        }
        java.util.List<String> out = new java.util.ArrayList<>(count);
        for (long v = first; v <= last; v++) {
            out.add(formatCode(prefix, codeWidth, v));
        }
        return out;
    }

    /**
     * Інкрементує лічильник на {@code count} за одну REQUIRES_NEW-транзакцію і
     * повертає <b>перше</b> значення блоку (діапазон {@code [first; first+count-1]}).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long bumpBlockAndGet(long typeId, Class<?> aggregateClass, String prefix, int count) {
        ReferenceSequence seq = sequences.findByTypeIdLocked(typeId)
                .orElseGet(() -> {
                    long seedNext = computeSeedNext(aggregateClass, prefix);
                    log.info("CodeGenerator: seeding reference_sequences for typeId={} with nextSeq={}",
                            typeId, seedNext);
                    ReferenceSequence init = new ReferenceSequence(typeId, seedNext);
                    return sequences.saveAndFlush(init);
                });
        long current = seq.getNextSeq();
        seq.setNextSeq(current + count);
        sequences.save(seq);
        return current;
    }

    /**
     * Seed-from-DB: когда в {@code reference_sequences} нет записи для typeId,
     * вычисляем, «каким был бы счётчик, если бы начали сразу после наибольшего
     * существующего кода в репозитории агрегата».
     */
    private long computeSeedNext(Class<?> aggregateClass,
                                 String prefix) {
        if (aggregateClass == null || em == null) return 1L;
        String entityName = aggregateClass.getSimpleName();
        String prefixSafe = prefix == null ? "" : prefix;
        long maxSeq = 0L;
        try {
            java.util.List<String> codes = em.createQuery(
                    "select e.code from " + entityName + " e", String.class).getResultList();
            Pattern tailDigits = Pattern.compile("^" + Pattern.quote(prefixSafe) + "(\\d+)$");
            for (String code : codes) {
                if (code == null) continue;
                Matcher m = tailDigits.matcher(code);
                if (m.matches()) {
                    try {
                        long n = Long.parseLong(m.group(1));
                        if (n > maxSeq) maxSeq = n;
                    } catch (NumberFormatException ignored) { /* skip */ }
                }
            }
        } catch (RuntimeException e) {
            log.warn("CodeGenerator: seed-from-DB failed for {}: {}", entityName, e.getMessage());
        }
        return maxSeq + 1;
    }

    // ============================================================================
    // Exceptions
    // ============================================================================

    /** Последовательность кодов исчерпана (достигла потолка). */
    public static class CodeSequenceExhaustedException extends RuntimeException {
        public CodeSequenceExhaustedException(String m) { super(m); }
    }

    /** Слишком много коллизий с user-supplied кодами подряд. */
    public static class CodeSequenceCollisionException extends RuntimeException {
        public CodeSequenceCollisionException(String m) { super(m); }
    }
}
