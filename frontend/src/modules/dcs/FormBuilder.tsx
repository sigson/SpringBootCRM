import { useState } from "react";
import type { AvailableField, DcsSchema, FormNode, FormNodeKind, ReportForm } from "./types";
import {
  Banner, Btn, Check, Empty, Field, Grid, MasterList, NumberInput, Panel, Select,
  Spacer, TextInput, Toolbar, clone, move, newId,
} from "./ui";

/**
 * <h2>Вкладка «Формы» — построитель интерфейсов отчёта.</h2>
 *
 * <p>Отчёт редко открывают «как есть»: пользователю нужна своя форма — два поля периода,
 * один отбор и кнопка «Сформировать», а не полный конструктор настроек. Здесь такая
 * форма собирается деревом элементов, как конструктор интерфейсов в хосте: слева
 * структура, справа свойства, снизу живой предпросмотр.
 *
 * <p>Элементы формы — не произвольные контролы, а <b>привязки к частям отчёта</b>:
 * «параметр», «отбор», «выбранные поля», «структура», «результат», «кнопка». Форма
 * поэтому не может рассинхронизироваться со схемой: она ссылается на её параметры и
 * поля по именам, а рисованием занимается runtime.
 */
export function FormsTab({
  forms, onChange, schema, fields,
}: {
  forms: ReportForm[];
  onChange: (f: ReportForm[]) => void;
  schema: DcsSchema;
  fields: AvailableField[];
}) {
  const [selected, setSelected] = useState(0);
  const [path, setPath] = useState<number[] | null>(null);
  const current = forms[selected] ?? null;
  const node = current && path ? nodeAt(current.nodes, path) : null;

  function patchForm(p: Partial<ReportForm>) {
    if (!current) return;
    const next = clone(forms);
    next[selected] = { ...next[selected], ...p };
    onChange(next);
  }

  function updateNodes(mutate: (nodes: FormNode[]) => void) {
    if (!current) return;
    const nodes = clone(current.nodes);
    mutate(nodes);
    patchForm({ nodes });
  }

  function addNode(kind: FormNodeKind) {
    const fresh: FormNode = {
      id: newId(kind),
      kind,
      title: defaultTitle(kind),
      target: kind === "parameter" ? (schema.parameters[0]?.name ?? null) : null,
      action: kind === "button" ? "compose" : null,
      span: kind === "group" || kind === "tabs" || kind === "result" ? 12 : 4,
      readOnly: false,
      children: [],
    };
    updateNodes((nodes) => {
      const parent = path ? nodeAt(nodes, path) : null;
      if (parent && CONTAINERS.includes(parent.kind)) {
        parent.children.push(fresh);
        setPath([...path!, parent.children.length - 1]);
      } else {
        nodes.push(fresh);
        setPath([nodes.length - 1]);
      }
    });
  }

  function patchNode(p: Partial<FormNode>) {
    if (!path) return;
    updateNodes((nodes) => {
      const n = nodeAt(nodes, path);
      if (n) Object.assign(n, p);
    });
  }

  return (
    <div className="dcs-split" style={{ gridTemplateColumns: "200px 280px minmax(0, 1fr)" }}>
      <Panel title="Forms">
        <div style={{ margin: -8 }}>
          <MasterList
            items={forms}
            selected={selected}
            onSelect={(i) => { setSelected(i); setPath(null); }}
            addLabel="Form"
            onAdd={() => {
              onChange([...forms, defaultForm(`Form ${forms.length + 1}`, schema)]);
              setSelected(forms.length);
            }}
            onRemove={(i) => { onChange(forms.filter((_, idx) => idx !== i)); setSelected(Math.max(0, i - 1)); }}
            onMove={(from, to) => { onChange(move(forms, from, to)); setSelected(to); }}
            emptyText="No forms — the report opens in the standard settings form."
            label={(f) => (
              <>
                <span>{f.name}</span>
                <Spacer />
                <span className="dcs-muted" style={{ fontSize: 10 }}>{PURPOSES[f.purpose]}</span>
              </>
            )}
          />
        </div>
      </Panel>

      <Panel title="Form structure">
        {!current ? <Empty>Select a form.</Empty> : (
          <div style={{ margin: -8, display: "flex", flexDirection: "column", height: "100%" }}>
            <Toolbar>
              <select className="dcs-input" style={{ maxWidth: 150 }} value=""
                      onChange={(e) => { if (e.target.value) addNode(e.target.value as FormNodeKind); }}>
                <option value="">＋ element…</option>
                {NODE_KINDS.map((k) => <option key={k.value} value={k.value}>{k.label}</option>)}
              </select>
              <Spacer />
              <Btn small disabled={!path} onClick={() => moveNode(-1)}>↑</Btn>
              <Btn small disabled={!path} onClick={() => moveNode(1)}>↓</Btn>
              <Btn small kind="danger" disabled={!path} onClick={removeNode}>🗑</Btn>
            </Toolbar>
            <div className="dcs-tree" style={{ flex: 1, padding: 4 }}>
              {current.nodes.length === 0 ? (
                <Empty>Empty form. Add elements.</Empty>
              ) : (
                <NodeTree nodes={current.nodes} parentPath={[]} selected={path} onSelect={setPath} />
              )}
            </div>
          </div>
        )}
      </Panel>

      <div style={{ display: "grid", gridTemplateRows: "auto minmax(0, 1fr)", gap: 8, minHeight: 0 }}>
        <Panel title={node ? `Element: ${KIND_LABELS[node.kind]}` : "Form properties"}>
          {!current ? <Empty>Select a form.</Empty> : !node ? (
            <Grid cols={2}>
              <Field label="Form name">
                <TextInput value={current.name} onChange={(v) => patchForm({ name: v })} />
              </Field>
              <Field label="Purpose" hint="Which form opens when the report is run">
                <Select value={current.purpose}
                        onChange={(v) => patchForm({ purpose: v as ReportForm["purpose"] })}
                        options={Object.entries(PURPOSES).map(([value, label]) => ({ value, label }))} />
              </Field>
            </Grid>
          ) : (
            <Grid cols={3}>
              <Field label="Title">
                <TextInput value={node.title ?? ""} onChange={(v) => patchNode({ title: v || null })} />
              </Field>
              {node.kind === "parameter" && (
                <Field label="Schema parameter">
                  <Select value={node.target ?? ""} empty="— parameter —"
                          onChange={(v) => patchNode({ target: v || null })}
                          options={schema.parameters.map((p) => ({
                            value: p.name, label: p.title || p.name,
                          }))} />
                </Field>
              )}
              {(node.kind === "filter" || node.kind === "selection") && (
                <Field label="Field" hint="Empty — the whole section">
                  <Select value={node.target ?? ""} empty="— all —"
                          onChange={(v) => patchNode({ target: v || null })}
                          options={fields.map((f) => ({ value: f.id, label: f.title }))} />
                </Field>
              )}
              {node.kind === "button" && (
                <Field label="Action">
                  <Select value={node.action ?? "compose"}
                          onChange={(v) => patchNode({ action: v as FormNode["action"] })}
                          options={[
                            { value: "compose", label: "Compose" },
                            { value: "export-xlsx", label: "Export to XLSX" },
                            { value: "export-csv", label: "Export to CSV" },
                            { value: "reset", label: "Reset settings" },
                          ]} />
                </Field>
              )}
              <Field label="Width (of 12)">
                <NumberInput value={node.span ?? 4} onChange={(v) => patchNode({ span: v ?? 4 })} />
              </Field>
              <Field label="&nbsp;">
                <Check label="Read-only" value={!!node.readOnly}
                       onChange={(v) => patchNode({ readOnly: v })} />
              </Field>
            </Grid>
          )}
        </Panel>

        <Panel title="Preview" scroll>
          {!current ? <Empty>—</Empty> : (
            <>
              <Banner kind="info">
                The preview shows the layout. The controls are rendered live by the report form
                when it is opened.
              </Banner>
              <div className="dcs-form-preview">
                <PreviewNodes nodes={current.nodes} parentPath={[]} selected={path}
                              onSelect={setPath} schema={schema} />
              </div>
            </>
          )}
        </Panel>
      </div>
    </div>
  );

  function moveNode(delta: number) {
    if (!path) return;
    const i = path[path.length - 1];
    updateNodes((nodes) => {
      const siblings = siblingsOf(nodes, path);
      const j = i + delta;
      if (j < 0 || j >= siblings.length) return;
      [siblings[i], siblings[j]] = [siblings[j], siblings[i]];
    });
    setPath([...path.slice(0, -1), i + delta]);
  }

  function removeNode() {
    if (!path) return;
    updateNodes((nodes) => {
      siblingsOf(nodes, path).splice(path[path.length - 1], 1);
    });
    setPath(null);
  }
}

