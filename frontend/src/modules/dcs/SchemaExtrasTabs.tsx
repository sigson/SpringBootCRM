import { useState } from "react";
import type { DcsClient } from "./api";
import { ExpressionEditor } from "./ExpressionEditor";
import type {
  AvailableField, CalculatedField, DcsSchema, FieldRole, ResourceField,
  SchemaParameter, ValueType,
} from "./types";
import {
  Btn, Check, Empty, Field, Grid, MasterList, Panel, Select, Spacer, TextInput, Toolbar, move,
} from "./ui";

/**
 * <h2>Вкладки «Вычисляемые поля», «Ресурсы» и «Параметры».</h2>
 *
 * <p>Три коллекции схемы с одинаковой механикой (список слева, свойства справа) и
 * разным смыслом:
 * <ul>
 *   <li><b>вычисляемое поле</b> считается для каждой записи — это ещё одна колонка,
 *       которой нет в запросе;</li>
 *   <li><b>ресурс</b> считается для каждой группировки — это итог;</li>
 *   <li><b>параметр</b> подставляется в запрос и в отбор до его выполнения.</li>
 * </ul>
 * Различие между первыми двумя — самое частое место ошибок: выражение, записанное
 * ресурсом, попадёт в итоги, а записанное вычисляемым полем — в строки.
 */

export function CalculatedFieldsTab({
  schema, onChange, client, fields,
}: {
  schema: DcsSchema; onChange: (s: DcsSchema) => void;
  client: DcsClient; fields: AvailableField[];
}) {
  const [selected, setSelected] = useState(0);
  const list = schema.calculatedFields;
  const current = list[selected] ?? null;

  const commit = (next: CalculatedField[]) => onChange({ ...schema, calculatedFields: next });
  const patch = (p: Partial<CalculatedField>) => {
    if (!current) return;
    const next = list.slice();
    next[selected] = { ...current, ...p };
    commit(next);
  };

  return (
    <div className="dcs-split" style={{ gridTemplateColumns: "240px minmax(0, 1fr)" }}>
      <Panel title="Calculated fields">
        <div style={{ margin: -8 }}>
          <MasterList
            items={list}
            selected={selected}
            onSelect={setSelected}
            addLabel="Field"
            onAdd={() => {
              commit([...list, {
                name: `calc${list.length + 1}`, title: null, expression: "",
                role: null, valueType: null,
                usableInSelection: true, usableInFilter: false,
                usableInGroup: false, usableInOrder: true,
              }]);
              setSelected(list.length);
            }}
            onRemove={(i) => { commit(list.filter((_, idx) => idx !== i)); setSelected(Math.max(0, i - 1)); }}
            onMove={(from, to) => { commit(move(list, from, to)); setSelected(to); }}
            emptyText="No calculated fields."
            label={(c) => <span>{c.title || c.name}</span>}
          />
        </div>
      </Panel>

      <Panel title="Field properties" scroll>
        {!current ? <Empty>Select a field or add a new one.</Empty> : (
          <>
            <Grid cols={3}>
              <Field label="Name" hint="Referenced from the settings by this name">
                <TextInput mono value={current.name} onChange={(v) => patch({ name: v })} />
              </Field>
              <Field label="Title">
                <TextInput value={current.title ?? ""} onChange={(v) => patch({ title: v || null })} />
              </Field>
              <Field label="Value type">
                <Select value={current.valueType ?? ""} empty="auto"
                        onChange={(v) => patch({ valueType: (v || null) as ValueType | null })}
                        options={VALUE_TYPES} />
              </Field>
            </Grid>

            <Field label="Expression"
                   hint="Computed per record. Aggregate functions here apply to a single record — for totals use a resource.">
              <ExpressionEditor value={current.expression} onChange={(v) => patch({ expression: v })}
                                client={client} fields={fields} />
            </Field>

            <Toolbar>
              <span className="dcs-muted">Available in:</span>
              <Check label="selection" value={current.usableInSelection}
                     onChange={(v) => patch({ usableInSelection: v })} />
              <Check label="filter" value={current.usableInFilter}
                     onChange={(v) => patch({ usableInFilter: v })} />
              <Check label="grouping" value={current.usableInGroup}
                     onChange={(v) => patch({ usableInGroup: v })} />
              <Check label="order" value={current.usableInOrder}
                     onChange={(v) => patch({ usableInOrder: v })} />
              <Spacer />
              <Field label="Role">
                <Select value={current.role ?? ""} empty="—"
                        onChange={(v) => patch({ role: (v || null) as FieldRole | null })}
                        options={ROLES} />
              </Field>
            </Toolbar>
          </>
        )}
      </Panel>
    </div>
  );
}

