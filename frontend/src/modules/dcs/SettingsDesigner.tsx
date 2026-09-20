import { useState } from "react";
import { FilterBuilder } from "./FilterBuilder";
import type {
  AppearanceArea, AppearanceItem, AvailableField, DcsSchema, DcsSettings,
  GroupingType, OrderItem, StructureNode,
} from "./types";
import {
  Banner, Btn, Check, Collapsible, Empty, Field, Grid, NumberInput, Panel, Select,
  Spacer, TabBar, TextInput, Toolbar, clone, move, newId,
} from "./ui";

/**
 * <h2>Конструктор настроек компоновки — «что и как выводить».</h2>
 *
 * <p>Центральная вкладка конструктора и главный экран для аналитика. Слева дерево
 * структуры отчёта (группировки, кросс-таблицы, диаграммы), справа — свойства
 * выделенного узла. Остальные разделы (общий отбор, порядок, оформление, параметры
 * вывода) вынесены в сворачиваемые секции ниже: они относятся к отчёту целиком, а не
 * к узлу.
 *
 * <p>Группировка без поля — это «Детальные записи»: узел, выводящий исходные строки.
 * Он показан отдельным видом в списке выбора поля, потому что «нет поля» как выбор
 * выглядит ошибкой, хотя это полноценный и часто нужный режим.
 */
