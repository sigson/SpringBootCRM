import { useEffect, useState } from "react";
import type { DcsClient } from "./api";
import { encodePack, onText } from "./packing";
import type { DataSetLink, DcsSchema, LinkType } from "./types";
import {
  Banner, Btn, Check, Empty, Field, Grid, MasterList, Panel, Select, Spacer, TextInput, Toolbar, move,
} from "./ui";

/**
 * <h2>Вкладка «Связи наборов данных».</h2>
 *
 * <p>Здесь и происходит то самое «собрать несколько запросов в связь»: каждая строка
 * описывает соединение двух наборов и условия, по которым они стыкуются. Настройка
 * уходит в упакованную строку директивами {@code --#link}, а сборщик пакета в
 * Workbench'е превращает её в цепочку JOIN'ов поверх CTE.
 *
 * <p>Справа — предпросмотр собранного SQL. Он важнее, чем кажется: связи — место, где
 * ошибка не видна по результату (лишние строки от декартова произведения выглядят как
 * «просто много данных»), и увидеть готовый запрос дешевле, чем отлаживать итоги.
 */
export function LinksTab({
  schema, onChange, client,
}: { schema: DcsSchema; onChange: (s: DcsSchema) => void; client: DcsClient }) {
  const [selected, setSelected] = useState(0);
  const [sql, setSql] = useState<string>("");
  const [warnings, setWarnings] = useState<string[]>([]);
  const [error, setError] = useState<string | null>(null);

  const links = schema.links;
  const current = links[selected] ?? null;
  const names = schema.dataSets.map((d) => ({ value: d.name, label: d.name }));

  // Предпросмотр пересобирается на любое изменение наборов или связей — но с
  // задержкой: сборка идёт на сервере, и дёргать её на каждую букву в условии незачем.
  useEffect(() => {
    if (schema.dataSets.length === 0) { setSql(""); setWarnings([]); return; }
    let alive = true;
    const timer = setTimeout(() => {
      client.parsePack(schema.packed)
        .then((pack) => client.packToSql(pack))
        .then((res) => {
          if (!alive) return;
          setSql(res.sqlPretty);
          setWarnings(res.warnings);
          setError(null);
        })
        .catch((e) => { if (alive) { setSql(""); setError(e?.message ?? String(e)); } });
    }, 350);
    return () => { alive = false; clearTimeout(timer); };
  }, [schema.packed, schema.dataSets.length, client]);

  function commit(next: DataSetLink[]) {
    onChange({ ...schema, links: next, packed: encodePack(schema.dataSets, next) });
  }

  function patchCurrent(patch: Partial<DataSetLink>) {
    if (!current) return;
    const next = links.slice();
    next[selected] = { ...current, ...patch };
    commit(next);
  }

  function addLink() {
    const source = schema.dataSets[0]?.name ?? "";
    const target = schema.dataSets[1]?.name ?? schema.dataSets[0]?.name ?? "";
    commit([...links, {
      source, target, linkType: "left",
      conditions: [{ sourceExpr: "", operator: "=", targetExpr: "" }],
      rawCondition: null, disabled: false,
    }]);
    setSelected(links.length);
  }

  const sourceFields = fieldOptions(schema, current?.source);
  const targetFields = fieldOptions(schema, current?.target);

  return (
    <div className="dcs-split" style={{ gridTemplateColumns: "280px minmax(0, 1fr)" }}>
      <Panel title="Links">
        <div style={{ margin: -8 }}>
          <MasterList
            items={links}
            selected={selected}
            onSelect={setSelected}
            onAdd={schema.dataSets.length > 0 ? addLink : undefined}
            addLabel="Link"
            onRemove={(i) => { commit(links.filter((_, idx) => idx !== i)); setSelected(Math.max(0, i - 1)); }}
            onMove={(from, to) => { commit(move(links, from, to)); setSelected(to); }}
            emptyText="No links. Datasets are joined with CROSS JOIN."
            label={(l) => (
              <span style={{ opacity: l.disabled ? 0.5 : 1 }}>
                {l.source} <span className="dcs-muted">→</span> {l.target}
                <span className="dcs-muted" style={{ marginLeft: 6, fontSize: 10 }}>
                  {l.linkType.toUpperCase()}
                </span>
              </span>
            )}
          />
        </div>
      </Panel>

      <div style={{ display: "grid", gridTemplateRows: "auto minmax(0, 1fr)", gap: 8, minHeight: 0 }}>
        <Panel title="Link settings">
          {!current ? (
            <Empty>Select a link or add a new one.</Empty>
          ) : (
            <>
              <Grid cols={4}>
                <Field label="Source (master)">
                  <Select value={current.source} onChange={(v) => patchCurrent({ source: v })}
                          options={names} empty="—" />
                </Field>
                <Field label="Target (detail)">
                  <Select value={current.target} onChange={(v) => patchCurrent({ target: v })}
                          options={names} empty="—" />
                </Field>
                <Field label="Join type"
                       hint="LEFT keeps every master row even without a match">
                  <Select
                    value={current.linkType}
                    onChange={(v) => patchCurrent({ linkType: v as LinkType })}
                    options={[
                      { value: "left", label: "LEFT — all source rows" },
                      { value: "inner", label: "INNER — matches only" },
                      { value: "right", label: "RIGHT — all target rows" },
                      { value: "full", label: "FULL — both sides" },
                      { value: "cross", label: "CROSS — cartesian product" },
                    ]}
                  />
                </Field>
                <Field label="&nbsp;">
                  <Check label="Disabled" value={current.disabled}
                         onChange={(v) => patchCurrent({ disabled: v })} />
                </Field>
              </Grid>

              {current.linkType !== "cross" && (
                <>
                  <div className="dcs-field__label" style={{ margin: "10px 0 4px" }}>
                    Join conditions
                  </div>
                  {(current.conditions ?? []).map((c, i) => (
                    <div key={i} className="dcs-filter-row">
                      <Select
                        value={c.sourceExpr}
                        onChange={(v) => patchCondition(i, { sourceExpr: v })}
                        options={sourceFields}
                        empty="— source field —"
                      />
                      <Select
                        value={c.operator}
                        onChange={(v) => patchCondition(i, { operator: v })}
                        options={["=", "<>", ">", ">=", "<", "<="].map((o) => ({ value: o, label: o }))}
                      />
                      <Select
                        value={c.targetExpr}
                        onChange={(v) => patchCondition(i, { targetExpr: v })}
                        options={targetFields}
                        empty="— target field —"
                      />
                      <Btn small kind="danger" onClick={() => removeCondition(i)}>✕</Btn>
                    </div>
                  ))}
                  <Toolbar>
                    <Btn small onClick={() => patchCurrent({
                      conditions: [...(current.conditions ?? []),
                        { sourceExpr: "", operator: "=", targetExpr: "" }],
                    })}>
                      ＋ Condition
                    </Btn>
                    <Spacer />
                    <span className="dcs-muted" style={{ fontSize: 11 }}>
                      ON {onText(current) ?? "— not set —"}
                    </span>
                  </Toolbar>

                  <Field label="Raw ON condition"
                         hint="Filled in — replaces the conditions above. For cases the field picker cannot express.">
                    <TextInput mono value={current.rawCondition ?? ""}
                               onChange={(v) => patchCurrent({ rawCondition: v || null })}
                               placeholder="Sales.item = Rates.item AND Rates.day <= Sales.day" />
                  </Field>
                </>
              )}
            </>
          )}
        </Panel>

        <Panel title="Assembled SQL" scroll
               actions={<span className="dcs-muted" style={{ fontSize: 11 }}>
                 {schema.dataSets.length} dataset(s)
               </span>}>
          {error && <Banner kind="error">{error}</Banner>}
          {warnings.map((w, i) => <Banner key={i} kind="warn">{w}</Banner>)}
          {sql ? <pre className="dcs-sql">{sql}</pre> : <Empty>Add a dataset to see the SQL.</Empty>}
        </Panel>
      </div>
    </div>
  );

  function patchCondition(i: number, patch: Partial<DataSetLink["conditions"][number]>) {
    if (!current) return;
    const conditions = (current.conditions ?? []).slice();
    conditions[i] = { ...conditions[i], ...patch };
    patchCurrent({ conditions });
  }

  function removeCondition(i: number) {
    if (!current) return;
    patchCurrent({ conditions: (current.conditions ?? []).filter((_, idx) => idx !== i) });
  }
}

/** Поля набора в виде полных путей — их и хранит условие связи. */
function fieldOptions(schema: DcsSchema, dataSetName: string | undefined) {
  if (!dataSetName) return [];
  const ds = schema.dataSets.find((d) => d.name === dataSetName);
  if (!ds) return [];
  return ds.fields.map((f) => ({
    value: `${ds.name}.${f.name}`,
    label: f.title ? `${f.name} — ${f.title}` : f.name,
  }));
}