function NodeTree({
  nodes, parentPath, selected, onSelect,
}: {
  nodes: FormNode[]; parentPath: number[];
  selected: number[] | null; onSelect: (p: number[]) => void;
}) {
  return (
    <>
      {nodes.map((n, i) => {
        const p = [...parentPath, i];
        const isSel = selected != null && p.length === selected.length && p.every((v, k) => v === selected[k]);
        return (
          <div key={n.id ?? i}>
            <div className={`dcs-tree__row${isSel ? " is-selected" : ""}`}
                 style={{ paddingLeft: 6 + parentPath.length * 14 }}
                 onClick={() => onSelect(p)}>
              <span className="dcs-tree__kind">{KIND_LABELS[n.kind]}</span>
              <span>{n.title || n.target || ""}</span>
            </div>
            {n.children.length > 0 && (
              <NodeTree nodes={n.children} parentPath={p} selected={selected} onSelect={onSelect} />
            )}
          </div>
        );
      })}
    </>
  );
}

function PreviewNodes({
  nodes, parentPath, selected, onSelect, schema,
}: {
  nodes: FormNode[]; parentPath: number[];
  selected: number[] | null; onSelect: (p: number[]) => void; schema: DcsSchema;
}) {
  return (
    <>
      {nodes.map((n, i) => {
        const p = [...parentPath, i];
        const isSel = selected != null && p.length === selected.length && p.every((v, k) => v === selected[k]);
        return (
          <div
            key={n.id ?? i}
            className={`dcs-form-node${isSel ? " is-selected" : ""}`}
            style={{ gridColumn: `span ${Math.min(12, Math.max(1, n.span ?? 4))}` }}
            onClick={(e) => { e.stopPropagation(); onSelect(p); }}
          >
            <div className="dcs-form-node__label">{KIND_LABELS[n.kind]}</div>
            <PreviewBody node={n} schema={schema} />
            {n.children.length > 0 && (
              <div className="dcs-form-preview" style={{ marginTop: 6 }}>
                <PreviewNodes nodes={n.children} parentPath={p} selected={selected}
                              onSelect={onSelect} schema={schema} />
              </div>
            )}
          </div>
        );
      })}
    </>
  );
}

