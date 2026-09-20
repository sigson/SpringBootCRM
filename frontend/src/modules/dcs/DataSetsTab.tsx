import { Component, lazy, Suspense, useState, type ReactNode } from "react";
import type { DcsClient } from "./api";
import { encodePack, NAME_RE, applyPacked } from "./packing";
import type { DataSet, DataSetField, DcsSchema, FieldRole, ValueType } from "./types";
import {
  Banner, Btn, Check, Empty, Field, MasterList, Panel, Select, Spacer, TabBar, TextArea,
  TextInput, Toolbar, move,
} from "./ui";

/**
 * Визуальный конструктор подгружается отдельным чанком: он тянет за собой редактор
 * Workbench'а со ссылочным режимом, и платить за него на каждой загрузке отчёта, где
 * запрос уже написан, незачем.
 */
const DatasetQueryBuilder = lazy(() =>
  import("./DatasetQueryBuilder").then((m) => ({ default: m.DatasetQueryBuilder })));

/** Ошибка загрузки/рендера конструктора не должна уносить всю вкладку. */
class BuilderBoundary extends Component<{ children: ReactNode }, { failed: boolean }> {
  state = { failed: false };
  static getDerivedStateFromError() { return { failed: true }; }
  componentDidCatch() { /* тихо: остаётся вкладка «SQL» */ }
  render() {
    if (this.state.failed) {
      return (
        <Banner kind="warn">
          The visual query builder is unavailable (the SQL Workbench module is missing).
          Write the dataset query on the «SQL» tab.
        </Banner>
      );
    }
    return this.props.children;
  }
}

/**
 * <h2>Вкладка «Наборы данных».</h2>
 *
 * <p>Слева — наборы, справа — запрос выбранного набора и метаданные его полей. Все
 * наборы живут в одной упакованной строке, поэтому любая правка здесь немедленно
 * пересобирает {@code schema.packed} — именно её потом читает Workbench.
 *
 * <p>Запрос набора можно и собрать мышью, и написать текстом: вкладка «Конструктор»
 * монтирует конструктор запросов Workbench'а (см. {@code DatasetQueryBuilder}), а
 * сгенерированный им SQL ложится в ту же упакованную строку. Правка текста отвязывает
 * набор от конструктора — разобрать произвольный SQL обратно в дерево нельзя, и делать
 * вид, что можно, было бы хуже, чем сказать об этом прямо.
 *
 * <p>«Автозаполнение» повторяет одноимённый флаг 1С: кнопка выполняет запрос набора
 * с нулевой выборкой, берёт метаданные результата и заводит поля с выведенными типами.
 * Уже настроенные заголовки и роли при этом сохраняются — заполняются только новые
 * колонки, иначе автозаполнение затирало бы ручную работу.
 */