export function SettingsDesigner({
  settings, onChange, schema, fields,
}: {
  settings: DcsSettings;
  onChange: (s: DcsSettings) => void;
  schema: DcsSchema;
  fields: AvailableField[];
}) {
  const [path, setPath] = useState<number[] | null>(null);
  const [nodeTab, setNodeTab] = useState<"selection" | "order" | "filter">("selection");

  const selected = path ? nodeAt(settings.structure, path) : null;

  function patchSettings(p: Partial<DcsSettings>) { onChange({ ...settings, ...p }); }

  function updateStructure(mutate: (tree: StructureNode[]) => void) {
    const tree = clone(settings.structure);
    mutate(tree);
    patchSettings({ structure: tree });
  }

  function addNode(kind: StructureNode["kind"]) {
    const node: StructureNode = {
      id: newId(kind),
      kind,
      field: null,
      groupingType: "items",
      title: null,
      selection: [],
      order: [],
      filter: null,
      children: [],
      rows: [],
      columns: [],
      chartType: "bar",
    };
    updateStructure((tree) => {
      // Новый узел вкладывается в выделенную группировку — так строится иерархия
      // «клиент → товар → месяц» без отдельного действия «вложить».
      const target = path ? nodeAt(tree, path) : null;
      if (target && target.kind === "grouping") {
        target.children.push(node);
        setPath([...path!, target.children.length - 1]);
      } else {
        tree.push(node);
        setPath([tree.length - 1]);
      }
    });
  }

  function addInto(bucket: "rows" | "columns") {
    if (!path || !selected || selected.kind !== "table") return;
    updateStructure((tree) => {
      const target = nodeAt(tree, path)!;
      target[bucket].push({
        id: newId("g"), kind: "grouping", field: null, groupingType: "items",
        title: null, selection: [], order: [], filter: null,
        children: [], rows: [], columns: [],
      });
    });
  }

  function patchNode(p: Partial<StructureNode>) {
    if (!path) return;
    updateStructure((tree) => {
      const n = nodeAt(tree, path);
      if (n) Object.assign(n, p);
    });
  }

  function removeNode() {
    if (!path) return;
    updateStructure((tree) => {
      const siblings = siblingsOf(tree, path);
      siblings.splice(path[path.length - 1], 1);
    });
    setPath(null);
  }

  function moveNode(delta: number) {
    if (!path) return;
    const i = path[path.length - 1];
    updateStructure((tree) => {
      const siblings = siblingsOf(tree, path);
      const j = i + delta;
      if (j < 0 || j >= siblings.length) return;
      [siblings[i], siblings[j]] = [siblings[j], siblings[i]];
    });
    setPath([...path.slice(0, -1), i + delta]);
  }

  const groupable = fields.filter((f) => f.usableInGroup);

  return (
    <div style={{ display: "grid", gridTemplateRows: "minmax(240px, 1fr) auto", gap: 8, minHeight: 0 }}>
      <div className="dcs-split" style={{ gridTemplateColumns: "300px minmax(0, 1fr)" }}>
        <Panel title="Report structure">
          <div style={{ margin: -8, display: "flex", flexDirection: "column", height: "100%" }}>
            <Toolbar>
              <Btn small onClick={() => addNode("grouping")} title="Grouping or detail records">
                ＋ Grouping
              </Btn>
              <Btn small onClick={() => addNode("table")} title="Cross-tab: rows × columns">
                ＋ Table
              </Btn>
              <Btn small onClick={() => addNode("chart")}>＋ Chart</Btn>
              <Spacer />
              <Btn small disabled={!path} onClick={() => moveNode(-1)}>↑</Btn>
              <Btn small disabled={!path} onClick={() => moveNode(1)}>↓</Btn>
              <Btn small kind="danger" disabled={!path} onClick={removeNode}>🗑</Btn>
            </Toolbar>
            <div className="dcs-tree" style={{ flex: 1, padding: 4 }}>
              {settings.structure.length === 0 ? (
                <Empty>Empty structure. Add a grouping — or compose right away to see the grand total.</Empty>
              ) : (
                <StructureTree
                  nodes={settings.structure}
                  parentPath={[]}
                  selected={path}
                  onSelect={setPath}
                  fields={fields}
                />
              )}
            </div>
          </div>
        </Panel>

        <Panel title={selected ? nodeCaption(selected, fields) : "Node properties"} scroll>
          {!selected ? (
            <Empty>Select a structure node.</Empty>
          ) : (
            <>
              <Grid cols={3}>
                {selected.kind === "grouping" && (
                  <>
                    <Field label="Grouping field"
                           hint="«Detail records» outputs the source rows without collapsing">
                      <Select
                        value={selected.field ?? ""}
                        onChange={(v) => patchNode({ field: v || null })}
                        options={[
                          { value: "", label: "— Detail records —" },
                          ...groupable.map((f) => ({ value: f.id, label: f.title })),
                        ]}
                      />
                    </Field>
                    <Field label="Grouping type">
                      <Select
                        value={selected.groupingType}
                        onChange={(v) => patchNode({ groupingType: v as GroupingType })}
                        options={[
                          { value: "items", label: "Items" },
                          { value: "hierarchy", label: "Hierarchy" },
                          { value: "hierarchyOnly", label: "Hierarchy only" },
                        ]}
                      />
                    </Field>
                  </>
                )}
                {selected.kind === "chart" && (
                  <Field label="Chart type">
                    <Select value={selected.chartType ?? "bar"}
                            onChange={(v) => patchNode({ chartType: v })}
                            options={[
                              { value: "bar", label: "Bars" },
                              { value: "line", label: "Line" },
                              { value: "pie", label: "Pie" },
                            ]} />
                  </Field>
                )}
                <Field label="Title">
                  <TextInput value={selected.title ?? ""} onChange={(v) => patchNode({ title: v || null })} />
                </Field>
              </Grid>

              {selected.groupingType !== "items" && selected.kind === "grouping" && (
                <Banner kind="warn">
                  Hierarchical totals need a parent field, which the schema does not describe yet —
                  the engine builds this grouping as a flat one and says so in the warnings.
                </Banner>
              )}

              {selected.kind === "table" && (
                <Grid cols={2}>
                  <BucketEditor
                    label="Row groupings"
                    nodes={selected.rows}
                    fields={groupable}
                    onAdd={() => addInto("rows")}
                    onChange={(rows) => patchNode({ rows })}
                  />
                  <BucketEditor
                    label="Column groupings"
                    nodes={selected.columns}
                    fields={groupable}
                    onAdd={() => addInto("columns")}
                    onChange={(columns) => patchNode({ columns })}
                  />
                </Grid>
              )}

              <TabBar
                active={nodeTab}
                onChange={(v) => setNodeTab(v as typeof nodeTab)}
                tabs={[
                  { id: "selection", label: "Selected fields", badge: selected.selection.length },
                  { id: "order", label: "Order", badge: selected.order.length },
                  { id: "filter", label: "Level filter" },
                ]}
              />

              {nodeTab === "selection" && (
                <SelectionEditor
                  value={selected.selection}
                  fields={fields.filter((f) => f.usableInSelection)}
                  onChange={(selection) => patchNode({ selection })}
                  emptyHint="Empty — the fields are inherited from the report settings."
                />
              )}

              {nodeTab === "order" && (
                <OrderEditor
                  value={selected.order}
                  fields={fields.filter((f) => f.usableInOrder)}
                  onChange={(order) => patchNode({ order })}
                />
              )}

              {nodeTab === "filter" && (
                <>
                  <Banner kind="info">
                    A level filter is checked on the already computed grouping — the analogue of HAVING.
                    To filter source records use the report filter below.
                  </Banner>
                  <FilterBuilder
                    group={selected.filter}
                    onChange={(filter) => patchNode({ filter })}
                    fields={fields}
                    parameters={schema.parameters}
                  />
                </>
              )}
            </>
          )}
        </Panel>
      </div>

      <div style={{ overflow: "auto", maxHeight: "40vh" }}>
        <Collapsible title="Report filter" defaultOpen={false}>
          <FilterBuilder
            group={settings.filter}
            onChange={(filter) => patchSettings({ filter })}
            fields={fields}
            parameters={schema.parameters}
          />
        </Collapsible>

        <Collapsible title="Selected fields (default)" defaultOpen={false}>
          <SelectionEditor
            value={settings.selection}
            fields={fields.filter((f) => f.usableInSelection)}
            onChange={(selection) => patchSettings({ selection })}
            emptyHint="Empty — every resource is shown."
          />
        </Collapsible>

        <Collapsible title="Order (default)" defaultOpen={false}>
          <OrderEditor
            value={settings.order}
            fields={fields.filter((f) => f.usableInOrder)}
            onChange={(order) => patchSettings({ order })}
          />
        </Collapsible>

        <Collapsible title="Conditional appearance" defaultOpen={false}>
          <AppearanceEditor
            items={settings.conditionalAppearance}
            fields={fields}
            schema={schema}
            onChange={(conditionalAppearance) => patchSettings({ conditionalAppearance })}
          />
        </Collapsible>

        <Collapsible title="Data parameters" defaultOpen={false}>
          <ParameterValues schema={schema} settings={settings} onChange={patchSettings} />
        </Collapsible>

        <Collapsible title="Output parameters" defaultOpen={false}>
          <Grid cols={3}>
            <Field label="Report title">
              <TextInput value={settings.outputParameters.title ?? ""}
                         onChange={(v) => patchOutput({ title: v || null })} />
            </Field>
            <Field label="Grand totals (vertical)">
              <Select value={settings.outputParameters.verticalTotals}
                      onChange={(v) => patchOutput({ verticalTotals: v as "begin" | "end" | "none" })}
                      options={[
                        { value: "begin", label: "At the beginning" },
                        { value: "end", label: "At the end" },
                        { value: "none", label: "Do not show" },
                      ]} />
            </Field>
            <Field label="Grand totals (horizontal)">
              <Select value={settings.outputParameters.horizontalTotals}
                      onChange={(v) => patchOutput({ horizontalTotals: v as "begin" | "end" | "none" })}
                      options={[
                        { value: "begin", label: "At the beginning" },
                        { value: "end", label: "At the end" },
                        { value: "none", label: "Do not show" },
                      ]} />
            </Field>
            <Field label="Grouping placement">
              <Select value={settings.outputParameters.groupPlacement}
                      onChange={(v) => patchOutput({ groupPlacement: v as "begin" | "end" })}
                      options={[
                        { value: "begin", label: "Total above the data" },
                        { value: "end", label: "Total below the data" },
                      ]} />
            </Field>
            <Field label="Source-record limit"
                   hint="Hitting the limit marks the result as truncated">
              <NumberInput value={settings.outputParameters.maxRows ?? null}
                           onChange={(v) => patchOutput({ maxRows: v })} placeholder="100000" />
            </Field>
            <Field label="&nbsp;">
              <div className="dcs-chips">
                <Check label="Show title" value={settings.outputParameters.showTitle}
                       onChange={(v) => patchOutput({ showTitle: v })} />
                <Check label="Show parameters" value={settings.outputParameters.showParameters}
                       onChange={(v) => patchOutput({ showParameters: v })} />
                <Check label="Show filter" value={settings.outputParameters.showFilter}
                       onChange={(v) => patchOutput({ showFilter: v })} />
              </div>
            </Field>
          </Grid>
        </Collapsible>
      </div>
    </div>
  );

  function patchOutput(p: Partial<DcsSettings["outputParameters"]>) {
    patchSettings({ outputParameters: { ...settings.outputParameters, ...p } });
  }
}