function PreviewBody({ node, schema }: { node: FormNode; schema: DcsSchema }) {
  switch (node.kind) {
    case "parameter": {
      const p = schema.parameters.find((x) => x.name === node.target);
      return (
        <label className="dcs-field">
          <span className="dcs-field__label">{node.title || p?.title || node.target || "parameter"}</span>
          <input className="dcs-input" disabled placeholder={p?.valueType ?? "value"} />
        </label>
      );
    }
    case "button":
      return <button type="button" className="dcs-btn dcs-btn--primary" disabled>{node.title || "Button"}</button>;
    case "label":
      return <div style={{ fontWeight: 600 }}>{node.title}</div>;
    case "spacer":
      return <div style={{ height: 8 }} />;
    case "result":
      return <div className="dcs-muted" style={{ padding: "12px 0", textAlign: "center" }}>
        report result area
      </div>;
    case "filter":
      return <div className="dcs-muted">filter{node.target ? `: ${node.target}` : ""}</div>;
    case "selection":
      return <div className="dcs-muted">selected fields{node.target ? `: ${node.target}` : ""}</div>;
    case "structure":
      return <div className="dcs-muted">report structure</div>;
    default:
      return node.title ? <div style={{ fontWeight: 600 }}>{node.title}</div> : null;
  }
}

/** Форма по умолчанию: период, отбор, кнопка и результат — то, что нужно в 90% случаев. */
export function defaultForm(name: string, schema: DcsSchema): ReportForm {
  const params: FormNode[] = schema.parameters.slice(0, 4).map((p) => ({
    id: newId("parameter"), kind: "parameter", title: p.title || p.name,
    target: p.name, action: null, span: 3, readOnly: false, children: [],
  }));
  return {
    id: newId("form"),
    name,
    purpose: "settings",
    nodes: [
      {
        id: newId("group"), kind: "group", title: "Parameters", target: null, action: null,
        span: 12, readOnly: false, children: params,
      },
      {
        id: newId("group"), kind: "group", title: "Filter", target: null, action: null,
        span: 12, readOnly: false,
        children: [{
          id: newId("filter"), kind: "filter", title: null, target: null, action: null,
          span: 12, readOnly: false, children: [],
        }],
      },
      {
        id: newId("button"), kind: "button", title: "Compose", target: null,
        action: "compose", span: 3, readOnly: false, children: [],
      },
      {
        id: newId("button"), kind: "button", title: "Export to XLSX", target: null,
        action: "export-xlsx", span: 3, readOnly: false, children: [],
      },
      {
        id: newId("result"), kind: "result", title: null, target: null, action: null,
        span: 12, readOnly: false, children: [],
      },
    ],
  };
}

function nodeAt(nodes: FormNode[], path: number[]): FormNode | null {
  let list = nodes;
  let node: FormNode | null = null;
  for (const i of path) {
    node = list[i] ?? null;
    if (!node) return null;
    list = node.children;
  }
  return node;
}

function siblingsOf(nodes: FormNode[], path: number[]): FormNode[] {
  let list = nodes;
  for (let i = 0; i < path.length - 1; i++) list = list[path[i]].children;
  return list;
}

function defaultTitle(kind: FormNodeKind): string | null {
  return kind === "group" ? "Group"
    : kind === "tabs" ? "Tabs"
    : kind === "tab" ? "Tab"
    : kind === "button" ? "Compose"
    : kind === "label" ? "Caption"
    : null;
}

const CONTAINERS: FormNodeKind[] = ["group", "tabs", "tab"];

const KIND_LABELS: Record<FormNodeKind, string> = {
  group: "group",
  tabs: "tabs",
  tab: "tab",
  parameter: "parameter",
  filter: "filter",
  selection: "fields",
  structure: "structure",
  result: "result",
  button: "button",
  label: "caption",
  spacer: "spacer",
};

const NODE_KINDS = (Object.keys(KIND_LABELS) as FormNodeKind[])
  .map((value) => ({ value, label: KIND_LABELS[value] }));

const PURPOSES: Record<ReportForm["purpose"], string> = {
  settings: "settings",
  result: "result",
  custom: "custom",
};
