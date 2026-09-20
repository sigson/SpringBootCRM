package app.springbootcrm.reporting;

import app.springbootcrm.metadata.AggregateClassification;
import app.springbootcrm.metadata.MetadataGraphService;
import app.springbootcrm.metadata.TypeRegistry;
import app.springbootcrm.reference.ReferenceAggregate;

import app.springbootcrm.metadata.UiAggregate;
import domain.core.bootstrap.AggregateDescriptor;
import domain.core.bootstrap.FieldDescriptor;
import domain.core.bootstrap.MetadataSnapshot;
import domain.core.bootstrap.MetadataSnapshotProvider;
import jakarta.annotation.PostConstruct;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Синхронизатор универсального reference-lookup view.
 *
 * <p>На старте (после {@code metadataBootstrapper}) перегенерирует:
 * <ul>
 *   <li><b>{@code reference_lookup}</b> — {@code UNION ALL} по всем агрегатам
 *       {@code @UiAggregate(isReference=true)}, у которых есть поля {@code code} и
 *       {@code name}. Форма: {@code (type_id, id, code, name, display)}. Любой
 *       отчёт может приджойниться к ней по {@code (type_id, id)} и получить
 *       читаемую подпись любой ссылки — в том числе для типов, добавленных позже
 *       (на следующем рестарте view подхватит их сама).</li>
 *   <li><b>Узкие union-view</b> {@code reference_lookup_u_<sorted typeIds>} — по
 *       одной на каждый уникальный набор {@code targets} union-ссылочного поля
 *       (из {@code @ValidAggregateRef}). Дают планировщику сразу видеть границу
 *       (меньше веток UNION ALL → эффективнее pruning) и используются ссылочным
 *       режимом конструктора запросов для duck-typing'а «стандартных» реквизитов
 *       (Код/Наименование) поверх union-полей.</li>
 * </ul>
 *
 * <p><b>Idempotent.</b> Перегенерация происходит, только если изменился hash
 * отсортированного списка веток (typeId+table+наборы). Это убирает гонку при
 * рестарте и лишний DDL.
 *
 * <p><b>H2/PostgreSQL.</b> Используются только совместимые конструкции:
 * {@code CREATE OR REPLACE VIEW}, {@code CAST(... AS BIGINT/VARCHAR)},
 * конкатенация {@code ||}. Проверено для H2 (MODE=PostgreSQL) и PostgreSQL.
 */
@Component
@DependsOn("metadataBootstrapper")
public class ReferenceLookupViewSynchronizer {

    private static final Logger log = LoggerFactory.getLogger(ReferenceLookupViewSynchronizer.class);
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*$");

    @PersistenceContext
    private EntityManager em;

    private final MetadataSnapshotProvider snapshots;
    private final TransactionTemplate txTemplate;

    public ReferenceLookupViewSynchronizer(MetadataSnapshotProvider snapshots,
                                           PlatformTransactionManager txManager) {
        this.snapshots = snapshots;
        this.txTemplate = new TransactionTemplate(txManager);
    }

    @PostConstruct
    public void rebuild() {
        // @Transactional на @PostConstruct не работает (бин ещё не обёрнут прокси),
        // а executeUpdate требует активной транзакции — поэтому TransactionTemplate.
        txTemplate.executeWithoutResult(status -> doRebuild());
    }

    void doRebuild() {
        MetadataSnapshot snap = snapshots.get();

        // 1) Ветки reference_lookup: все справочники с code+name.
        List<Branch> branches = collectReferenceBranches(snap);
        if (branches.isEmpty()) {
            log.info("ReferenceLookupViewSynchronizer: no catalog with code+name - keeping the skeleton");
            return;
        }
        branches.sort(Comparator.comparingLong(b -> b.typeId));

        // 2) Уникальные наборы union-целей (для узких view).
        Set<TreeSet<Long>> unionSets = collectUnionTargetSets(snap);

        // 3) Idempotency-hash: ветки + наборы.
        String hash = computeHash(branches, unionSets);
        if (hash.equals(currentVersion())) {
            log.debug("ReferenceLookupViewSynchronizer: hash unchanged - the view is up to date");
            return;
        }

        // 4) reference_lookup
        String mainDdl = "CREATE OR REPLACE VIEW reference_lookup AS\n"
                + branches.stream().map(Branch::sql).collect(Collectors.joining("\n  UNION ALL\n"));
        em.createNativeQuery(mainDdl).executeUpdate();

        // 5) Узкие union-view (подмножества веток по typeId).
        for (TreeSet<Long> set : unionSets) {
            List<Branch> subset = branches.stream()
                    .filter(b -> set.contains(b.typeId))
                    .toList();
            if (subset.size() < 2) continue;   // не-union или не все цели — справочники; пропускаем
            String name = unionViewName(set);
            if (!SAFE_IDENTIFIER.matcher(name).matches()) continue;
            String ddl = "CREATE OR REPLACE VIEW " + name + " AS\n"
                    + subset.stream().map(Branch::sql).collect(Collectors.joining("\n  UNION ALL\n"));
            em.createNativeQuery(ddl).executeUpdate();
        }

        saveVersion(hash);
        log.info("ReferenceLookupViewSynchronizer: reference_lookup regenerated ({} branches, {} union view)",
                branches.size(), unionSets.size());
    }