// ----------------------------------------------------------------- дерево

function StructureTree({
  nodes, parentPath, selected, onSelect, fields,
}: {
  nodes: StructureNode[];
  parentPath: number[];
  selected: number[] | null;
  onSelect: (p: number[]) => void;
  fields: AvailableField[];
}) {
  return (
    <>
      {nodes.map((n, i) => {
        const p = [...parentPath, i];
        const isSel = selected != null && samePath(selected, p);
        return (
          <div key={n.id ?? i}>
            <div
              className={`dcs-tree__row${isSel ? " is-selected" : ""}`}
              style={{ paddingLeft: 6 + parentPath.length * 14 }}
              onClick={() => onSelect(p)}
            >
              <span className="dcs-tree__kind">{kindIcon(n.kind)}</span>
              <span>{nodeCaption(n, fields)}</span>
            </div>
            {n.children.length > 0 && (
              <StructureTree nodes={n.children} parentPath={p} selected={selected}
                             onSelect={onSelect} fields={fields} />
            )}
          </div>
        );
      })}
    </>
  );
}

function kindIcon(kind: StructureNode["kind"]): string {
  return kind === "table" ? "▦" : kind === "chart" ? "▨" : "≡";
}

function nodeCaption(n: StructureNode, fields: AvailableField[]): string {
  if (n.title) return n.title;
  if (n.kind === "table") return "Cross-tab";
  if (n.kind === "chart") return "Chart";
  if (!n.field) return "Detail records";
  return fields.find((f) => f.id === n.field)?.title ?? n.field;
}

