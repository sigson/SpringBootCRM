import { useEffect, useMemo, useState } from "react";
import { Navigate } from "react-router-dom";
import { useAuth, isAdmin } from "../auth/AuthProvider";
import { dataGenApi, type PurgeReport, type GenTarget } from "../api/endpoints";
import { Alert, CheckboxField, FormField, PageHead } from "../components/Common";
import { RefField } from "../components/RefField";
import { useApiErrorHandler } from "../components/useApiErrorHandler";
import { useToast } from "../components/Toast";
import { useConfirm } from "../components/ConfirmDialog";
import { ACCESS_ROLE_TYPE_ID } from "../editors/index";

/** TypeId довідника «Інтерфейси» (app.springbootcrm.interfaces.InterfaceLayout). */
const INTERFACE_LAYOUT_TYPE_ID = 9300;

/** Максимум за один виклик (до 1 млрд). */
const MAX_COUNT = 1_000_000_000;

const KIND_LABEL: Record<GenTarget["kind"], string> = {
  REFERENCE: "Catalogs",
  REGISTER: "Registers",
  TABULAR: "Tabular parts",
};
const KIND_ORDER: GenTarget["kind"][] = ["REFERENCE", "REGISTER", "TABULAR"];

/**
 * <h2>Розділ «Генерація даних».</h2>
 *
 * <p>Повністю керується метаданими: перелік типів приходить з бекенду
 * ({@code GET /api/admin/datagen/targets}), форма будується динамічно. У кожного
 * типу — власне поле кількості та власна кнопка. Для табличних частин кількість
 * трактується «на кожного власника».
 *
 * <p>Окрема секція «Користувачі» — з роллю, паролем та <b>інтерфейсом</b> за
 * замовчуванням. Довідники «Ролі» та «Інтерфейси» у переліку відсутні (вони не
 * генеруються випадково).
 */