export function DataSetsTab({
  schema, onChange, client, dataSourceId, getToken,
}: {
  schema: DcsSchema;
  onChange: (s: DcsSchema) => void;
  client: DcsClient;
  dataSourceId: string | null;
  getToken?: () => string | null | undefined;
}) {
  const [selected, setSelected] = useState(0);
  const [view, setView] = useState<"query" | "builder" | "fields" | "packed">("query");
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<{ kind: "ok" | "error" | "warn"; text: string } | null>(null);

  const dataSets = schema.dataSets;
  const current = dataSets[selected] ?? null;

  function commit(next: DataSet[]) {
    onChange({ ...schema, dataSets: next, packed: encodePack(next, schema.links) });
  }

  function patchCurrent(patch: Partial<DataSet>) {
    if (!current) return;
    const next = dataSets.slice();
    next[selected] = { ...current, ...patch };
    commit(next);
  }

  function addDataSet() {
    const name = uniqueName(dataSets.map((d) => d.name), "DataSet");
    commit([...dataSets, {
      name, title: null, type: "query", items: [], autoFill: true, fields: [],
      query: "", builder: null,
    }]);
    setSelected(dataSets.length);
    setView("builder");
  }

  function removeDataSet(i: number) {
    const removed = dataSets[i];
    const next = dataSets.filter((_, idx) => idx !== i);
    // Связи, потерявшие сторону, больше не имеют смысла — убираем вместе с набором,
    // иначе сборщик пакета отбросит их с предупреждением при каждом формировании.
    const links = schema.links.filter((l) => l.source !== removed.name && l.target !== removed.name);
    onChange({ ...schema, dataSets: next, links, packed: encodePack(next, links) });
    setSelected(Math.max(0, i - 1));
  }

  async function autoFill() {
    if (!current) return;
    setBusy(true);
    setMessage(null);
    try {
      const packed = encodePack(dataSets, schema.links);
      // Значения параметров нужны и здесь: набор с &Параметром без них не исполнится,
      // а автозаполнение полей нужнее всего именно в таких наборах.
      const described = await client.describeDataSets(packed, dataSourceId, parameterValues(schema));
      const mine = described.find((d) => d.name === current.name);
      if (!mine) { setMessage({ kind: "error", text: "The server did not describe this dataset" }); return; }
      if (mine.error) { setMessage({ kind: "error", text: mine.error }); return; }

      const byName = new Map(current.fields.map((f) => [f.name.toLowerCase(), f]));
      const fields: DataSetField[] = mine.columns.map((c) => {
        const existing = byName.get(c.name.toLowerCase());
        if (existing) return { ...existing, valueType: existing.valueType ?? c.valueType };
        return defaultField(c.name, c.valueType);
      });
      patchCurrent({ fields });
      setView("fields");
      setMessage({ kind: "ok", text: `Fields filled in: ${fields.length}` });
    } catch (e) {
      setMessage({ kind: "error", text: e instanceof Error ? e.message : String(e) });
    } finally {
      setBusy(false);
    }
  }

  const nameInvalid = current != null && !NAME_RE.test(current.name);

  return (
    <div className="dcs-split" style={{ gridTemplateColumns: "240px minmax(0, 1fr)" }}>
      <Panel title="Datasets">
        <div style={{ margin: -8 }}>
          <MasterList
            items={dataSets}
            selected={selected}
            onSelect={setSelected}
            onAdd={addDataSet}
            addLabel="Dataset"
            onRemove={removeDataSet}
            onMove={(from, to) => { commit(move(dataSets, from, to)); setSelected(to); }}
            emptyText="No datasets. Add a query."
            label={(d) => (
              <>
                <span>{d.name}</span>
                <Spacer />
                <span className="dcs-muted" style={{ fontSize: 10 }}>{d.fields.length}</span>
              </>
            )}
          />
        </div>
      </Panel>

      <Panel
        title={current ? `Dataset: ${current.name}` : "Dataset"}
        actions={
          <>
            <Btn small onClick={() => void autoFill()} disabled={!current || busy}
                 title="Run the query with an empty selection and fill the fields from the result metadata">
              {busy ? "…" : "Auto-fill fields"}
            </Btn>
          </>
        }
        scroll
      >
        {!current ? (
          <Empty>Select a dataset or add a new one.</Empty>
        ) : (
          <>
            {message && (
              <Banner kind={message.kind} onClose={() => setMessage(null)}>{message.text}</Banner>
            )}

            <div className="dcs-grid" style={{ gridTemplateColumns: "1fr 1fr 120px", marginBottom: 8 }}>
              <Field label="Name" hint="Latin letters, digits and _ — becomes the SQL prefix of the dataset">
                <TextInput value={current.name} invalid={nameInvalid}
                           onChange={(v) => renameDataSet(v)} mono />
              </Field>
              <Field label="Title">
                <TextInput value={current.title ?? ""} onChange={(v) => patchCurrent({ title: v || null })} />
              </Field>
              <Field label="Kind">
                <Select
                  value={current.type}
                  onChange={(v) => patchCurrent({ type: v as DataSet["type"] })}
                  options={[{ value: "query", label: "Query" }, { value: "union", label: "Union" }]}
                />
              </Field>
            </div>

            {nameInvalid && (
              <Banner kind="error">
                Invalid dataset name. Allowed: latin letters, digits and underscore, not starting with a digit.
              </Banner>
            )}

            <TabBar
              active={view}
              onChange={(v) => setView(v as typeof view)}
              tabs={[
                { id: "builder", label: "Constructor" },
                { id: "query", label: "SQL" },
                { id: "fields", label: "Fields", badge: current.fields.length },
                { id: "packed", label: "Packed string" },
              ]}
            />

            {view === "builder" && (
              <BuilderBoundary>
                <Suspense fallback={<Empty>Loading the query builder…</Empty>}>
                  <DatasetQueryBuilder
                    dataSetName={current.name}
                    dataSourceId={dataSourceId ?? "main"}
                    builder={current.builder ?? null}
                    currentSql={current.query ?? ""}
                    getToken={getToken}
                    parameters={schema.parameters ?? []}
                    dcsClient={client}
                    // Предпросмотр исполняет набор в контексте пакета, поэтому строка
                    // собирается с ещё не сохранённым текстом текущего набора.
                    packedFor={(sql) => encodePack(
                      dataSets.map((d, i) => (i === selected ? { ...d, query: sql } : d)),
                      schema.links)}
                    onChange={(builder, sql) => patchCurrent({ builder, query: sql })}
                  />
                </Suspense>
              </BuilderBoundary>
            )}

            {view === "query" && (
              <>
                {current.builder != null && (
                  <Banner kind="info">
                    This query is assembled in the constructor. Editing the text here detaches
                    the dataset from it — the tree cannot be restored from arbitrary SQL.
                  </Banner>
                )}
                <Field label="SELECT" hint="Without a trailing semicolon. Schema parameters are written as &Name.">
                  <TextArea mono rows={16} value={current.query ?? ""}
                            onChange={(v) => patchCurrent({ query: v, builder: null })}
                            placeholder="SELECT d.code, d.amount FROM deals d" />
                </Field>
              </>
            )}

            {view === "fields" && (
              <FieldsEditor
                fields={current.fields}
                onChange={(fields) => patchCurrent({ fields })}
              />
            )}

            {view === "packed" && (
              <PackedEditor schema={schema} onChange={onChange} />
            )}
          </>
        )}
      </Panel>
    </div>
  );

  function renameDataSet(name: string) {
    if (!current) return;
    const oldName = current.name;
    const next = dataSets.slice();
    next[selected] = { ...current, name };
    // Переименование набора переписывает и стороны связей: иначе связь молча
    // отвалится, а пользователь увидит декартово произведение вместо соединения.
    const links = schema.links.map((l) => ({
      ...l,
      source: l.source === oldName ? name : l.source,
      target: l.target === oldName ? name : l.target,
      conditions: l.conditions.map((c) => ({
        ...c,
        sourceExpr: requalify(c.sourceExpr, oldName, name),
        targetExpr: requalify(c.targetExpr, oldName, name),
      })),
    }));
    onChange({ ...schema, dataSets: next, links, packed: encodePack(next, links) });
  }
}