/** Группировки строк/колонок кросс-таблицы — плоский список, вложенность даёт порядок. */
function BucketEditor({
  label, nodes, fields, onAdd, onChange,
}: {
  label: string;
  nodes: StructureNode[];
  fields: AvailableField[];
  onAdd: () => void;
  onChange: (n: StructureNode[]) => void;
}) {
  return (
    <Field label={label}>
      {nodes.length === 0 && <Empty>Not set</Empty>}
      {nodes.map((n, i) => (
        <div key={n.id ?? i} className="dcs-filter-row" style={{ gridTemplateColumns: "1fr auto auto" }}>
          <Select
            value={n.field ?? ""}
            onChange={(v) => {
              const next = clone(nodes);
              next[i].field = v || null;
              onChange(next);
            }}
            options={fields.map((f) => ({ value: f.id, label: f.title }))}
            empty="— field —"
          />
          <Btn small disabled={i === 0} onClick={() => onChange(move(nodes, i, i - 1))}>↑</Btn>
          <Btn small kind="danger" onClick={() => onChange(nodes.filter((_, idx) => idx !== i))}>✕</Btn>
        </div>
      ))}
      <Toolbar><Btn small onClick={onAdd}>＋ Grouping</Btn></Toolbar>
    </Field>
  );
}

// ------------------------------------------------------------- подпанели

function SelectionEditor({
  value, fields, onChange, emptyHint,
}: {
  value: string[]; fields: AvailableField[];
  onChange: (v: string[]) => void; emptyHint?: string;
}) {
  return (
    <>
      <div className="dcs-chips" style={{ marginBottom: 6 }}>
        {value.map((id, i) => (
          <span key={id} className="dcs-chip">
            {fields.find((f) => f.id === id)?.title ?? id}
            <button type="button" onClick={() => onChange(value.filter((_, idx) => idx !== i))}>✕</button>
          </span>
        ))}
        {value.length === 0 && <span className="dcs-muted" style={{ fontSize: 11 }}>{emptyHint}</span>}
      </div>
      <Toolbar>
        <select
          className="dcs-input"
          style={{ maxWidth: 260 }}
          value=""
          onChange={(e) => { if (e.target.value && !value.includes(e.target.value)) onChange([...value, e.target.value]); }}
        >
          <option value="">＋ field…</option>
          {fields.filter((f) => !value.includes(f.id))
                 .map((f) => <option key={f.id} value={f.id}>{f.title}</option>)}
        </select>
        <Btn small onClick={() => onChange(fields.map((f) => f.id))}>All</Btn>
        <Btn small onClick={() => onChange([])}>Clear</Btn>
      </Toolbar>
    </>
  );
}

