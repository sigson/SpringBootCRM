package app.springbootcrm.reference;

import app.springbootcrm.reference.CodeGenerator;
import app.springbootcrm.reference.ReferenceSequence;
import app.springbootcrm.reference.ReferenceSequenceRepository;
import app.springbootcrm.reference.UnknownReferenceException;

import domain.core.access.AccessContextHolder;
import domain.core.access.SystemAccessContexts;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration-test атомарной генерации кодов через {@link CodeGenerator}.
 *
 * <p>Использует {@code nextForTypeId(long, prefix, width, null, predicate)} с
 * вымышленными typeId (9_999_xxx), не конфликтующими с реальными агрегатами.
 *
 * <p><b>NB:</b> {@code bumpAndGet} выполняется в REQUIRES_NEW-транзакции, поэтому
 * тесты нельзя оборачивать в {@code @Transactional} (иначе внешняя транзакция не
 * увидит commit'ов счётчика, а lock'и держались бы). Вместо этого делаем явную
 * cleanup-логику через отдельные свободные строки.
 */
@SpringBootTest
@ActiveProfiles("test")
class CodeGeneratorIT {

    private static final long TEST_TYPE_ID_A = 9_999_001L;
    private static final long TEST_TYPE_ID_B = 9_999_002L;

    @Autowired private CodeGenerator generator;
    @Autowired private ReferenceSequenceRepository sequences;
    @Autowired private AccessContextHolder holder;
    @Autowired private SystemAccessContexts systems;

    @Test
    void sequential_calls_return_incrementing_codes() throws IOException {
        sequences.deleteById(TEST_TYPE_ID_A);
        try (var ignored = holder.bind(systems.maxPrivileges())) {
            String c1 = generator.nextForTypeId(TEST_TYPE_ID_A, "TST", 8, null, c -> true);
            String c2 = generator.nextForTypeId(TEST_TYPE_ID_A, "TST", 8, null, c -> true);
            String c3 = generator.nextForTypeId(TEST_TYPE_ID_A, "TST", 8, null, c -> true);

            assertThat(c1).isEqualTo("TST00001");
            assertThat(c2).isEqualTo("TST00002");
            assertThat(c3).isEqualTo("TST00003");

            ReferenceSequence row = sequences.findById(TEST_TYPE_ID_A).orElse(null);
            assertThat(row).isNotNull();
            assertThat(row.getNextSeq()).isEqualTo(4L);
        }
        sequences.deleteById(TEST_TYPE_ID_A);
    }

    @Test
    void different_type_ids_have_independent_counters() throws IOException {
        sequences.deleteById(TEST_TYPE_ID_A);
        sequences.deleteById(TEST_TYPE_ID_B);
        try (var ignored = holder.bind(systems.maxPrivileges())) {
            String a1 = generator.nextForTypeId(TEST_TYPE_ID_A, "AA", 6, null, c -> true);
            String b1 = generator.nextForTypeId(TEST_TYPE_ID_B, "BB", 6, null, c -> true);
            String a2 = generator.nextForTypeId(TEST_TYPE_ID_A, "AA", 6, null, c -> true);

            assertThat(a1).isEqualTo("AA0001");
            assertThat(b1).isEqualTo("BB0001");
            assertThat(a2).isEqualTo("AA0002");
        }
        sequences.deleteById(TEST_TYPE_ID_A);
        sequences.deleteById(TEST_TYPE_ID_B);
    }

    @Test
    void zero_width_throws_unknown_reference() throws IOException {
        try (var ignored = holder.bind(systems.maxPrivileges())) {
            assertThatThrownBy(() ->
                    generator.nextForTypeId(TEST_TYPE_ID_A, "X", 0, null, c -> true))
                    .isInstanceOf(UnknownReferenceException.class);
        }
    }

    @Test
    void no_prefix_pads_full_width() throws IOException {
        long uniqueTypeId = 9_999_010L;
        sequences.deleteById(uniqueTypeId);
        try (var ignored = holder.bind(systems.maxPrivileges())) {
            String c = generator.nextForTypeId(uniqueTypeId, "", 9, null, p -> true);
            assertThat(c).isEqualTo("000000001");
        }
        sequences.deleteById(uniqueTypeId);
    }

    @Test
    void uniqueness_predicate_skips_taken_codes() throws IOException {
        long uniqueTypeId = 9_999_020L;
        sequences.deleteById(uniqueTypeId);
        try (var ignored = holder.bind(systems.maxPrivileges())) {
            // First 3 codes are "taken" by user — generator should skip to the 4th.
            String c = generator.nextForTypeId(uniqueTypeId, "U", 5, null,
                    code -> !code.equals("U0001") && !code.equals("U0002") && !code.equals("U0003"));
            assertThat(c).isEqualTo("U0004");
        }
        sequences.deleteById(uniqueTypeId);
    }
}