export function DataGenPage() {
  const { user } = useAuth();
  const handleApiError = useApiErrorHandler();
  const toast = useToast();
  const confirmDialog = useConfirm();

  const [flag, setFlag] = useState<string>("[[GEN]]");
  const [targets, setTargets] = useState<GenTarget[]>([]);

  // Кількість на кожен тип (key = typeId) та busy-стан.
  const [counts, setCounts] = useState<Record<number, string>>({});
  // Benchmark-режим на кожен тип (швидке наповнення; ≤10 кешованих ссилок).
  const [benchmark, setBenchmark] = useState<Record<number, boolean>>({});
  // Без логування на кожен тип (не писати DataGenLog по рядку).
  const [noLogging, setNoLogging] = useState<Record<number, boolean>>({});
  const [busyType, setBusyType] = useState<number | null>(null);

  const [userCount, setUserCount] = useState("10");
  const [defaultRoleId, setDefaultRoleId] = useState<string | null>(null);
  const [defaultInterfaceId, setDefaultInterfaceId] = useState<string | null>(null);
  const [defaultPassword, setDefaultPassword] = useState("Password123");
  const [usersBusy, setUsersBusy] = useState(false);

  const [purgeBusy, setPurgeBusy] = useState(false);
  const [lastPurge, setLastPurge] = useState<PurgeReport | null>(null);

  useEffect(() => {
    dataGenApi.flag().then(r => setFlag(r.flag)).catch(() => {  });
    dataGenApi.targets()
      .then(list => {
        setTargets(list);
        setCounts(Object.fromEntries(list.map(t => [t.typeId, "10"])));
      })
      .catch(err => handleApiError(err));
  }, [handleApiError]);

  const grouped = useMemo(() => {
    const m: Record<string, GenTarget[]> = {};
    for (const t of targets) (m[t.kind] ??= []).push(t);
    return m;
  }, [targets]);

  // Guard: тільки адміни (на випадок прямого переходу за URL).
  if (!isAdmin(user)) {
    return <Navigate to="/" replace />;
  }

  function parseCount(raw: string): number | null {
    const n = Number(raw);
    if (!Number.isInteger(n) || n <= 0) return null;
    if (n > MAX_COUNT) return null;
    return n;
  }

  async function onGenerateType(t: GenTarget) {
    const count = parseCount(counts[t.typeId] ?? "");
    if (count == null) { toast.error(`Count: an integer 1…${MAX_COUNT}`); return; }
    const bench = !!benchmark[t.typeId];
    const noLog = !!noLogging[t.typeId];
    setBusyType(t.typeId);
    try {
      const r = await dataGenApi.generate({ typeId: t.typeId, count, benchmark: bench, noLogging: noLog });
      const suffix = t.kind === "TABULAR" ? ` (by ${count} per owner)` : "";
      const tags = [bench ? "benchmark" : null, noLog ? "without logging" : null].filter(Boolean);
      const mode = tags.length ? ` [${tags.join(", ")}]` : "";
      toast.success(`«${t.pluralLabel}»: created ${r.created}${suffix}${mode}`);
    } catch (err) {
      handleApiError(err);
    } finally {
      setBusyType(null);
    }
  }

  async function onPurgeType(t: GenTarget) {
    const ok = await confirmDialog({
      title: `Clear the whole type «${t.pluralLabel}»?`,
      message: "ALL rows of this type will be deleted - both generated and REAL. " +
               "This cannot be undone. Use it for types filled in «without logging» mode».",
      confirmLabel: "Yes, delete everything",
      kind: "danger",
    });
    if (!ok) return;
    setBusyType(t.typeId);
    try {
      const r = await dataGenApi.purgeType({ typeId: t.typeId });
      toast.success(`«${t.pluralLabel}»: deleted ${r.deleted}`);
    } catch (err) {
      handleApiError(err);
    } finally {
      setBusyType(null);
    }
  }

  async function onGenerateUsers() {
    const count = parseCount(userCount);
    if (count == null) { toast.error(`Count: an integer 1…${MAX_COUNT}`); return; }
    if (defaultPassword.length < 6) { toast.error("Password: at least 6 characters"); return; }
    setUsersBusy(true);
    try {
      const r = await dataGenApi.generateUsers({
        count,
        defaultRoleId,
        defaultPassword,
        defaultInterfaceId,
      });
      toast.success(`Users generated: ${r.created}`);
    } catch (err) {
      handleApiError(err);
    } finally {
      setUsersBusy(false);
    }
  }

  async function onPurge() {
    const ok = await confirmDialog({
      title: "Purge generated data?",
      message: "ALL records created by the generator will be deleted (per the generation log), " +
               "and objects with the marker in the name. Real data is untouched.",
      confirmLabel: "Yes, purge",
      kind: "danger",
    });
    if (!ok) return;
    setPurgeBusy(true);
    try {
      const report = await dataGenApi.purge();
      setLastPurge(report);
      toast.success(`Deleted in total: ${report.total}`);
    } catch (err) {
      handleApiError(err);
    } finally {
      setPurgeBusy(false);
    }
  }

  function renderTargetCard(t: GenTarget) {
    const busy = busyType === t.typeId;
    const desc = t.kind === "TABULAR"
      ? `Rows per EACH owner (${t.ownerLabel ?? "owners"}). ` +
        "Total = count × owner count."
      : "Reference fields are filled with random existing values.";
    return (
      <div className="card" key={t.typeId} style={{ maxWidth: 560 }}>
        <div className="form-grid">
          <FormField
            label={`${t.iconHint} ${t.pluralLabel}`}
            type="number"
            value={counts[t.typeId] ?? ""}
            onChange={v => setCounts(c => ({ ...c, [t.typeId]: v }))}
            description={desc}
          />
          <CheckboxField
            label="Benchmark-mode (fast filling)"
            value={!!benchmark[t.typeId]}
            onChange={v => setBenchmark(b => ({ ...b, [t.typeId]: v }))}
            description={"Maximum insert speed: reference fields are taken from the cache " +
              "(≤10 values per type, assigned round-robin), scalars are shared within the batch. " +
              "Suitable for filling a table with tens or hundreds of millions of rows."}
          />
          <CheckboxField
            label="Without logging"
            value={!!noLogging[t.typeId]}
            onChange={v => setNoLogging(b => ({ ...b, [t.typeId]: v }))}
            description={"Skip per-row generation logging - half the inserts and space. " +
              "WARNING: the regular «Cleanup» will no longer remove these rows; they can only be deleted " +
              "with the «Clear the whole type» button below (it also deletes the real data of the type)."}
          />
        </div>
        <div className="hflex" style={{ gap: 8, marginTop: 12, flexWrap: "wrap" }}>
          <button className="btn btn--primary"
                  onClick={() => void onGenerateType(t)} disabled={busy}>
            {busy ? "Generation…" : "Generate"}
          </button>
          <button className="btn btn--danger"
                  onClick={() => void onPurgeType(t)} disabled={busy}>
            Clear the whole type
          </button>
        </div>
      </div>
    );
  }

  return (
    <main className="page">
      <PageHead
        title="Data generation"
        subtitle="Admin utility that fills catalogs and registers with test records (requirement №6)"
      />

      <Alert kind="info">
        Every generated record is written to the generation log - the «Purge»
        deletes exactly these records (reliable even for registers without a name). Text fields
        additionally contain the marker <strong className="mono">{flag}</strong>. Real data
        are untouched. Maximum per run — {MAX_COUNT.toLocaleString("uk")}.
      </Alert>

      {}
      <div className="section-label">👥 Users</div>
      <div className="card" style={{ maxWidth: 560 }}>
        <div className="form-grid">
          <FormField label="Count" type="number" value={userCount}
                     onChange={setUserCount}
                     description={`How many users to generate (1…${MAX_COUNT})`} />
          <RefField
            label="Default role"
            value={defaultRoleId}
            refTypeId={ACCESS_ROLE_TYPE_ID}
            onChange={setDefaultRoleId}
            description="Assigned to every generated user. «✕» — without a role."
          />
          <RefField
            label="Default interface"
            value={defaultInterfaceId}
            refTypeId={INTERFACE_LAYOUT_TYPE_ID}
            onChange={setDefaultInterfaceId}
            description="Assigned to every generated user. «✕» — the usual navigation mode."
          />
          <FormField label="Default password" value={defaultPassword}
                     onChange={setDefaultPassword}
                     description="One password for all generated users (min. 6 characters)" />
        </div>
        <button className="btn btn--primary" style={{ marginTop: 12 }}
                onClick={() => void onGenerateUsers()} disabled={usersBusy}>
          {usersBusy ? "Generation…" : "Generate users"}
        </button>
      </div>

      {}
      {KIND_ORDER.filter(k => grouped[k]?.length).map(kind => (
        <div key={kind}>
          <div className="section-label" style={{ marginTop: 20 }}>{KIND_LABEL[kind]}</div>
          <div style={{ display: "flex", flexWrap: "wrap", gap: 12 }}>
            {grouped[kind].map(renderTargetCard)}
          </div>
        </div>
      ))}

      {}
      <div className="section-label" style={{ marginTop: 20 }}>Cleanup</div>
      <div className="card" style={{ maxWidth: 560 }}>
        <p className="muted" style={{ marginTop: 0 }}>
          Deletes every generated record (per the generation log and marker{" "}
          <span className="mono">{flag}</span>).
        </p>
        <button className="btn btn--danger"
                onClick={() => void onPurge()} disabled={purgeBusy}>
          {purgeBusy ? "Cleanup…" : "Purge generated data"}
        </button>
        {lastPurge && (
          <div className="muted" style={{ marginBottom: 0 }}>
            <p style={{ marginBottom: 4 }}>
              Last run: deleted in total <strong>{lastPurge.total}</strong>.
            </p>
            {Object.keys(lastPurge.byType).length > 0 && (
              <ul style={{ margin: 0, paddingLeft: 18 }}>
                {Object.entries(lastPurge.byType).map(([label, n]) => (
                  <li key={label}>{label}: <strong>{n}</strong></li>
                ))}
              </ul>
            )}
          </div>
        )}
      </div>
    </main>
  );
}