function OrderEditor({
  value, fields, onChange,
}: { value: OrderItem[]; fields: AvailableField[]; onChange: (v: OrderItem[]) => void }) {
  return (
    <>
      {value.length === 0 && <Empty>No order — groupings are sorted by value.</Empty>}
      {value.map((o, i) => (
        <div key={i} className="dcs-filter-row" style={{ gridTemplateColumns: "1fr 140px auto auto" }}>
          <Select
            value={o.field}
            onChange={(v) => patch(i, { field: v })}
            options={fields.map((f) => ({ value: f.id, label: f.title }))}
            empty="— field —"
          />
          <Select
            value={o.direction}
            onChange={(v) => patch(i, { direction: v as "asc" | "desc" })}
            options={[{ value: "asc", label: "Ascending" }, { value: "desc", label: "Descending" }]}
          />
          <Btn small disabled={i === 0} onClick={() => onChange(move(value, i, i - 1))}>↑</Btn>
          <Btn small kind="danger" onClick={() => onChange(value.filter((_, idx) => idx !== i))}>✕</Btn>
        </div>
      ))}
      <Toolbar>
        <Btn small onClick={() => onChange([...value, { field: fields[0]?.id ?? "", direction: "asc" }])}>
          ＋ Order by
        </Btn>
      </Toolbar>
    </>
  );

  function patch(i: number, p: Partial<OrderItem>) {
    const next = value.slice();
    next[i] = { ...next[i], ...p };
    onChange(next);
  }
}

function AppearanceEditor({
  items, fields, schema, onChange,
}: {
  items: AppearanceItem[]; fields: AvailableField[];
  schema: DcsSchema; onChange: (v: AppearanceItem[]) => void;
}) {
  const [open, setOpen] = useState(0);

  function patch(i: number, p: Partial<AppearanceItem>) {
    const next = clone(items);
    next[i] = { ...next[i], ...p };
    onChange(next);
  }

  return (
    <>
      {items.length === 0 && <Empty>No appearance rules.</Empty>}
      {items.map((item, i) => (
        <div key={item.id ?? i} style={{ border: "1px solid var(--border)", borderRadius: 4, marginBottom: 6 }}>
          <Toolbar>
            <Btn small kind="ghost" onClick={() => setOpen(open === i ? -1 : i)}>
              {open === i ? "▾" : "▸"} Rule {i + 1}
            </Btn>
            <Spacer />
            <Check label="Enabled" value={!item.disabled} onChange={(v) => patch(i, { disabled: !v })} />
            <Btn small kind="danger" onClick={() => onChange(items.filter((_, idx) => idx !== i))}>🗑</Btn>
          </Toolbar>
          {open === i && (
            <div style={{ padding: 8 }}>
              <Field label="Condition">
                <FilterBuilder group={item.filter} onChange={(filter) => patch(i, { filter })}
                               fields={fields} parameters={schema.parameters}
                               emptyText="No condition — the appearance always applies." />
              </Field>

              <Field label="Formatted fields" hint="Empty — the whole row">
                <div className="dcs-chips">
                  {item.fields.map((f) => (
                    <span key={f} className="dcs-chip">
                      {fields.find((x) => x.id === f)?.title ?? f}
                      <button type="button"
                              onClick={() => patch(i, { fields: item.fields.filter((x) => x !== f) })}>✕</button>
                    </span>
                  ))}
                  <select className="dcs-input" style={{ maxWidth: 200 }} value=""
                          onChange={(e) => {
                            if (e.target.value && !item.fields.includes(e.target.value)) {
                              patch(i, { fields: [...item.fields, e.target.value] });
                            }
                          }}>
                    <option value="">＋ field…</option>
                    {fields.map((f) => <option key={f.id} value={f.id}>{f.title}</option>)}
                  </select>
                </div>
              </Field>

              <Grid cols={4}>
                <Field label="Text colour">
                  <TextInput value={item.appearance.textColor ?? ""}
                             placeholder="#c62828"
                             onChange={(v) => patchAppearance(i, { textColor: v || null })} />
                </Field>
                <Field label="Background">
                  <TextInput value={item.appearance.backColor ?? ""}
                             placeholder="#fff3e0"
                             onChange={(v) => patchAppearance(i, { backColor: v || null })} />
                </Field>
                <Field label="Format">
                  <TextInput value={item.appearance.format ?? ""}
                             onChange={(v) => patchAppearance(i, { format: v || null })} />
                </Field>
                <Field label="Style">
                  <div className="dcs-chips">
                    <Check label="bold" value={!!item.appearance.bold}
                           onChange={(v) => patchAppearance(i, { bold: v || null })} />
                    <Check label="italic" value={!!item.appearance.italic}
                           onChange={(v) => patchAppearance(i, { italic: v || null })} />
                  </div>
                </Field>
              </Grid>

              <Field label="Areas" hint="Empty — every area">
                <div className="dcs-chips">
                  {(["header", "details", "groupTotals", "grandTotal"] as AppearanceArea[]).map((a) => (
                    <Check
                      key={a}
                      label={AREA_LABELS[a]}
                      value={item.areas.includes(a)}
                      onChange={(v) => patch(i, {
                        areas: v ? [...item.areas, a] : item.areas.filter((x) => x !== a),
                      })}
                    />
                  ))}
                </div>
              </Field>
            </div>
          )}
        </div>
      ))}
      <Toolbar>
        <Btn small onClick={() => {
          onChange([...items, {
            id: newId("appear"), filter: null, fields: [],
            appearance: {}, areas: [], disabled: false,
          }]);
          setOpen(items.length);
        }}>
          ＋ Appearance rule
        </Btn>
      </Toolbar>
    </>
  );

  function patchAppearance(i: number, p: Partial<AppearanceItem["appearance"]>) {
    patch(i, { appearance: { ...items[i].appearance, ...p } });
  }
}