    // ------------------------------------------------------------------ build

    private List<Branch> collectReferenceBranches(MetadataSnapshot snap) {
        List<Branch> out = new ArrayList<>();
        for (AggregateDescriptor agg : snap.allAggregates()) {
            UiAggregate ui = agg.javaClass().getAnnotation(UiAggregate.class);
            if (ui == null) continue;
            // Класифікація — за фактом дочірності ReferenceAggregate (а не за
            // ручним прапорцем). Узгоджено з TypeRegistry/MetadataGraphService.
            if (!app.springbootcrm.metadata.AggregateClassification.isReference(agg.javaClass())) continue;
            // Нужны именно свойства code+name (есть у всех справочников).
            if (agg.fieldByPropertyName("code") == null || agg.fieldByPropertyName("name") == null) continue;
            String table = agg.tableName();
            if (!SAFE_IDENTIFIER.matcher(table).matches()) continue;
            out.add(new Branch(agg.typeId(), table));
        }
        return out;
    }

    private Set<TreeSet<Long>> collectUnionTargetSets(MetadataSnapshot snap) {
        Set<TreeSet<Long>> sets = new LinkedHashSet<>();
        for (AggregateDescriptor agg : snap.allAggregates()) {
            for (FieldDescriptor fd : agg.fields()) {
                if (!fd.isUnionReference()) continue;
                TreeSet<Long> set = new TreeSet<>();
                for (long t : fd.referencedTypeIds()) if (t > 0) set.add(t);
                if (set.size() >= 2) sets.add(set);
            }
        }
        return sets;
    }

    /** Детерминированное имя узкой union-view: {@code reference_lookup_u_<ids asc по _>}. */
    public static String unionViewName(Set<Long> typeIds) {
        String ids = typeIds.stream().sorted()
                .map(String::valueOf).collect(Collectors.joining("_"));
        return "reference_lookup_u_" + ids;
    }

    // ------------------------------------------------------------- versioning

    private String currentVersion() {
        try {
            Object v = em.createNativeQuery(
                    "SELECT content_hash FROM reference_lookup_version WHERE id = 1")
                    .getResultList().stream().findFirst().orElse(null);
            return v == null ? null : v.toString();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void saveVersion(String hash) {
        // upsert одной строки id=1 (MERGE совместим с H2; для Postgres — fallback через delete+insert).
        try {
            em.createNativeQuery(
                    "MERGE INTO reference_lookup_version (id, content_hash, updated_at) " +
                    "KEY(id) VALUES (1, ?1, CURRENT_TIMESTAMP)")
                    .setParameter(1, hash)
                    .executeUpdate();
        } catch (RuntimeException mergeNotSupported) {
            em.createNativeQuery("DELETE FROM reference_lookup_version WHERE id = 1").executeUpdate();
            em.createNativeQuery(
                    "INSERT INTO reference_lookup_version (id, content_hash, updated_at) " +
                    "VALUES (1, ?1, CURRENT_TIMESTAMP)")
                    .setParameter(1, hash)
                    .executeUpdate();
        }
    }

    private static String computeHash(List<Branch> branches, Set<TreeSet<Long>> unionSets) {
        StringBuilder sb = new StringBuilder("v1|");
        for (Branch b : branches) sb.append(b.typeId).append(':').append(b.table).append(';');
        sb.append("|U|");
        unionSets.stream().map(ReferenceLookupViewSynchronizer::unionViewName).sorted()
                .forEach(n -> sb.append(n).append(';'));
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte x : d) hex.append(String.format("%02x", x));
            return hex.toString();
        } catch (Exception e) {
            return Integer.toHexString(sb.toString().hashCode());
        }
    }

    /** Одна ветка UNION ALL: унифицирует таблицу-справочник к форме reference_lookup. */
    private record Branch(long typeId, String table) {
        String sql() {
            // display = code || ' — ' || name (согласовано с displayPattern «{code} — {name}»).
            //
            // КАЖДАЯ колонка приводится к VARCHAR без длины — это не косметика.
            // PostgreSQL разрешает CREATE OR REPLACE VIEW, только если типы колонок
            // совпадают с предыдущим определением. Скелет view из V1__initial.sql
            // объявляет code/name/display как CAST(NULL AS VARCHAR), тогда как в
            // таблицах это varchar(50)/varchar(200), а '||' в PG даёт text. Без
            // явных CAST'ов PG падает на «cannot change data type of view column».
            // H2 к этому безразличен, поэтому расхождение не всплывало.
            return "  SELECT CAST(" + typeId + " AS BIGINT) AS type_id, "
                    + "CAST(id AS VARCHAR) AS id, "
                    + "CAST(code AS VARCHAR) AS code, "
                    + "CAST(name AS VARCHAR) AS name, "
                    + "CAST(code || ' — ' || name AS VARCHAR) AS display FROM " + table;
        }
    }
}