/** Таблица метаданных полей набора. */
function FieldsEditor({
  fields, onChange,
}: { fields: DataSetField[]; onChange: (f: DataSetField[]) => void }) {

  function patch(i: number, p: Partial<DataSetField>) {
    const next = fields.slice();
    next[i] = { ...next[i], ...p };
    onChange(next);
  }

  return (
    <>
      <Toolbar>
        <Btn small onClick={() => onChange([...fields, defaultField(`field${fields.length + 1}`, "string")])}>
          ＋ Field
        </Btn>
        <Spacer />
        <span className="dcs-muted" style={{ fontSize: 11 }}>
          The flags control where the field may be used in the settings form
        </span>
      </Toolbar>
      {fields.length === 0 ? (
        <Empty>No fields. Use «Auto-fill fields» or add them manually.</Empty>
      ) : (
        <div style={{ overflow: "auto", maxHeight: "52vh" }}>
          <table className="dcs-table">
            <thead>
              <tr>
                <th style={{ width: 150 }}>Column</th>
                <th style={{ width: 170 }}>Title</th>
                <th style={{ width: 110 }}>Type</th>
                <th style={{ width: 130 }}>Role</th>
                <th style={{ width: 210 }}>Available in</th>
                <th style={{ width: 150 }}>Rules</th>
                <th style={{ width: 34 }} />
              </tr>
            </thead>
            <tbody>
              {fields.map((f, i) => (
                <tr key={i}>
                  <td><TextInput mono value={f.name} onChange={(v) => patch(i, { name: v })} /></td>
                  <td><TextInput value={f.title ?? ""} onChange={(v) => patch(i, { title: v || null })} /></td>
                  <td>
                    <Select
                      value={f.valueType ?? "string"}
                      onChange={(v) => patch(i, { valueType: v as ValueType })}
                      options={VALUE_TYPES}
                    />
                  </td>
                  <td>
                    <Select
                      value={f.role ?? ""}
                      onChange={(v) => patch(i, { role: (v || null) as FieldRole | null })}
                      options={ROLES}
                      empty="—"
                    />
                  </td>
                  <td>
                    <div className="dcs-chips">
                      <Check label="sel" value={f.usableInSelection} title="Selection"
                             onChange={(v) => patch(i, { usableInSelection: v })} />
                      <Check label="flt" value={f.usableInFilter} title="Filter"
                             onChange={(v) => patch(i, { usableInFilter: v })} />
                      <Check label="grp" value={f.usableInGroup} title="Grouping"
                             onChange={(v) => patch(i, { usableInGroup: v })} />
                      <Check label="ord" value={f.usableInOrder} title="Order"
                             onChange={(v) => patch(i, { usableInOrder: v })} />
                    </div>
                  </td>
                  <td>
                    <div className="dcs-chips">
                      <Check label="skip NULL" value={f.ignoreNull} title="Ignore NULL values"
                             onChange={(v) => patch(i, { ignoreNull: v })} />
                      <Check label="required" value={f.mandatory} title="Always include in the query"
                             onChange={(v) => patch(i, { mandatory: v })} />
                    </div>
                  </td>
                  <td>
                    <Btn small kind="danger"
                         onClick={() => onChange(fields.filter((_, idx) => idx !== i))}>✕</Btn>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </>
  );
}

/**
 * Редактор упакованной строки целиком. Нужен для копипасты готовой структуры и для
 * случаев, когда запрос удобнее править текстом; после применения наборы и связи
 * перечитываются из строки, а метаданные полей сохраняются.
 */
function PackedEditor({
  schema, onChange,
}: { schema: DcsSchema; onChange: (s: DcsSchema) => void }) {
  const [text, setText] = useState(schema.packed ?? "");
  const [applied, setApplied] = useState(false);

  return (
    <>
      <Banner kind="info">
        One string holds every dataset query and the link directives. This is exactly what the
        SQL Workbench reads to assemble the datasets into joins.
      </Banner>
      <TextArea mono rows={18} value={text} onChange={(v) => { setText(v); setApplied(false); }} />
      <Toolbar>
        <Btn small kind="primary" onClick={() => { onChange(applyPacked(schema, text)); setApplied(true); }}>
          Apply the string
        </Btn>
        <Btn small onClick={() => { setText(schema.packed ?? ""); setApplied(false); }}>Revert</Btn>
        {applied && <span className="dcs-muted">Applied</span>}
      </Toolbar>
    </>
  );
}

// ------------------------------------------------------------------ helpers

export function defaultField(name: string, valueType: ValueType): DataSetField {
  return {
    name,
    dataPath: name,
    title: name,
    role: valueType === "number" ? "resource" : "dimension",
    periodOrder: null,
    valueType,
    usableInSelection: true,
    usableInFilter: true,
    usableInGroup: true,
    usableInOrder: true,
    ignoreNull: false,
    mandatory: false,
  };
}

/** Значения параметров схемы по умолчанию — ими исполняются служебные прогоны набора. */
export function parameterValues(schema: DcsSchema): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  for (const p of schema.parameters ?? []) {
    if (p?.name) out[p.name] = p.value ?? null;
  }
  return out;
}

function uniqueName(taken: string[], base: string): string {
  if (!taken.includes(base)) return base;
  let n = 2;
  while (taken.includes(`${base}${n}`)) n++;
  return `${base}${n}`;
}

/** Меняет префикс набора в выражении связи при переименовании набора. */
function requalify(expr: string, oldName: string, newName: string): string {
  const prefix = `${oldName}.`;
  return expr.startsWith(prefix) ? newName + "." + expr.slice(prefix.length) : expr;
}

const VALUE_TYPES = [
  { value: "string", label: "String" },
  { value: "number", label: "Number" },
  { value: "date", label: "Date" },
  { value: "boolean", label: "Boolean" },
];

const ROLES = [
  { value: "dimension", label: "Dimension" },
  { value: "resource", label: "Resource" },
  { value: "period", label: "Period" },
  { value: "additionalPeriod", label: "Extra period" },
  { value: "beginBalance", label: "Opening balance" },
  { value: "endBalance", label: "Closing balance" },
  { value: "account", label: "Account" },
];