function ParameterValues({
  schema, settings, onChange,
}: {
  schema: DcsSchema; settings: DcsSettings;
  onChange: (p: Partial<DcsSettings>) => void;
}) {
  if (schema.parameters.length === 0) return <Empty>The schema declares no parameters.</Empty>;
  return (
    <Grid cols={3}>
      {schema.parameters.map((p) => {
        const value = settings.dataParameters[p.name];
        return (
          <Field key={p.name} label={p.title || p.name}
                 hint={p.required ? "required" : undefined}>
            {p.availableValues.length > 0 ? (
              <Select
                value={value == null ? "" : String(value)}
                onChange={(v) => set(p.name, v)}
                options={p.availableValues.map((av) => ({
                  value: String(av.value), label: av.presentation || String(av.value),
                }))}
                empty="—"
                disabled={p.useRestriction}
              />
            ) : (
              <TextInput value={value == null ? "" : String(value)}
                         readOnly={p.useRestriction}
                         onChange={(v) => set(p.name, v)} />
            )}
          </Field>
        );
      })}
    </Grid>
  );

  function set(name: string, raw: string) {
    const p = schema.parameters.find((x) => x.name === name);
    let value: unknown = raw;
    if (raw === "") value = null;
    else if (p?.valueType === "number") { const n = Number(raw); value = Number.isNaN(n) ? raw : n; }
    else if (p?.valueType === "boolean") value = raw === "true";
    onChange({ dataParameters: { ...settings.dataParameters, [name]: value } });
  }
}

const AREA_LABELS: Record<AppearanceArea, string> = {
  header: "header",
  details: "detail records",
  groupTotals: "grouping totals",
  grandTotal: "grand total",
};

// ------------------------------------------------------------- навигация

function nodeAt(tree: StructureNode[], path: number[]): StructureNode | null {
  let nodes = tree;
  let node: StructureNode | null = null;
  for (const i of path) {
    node = nodes[i] ?? null;
    if (!node) return null;
    nodes = node.children;
  }
  return node;
}

function siblingsOf(tree: StructureNode[], path: number[]): StructureNode[] {
  let nodes = tree;
  for (let i = 0; i < path.length - 1; i++) nodes = nodes[path[i]].children;
  return nodes;
}

function samePath(a: number[], b: number[]): boolean {
  return a.length === b.length && a.every((v, i) => v === b[i]);
}