export function ResourcesTab({
  schema, onChange, client, fields,
}: {
  schema: DcsSchema; onChange: (s: DcsSchema) => void;
  client: DcsClient; fields: AvailableField[];
}) {
  const [selected, setSelected] = useState(0);
  const list = schema.resources;
  const current = list[selected] ?? null;

  const commit = (next: ResourceField[]) => onChange({ ...schema, resources: next });
  const patch = (p: Partial<ResourceField>) => {
    if (!current) return;
    const next = list.slice();
    next[selected] = { ...current, ...p };
    commit(next);
  };

  const groupable = fields.filter((f) => f.usableInGroup);
  const numericFields = fields.filter((f) => f.kind === "source");

  return (
    <div className="dcs-split" style={{ gridTemplateColumns: "240px minmax(0, 1fr)" }}>
      <Panel title="Resources">
        <div style={{ margin: -8 }}>
          <MasterList
            items={list}
            selected={selected}
            onSelect={setSelected}
            addLabel="Resource"
            onAdd={() => {
              commit([...list, {
                name: `resource${list.length + 1}`, title: null,
                expression: "", calcByGroups: [], format: null,
              }]);
              setSelected(list.length);
            }}
            onRemove={(i) => { commit(list.filter((_, idx) => idx !== i)); setSelected(Math.max(0, i - 1)); }}
            onMove={(from, to) => { commit(move(list, from, to)); setSelected(to); }}
            emptyText="No resources. A report without resources has no totals."
            label={(r) => <span>{r.title || r.name}</span>}
          />
        </div>
      </Panel>

      <Panel title="Resource properties" scroll>
        {!current ? <Empty>Select a resource or add a new one.</Empty> : (
          <>
            <Grid cols={3}>
              <Field label="Name">
                <TextInput mono value={current.name} onChange={(v) => patch({ name: v })} />
              </Field>
              <Field label="Title">
                <TextInput value={current.title ?? ""} onChange={(v) => patch({ title: v || null })} />
              </Field>
              <Field label="Format" hint="Passed to the output as-is">
                <TextInput value={current.format ?? ""} onChange={(v) => patch({ format: v || null })}
                           placeholder="#,##0.00" />
              </Field>
            </Grid>

            <Toolbar>
              <span className="dcs-muted">Quick aggregate:</span>
              {["Сумма", "Количество", "Минимум", "Максимум", "Среднее", "КоличествоРазличных"].map((fn) => (
                <select
                  key={fn}
                  className="dcs-input"
                  style={{ maxWidth: 150 }}
                  value=""
                  onChange={(e) => {
                    if (e.target.value) patch({ expression: `${fn}(${e.target.value})` });
                  }}
                >
                  <option value="">{fn}(…)</option>
                  {numericFields.map((f) => <option key={f.id} value={f.id}>{f.title}</option>)}
                </select>
              ))}
            </Toolbar>

            <Field label="Expression"
                   hint="Computed over the records of each grouping. A single simple aggregate lets the engine push GROUP BY into SQL.">
              <ExpressionEditor value={current.expression} onChange={(v) => patch({ expression: v })}
                                client={client} fields={fields} />
            </Field>

            <Field label="Calculate by groupings"
                   hint="Empty — by every grouping. Otherwise only by the chosen ones.">
              <div className="dcs-chips">
                {current.calcByGroups.map((g) => (
                  <span key={g} className="dcs-chip">
                    {g}
                    <button type="button"
                            onClick={() => patch({ calcByGroups: current.calcByGroups.filter((x) => x !== g) })}>
                      ✕
                    </button>
                  </span>
                ))}
                <select
                  className="dcs-input"
                  style={{ maxWidth: 220 }}
                  value=""
                  onChange={(e) => {
                    const v = e.target.value;
                    if (v && !current.calcByGroups.includes(v)) {
                      patch({ calcByGroups: [...current.calcByGroups, v] });
                    }
                  }}
                >
                  <option value="">＋ grouping…</option>
                  {groupable.map((f) => <option key={f.id} value={f.id}>{f.title}</option>)}
                </select>
              </div>
            </Field>
          </>
        )}
      </Panel>
    </div>
  );
}

export function ParametersTab({
  schema, onChange,
}: { schema: DcsSchema; onChange: (s: DcsSchema) => void }) {
  const [selected, setSelected] = useState(0);
  const list = schema.parameters;
  const current = list[selected] ?? null;

  const commit = (next: SchemaParameter[]) => onChange({ ...schema, parameters: next });
  const patch = (p: Partial<SchemaParameter>) => {
    if (!current) return;
    const next = list.slice();
    next[selected] = { ...current, ...p };
    commit(next);
  };

  return (
    <div className="dcs-split" style={{ gridTemplateColumns: "240px minmax(0, 1fr)" }}>
      <Panel title="Parameters">
        <div style={{ margin: -8 }}>
          <MasterList
            items={list}
            selected={selected}
            onSelect={setSelected}
            addLabel="Parameter"
            onAdd={() => {
              commit([...list, {
                name: `Param${list.length + 1}`, title: null, valueType: "string",
                value: null, availableValues: [], stdPeriod: false,
                useRestriction: false, userVisible: true, required: false,
              }]);
              setSelected(list.length);
            }}
            onRemove={(i) => { commit(list.filter((_, idx) => idx !== i)); setSelected(Math.max(0, i - 1)); }}
            onMove={(from, to) => { commit(move(list, from, to)); setSelected(to); }}
            emptyText="No parameters."
            label={(p) => <span>&amp;{p.name}</span>}
          />
        </div>
      </Panel>

      <Panel title="Parameter properties" scroll>
        {!current ? <Empty>Select a parameter or add a new one.</Empty> : (
          <>
            <Grid cols={3}>
              <Field label="Name" hint="Written as &Name in queries and filters">
                <TextInput mono value={current.name} onChange={(v) => patch({ name: v })} />
              </Field>
              <Field label="Title">
                <TextInput value={current.title ?? ""} onChange={(v) => patch({ title: v || null })} />
              </Field>
              <Field label="Type">
                <Select value={current.valueType}
                        onChange={(v) => patch({ valueType: v as SchemaParameter["valueType"] })}
                        options={[...VALUE_TYPES, { value: "list", label: "Value list" }]} />
              </Field>
            </Grid>

            <Field label="Default value">
              <TextInput value={current.value == null ? "" : String(current.value)}
                         onChange={(v) => patch({ value: coerce(v, current.valueType) })}
                         placeholder={current.valueType === "date" ? "2026-01-01" : ""} />
            </Field>

            <Toolbar>
              <Check label="Required" value={current.required}
                     title="Composing without a value fails with an explicit error"
                     onChange={(v) => patch({ required: v })} />
              <Check label="Show to the user" value={current.userVisible}
                     onChange={(v) => patch({ userVisible: v })} />
              <Check label="Read-only" value={current.useRestriction}
                     onChange={(v) => patch({ useRestriction: v })} />
              <Check label="Standard period" value={current.stdPeriod}
                     title="The value is set with a relative date"
                     onChange={(v) => patch({ stdPeriod: v })} />
            </Toolbar>

            <Field label="Available values" hint="Turns the parameter into a dropdown">
              {current.availableValues.length === 0 && <Empty>The value is entered freely.</Empty>}
              {current.availableValues.map((av, i) => (
                <div key={i} className="dcs-filter-row">
                  <TextInput value={av.value == null ? "" : String(av.value)}
                             onChange={(v) => patchAvailable(i, { value: coerce(v, current.valueType) })} />
                  <span className="dcs-muted" style={{ textAlign: "center" }}>→</span>
                  <TextInput value={av.presentation}
                             onChange={(v) => patchAvailable(i, { presentation: v })} />
                  <Btn small kind="danger"
                       onClick={() => patch({
                         availableValues: current.availableValues.filter((_, idx) => idx !== i),
                       })}>✕</Btn>
                </div>
              ))}
              <Toolbar>
                <Btn small onClick={() => patch({
                  availableValues: [...current.availableValues, { value: "", presentation: "" }],
                })}>
                  ＋ Value
                </Btn>
              </Toolbar>
            </Field>
          </>
        )}
      </Panel>
    </div>
  );

  function patchAvailable(i: number, p: Partial<SchemaParameter["availableValues"][number]>) {
    if (!current) return;
    const availableValues = current.availableValues.slice();
    availableValues[i] = { ...availableValues[i], ...p };
    patch({ availableValues });
  }
}

/** Приведение введённого текста к типу параметра — пустая строка означает «нет значения». */
function coerce(v: string, type: string): unknown {
  if (v === "") return null;
  if (type === "number") { const n = Number(v); return Number.isNaN(n) ? v : n; }
  if (type === "boolean") return v === "true" || v === "1";
  return v;
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
  { value: "account", label: "Account" },
];
