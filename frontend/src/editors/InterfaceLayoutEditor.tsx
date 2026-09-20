import { useEffect, useMemo, useState, type CSSProperties } from "react";
import type { TypeEditorProps } from "./registry";
import { interfaceLayoutsApi, type LayoutNode } from "./interfaceLayout.api";
import { navigationApi } from "../api/navigation";
import type { NavKind, ToolDescriptor } from "../types/navigation";
import { useMetadata } from "../metadata/MetadataProvider";
import { FormField, CheckboxField } from "../components/Common";
import { useApiErrorHandler } from "../components/useApiErrorHandler";
import { useToast } from "../components/Toast";

/**
 * <h2>Візуальний конструктор дерева кастомізованого інтерфейсу.</h2>
 *
 * <p>Кастомний редактор для довідника {@code InterfaceLayout} (typeId=9300),
 * зареєстрований через {@code registerEditor(9300, …)}. Наслідує конструктор
 * інтерфейсів 1С:Підприємства:
 * <ul>
 *   <li><b>зліва</b> — дерево вузлів (GROUP/OBJECT/TOOL) з тулбаром: додати
 *       групу/об'єкт/інструмент, видалити, перемістити вгору/вниз, збільшити/
 *       зменшити рівень вкладеності;</li>
 *   <li><b>справа</b> — палітра властивостей виділеного вузла (заголовок,
 *       іконка; для OBJECT — вибір типу з метаданих; для TOOL — вибір інструмента).</li>
 * </ul>
 *
 * <p>Результат — JSON-реквізит {@code layout}. Видимість/фільтрація за правами
 * робиться на сервері при віддачі {@code /api/navigation}; тут лише декларація схеми.
 */
export function InterfaceLayoutEditor({ id, prefetchedCode, onClose, onSaved }: TypeEditorProps) {
  const { types } = useMetadata();
  const handleApiError = useApiErrorHandler();
  const toast = useToast();

  const [code, setCode] = useState(prefetchedCode ?? "");
  const [name, setName] = useState("");
  const [enabled, setEnabled] = useState(true);
  const [tree, setTree] = useState<LayoutNode[]>([]);
  const [selected, setSelected] = useState<Path | null>(null);
  const [tools, setTools] = useState<ToolDescriptor[]>([]);
  const [loading, setLoading] = useState(id != null);
  const [submitting, setSubmitting] = useState(false);

  // Кандидати для OBJECT-вузлів: усі типи, крім табличних частин.
  const objectTypes = useMemo(
    () => types.filter(t => !t.isTabularPart),
    [types],
  );

  useEffect(() => {
    navigationApi.tools().then(setTools).catch(() => setTools([]));
  }, []);

  useEffect(() => {
    if (id == null) { setLoading(false); return; }
    let cancelled = false;
    (async () => {
      try {
        const dto = await interfaceLayoutsApi.get(id);
        if (!cancelled) {
          setCode(dto.code);
          setName(dto.name);
          setEnabled(dto.enabled);
          setTree(normalize(dto.layout ?? []));
        }
      } catch (err) {
        if (!cancelled) handleApiError(err);
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => { cancelled = true; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id]);

  const selectedNode = selected ? nodeAt(tree, selected) : null;

  function addNode(kind: NavKind) {
    const node: LayoutNode =
      kind === "GROUP" ? { kind: "GROUP", title: "New group", icon: "📁", children: [] }
      : kind === "OBJECT" ? { kind: "OBJECT", typeId: objectTypes[0]?.typeId ?? null, children: [] }
      : { kind: "TOOL", tool: tools[0]?.key ?? "", children: [] };

    const t = clone(tree);
    let newPath: Path;
    if (selected && nodeAt(t, selected)?.kind === "GROUP") {
      const sel = nodeAt(t, selected)!;
      sel.children = sel.children ?? [];
      sel.children.push(node);
      newPath = [...selected, sel.children.length - 1];
    } else {
      t.push(node);
      newPath = [t.length - 1];
    }
    setTree(t);
    setSelected(newPath);
  }

  function removeSelected() {
    if (!selected) return;
    const t = clone(tree);
    const arr = siblingArray(t, selected);
    arr.splice(selected[selected.length - 1], 1);
    setTree(t);
    setSelected(null);
  }

  function move(dir: "up" | "down") {
    if (!selected) return;
    const t = clone(tree);
    const arr = siblingArray(t, selected);
    const i = selected[selected.length - 1];
    const j = dir === "up" ? i - 1 : i + 1;
    if (j < 0 || j >= arr.length) return;
    [arr[i], arr[j]] = [arr[j], arr[i]];
    setTree(t);
    setSelected([...selected.slice(0, -1), j]);
  }

  /** Збільшити рівень вкладеності: зробити дочірнім попередньої сестринської групи. */
  function indent() {
    if (!selected) return;
    const t = clone(tree);
    const arr = siblingArray(t, selected);
    const i = selected[selected.length - 1];
    if (i <= 0) return;
    const prev = arr[i - 1];
    if (prev.kind !== "GROUP") return;   // вкладати можна лише у групу
    prev.children = prev.children ?? [];
    const [moved] = arr.splice(i, 1);
    prev.children.push(moved);
    setTree(t);
    setSelected([...selected.slice(0, -1), i - 1, prev.children.length - 1]);
  }

  /** Зменшити рівень вкладеності: підняти до батьківського рівня (після батька). */
  function outdent() {
    if (!selected || selected.length < 2) return;
    const t = clone(tree);
    const parentPath = selected.slice(0, -1);
    const arr = siblingArray(t, selected);
    const i = selected[selected.length - 1];
    const grandArr = siblingArray(t, parentPath);
    const parentIdx = parentPath[parentPath.length - 1];
    const [moved] = arr.splice(i, 1);
    grandArr.splice(parentIdx + 1, 0, moved);
    setTree(t);
    setSelected([...parentPath.slice(0, -1), parentIdx + 1]);
  }

  function patchSelected(patch: Partial<LayoutNode>) {
    if (!selected) return;
    const t = clone(tree);
    const n = nodeAt(t, selected);
    if (!n) return;
    Object.assign(n, patch);
    setTree(t);
  }

  async function save() {
    if (!name.trim()) { toast.error("Enter the interface name"); return; }
    setSubmitting(true);
    try {
      if (id == null) {
        const dto = await interfaceLayoutsApi.create({
          code: code.trim() || undefined, name: name.trim(), layout: tree, enabled,
        });
        onSaved?.(dto.id);
      } else {
        await interfaceLayoutsApi.update(id, { name: name.trim(), layout: tree, enabled });
        onSaved?.(id);
      }
    } catch (err) {
      handleApiError(err);
    } finally {
      setSubmitting(false);
    }
  }

  if (loading) return <div className="empty-state">Loading…</div>;

  return (
    <div>
      <div className="form-grid form-grid--cols-2">
        <FormField label="Code" value={code} onChange={setCode}
                   readOnly={id != null} description={id == null ? "Generated; can be changed" : undefined} />
        <FormField label="Name" value={name} onChange={setName} required autoFocus />
      </div>
      <CheckboxField label="Active" value={enabled} onChange={setEnabled} />

      <div className="section-label" style={{ margin: "14px 0 6px" }}>Interface structure</div>

      <div style={builderStyle}>
        {}
        <div style={treePanelStyle}>
          <div className="hflex" style={{ gap: 4, flexWrap: "wrap", marginBottom: 8 }}>
            <button className="btn btn--small" onClick={() => addNode("GROUP")} title="Add group">＋ Group</button>
            <button className="btn btn--small" onClick={() => addNode("OBJECT")} title="Add database object">＋ Object</button>
            <button className="btn btn--small" onClick={() => addNode("TOOL")} title="Add tool">＋ Tool</button>
            <span style={{ flex: 1 }} />
            <button className="btn btn--small" onClick={() => move("up")} disabled={!selected} title="Up">↑</button>
            <button className="btn btn--small" onClick={() => move("down")} disabled={!selected} title="Down">↓</button>
            <button className="btn btn--small" onClick={outdent} disabled={!selected} title="Decrease level">⇤</button>
            <button className="btn btn--small" onClick={indent} disabled={!selected} title="Increase level">⇥</button>
            <button className="btn btn--small btn--danger" onClick={removeSelected} disabled={!selected} title="Delete">🗑</button>
          </div>
          <div style={{ overflowY: "auto", maxHeight: 360 }}>
            {tree.length === 0
              ? <div className="muted" style={{ padding: 8 }}>Empty. Add a group or an object.</div>
              : <TreeNodes nodes={tree} parentPath={[]} selected={selected} onSelect={setSelected}
                           objectTypes={objectTypes} tools={tools} />}
          </div>
        </div>

        {}
        <div style={propPanelStyle}>
          {!selectedNode ? (
            <div className="muted" style={{ padding: 8 }}>Select a node to edit its properties.</div>
          ) : (
            <NodeProperties node={selectedNode} objectTypes={objectTypes} tools={tools} onPatch={patchSelected} />
          )}
        </div>
      </div>

      <div className="hflex" style={{ marginTop: 16, justifyContent: "flex-end", gap: 6 }}>
        <button className="btn" onClick={onClose} disabled={submitting}>Cancel</button>
        <button className="btn btn--primary" onClick={() => void save()} disabled={submitting}>
          {submitting ? "Saving…" : "Save"}
        </button>
      </div>
    </div>
  );
}

function TreeNodes({
  nodes, parentPath, selected, onSelect, objectTypes, tools,
}: {
  nodes: LayoutNode[]; parentPath: Path;
  selected: Path | null; onSelect: (p: Path) => void;
  objectTypes: { typeId: number; pluralLabel: string; iconHint: string }[];
  tools: ToolDescriptor[];
}) {
  return (
    <>
      {nodes.map((n, i) => {
        const path = [...parentPath, i];
        const isSel = selected != null && samePath(selected, path);
        return (
          <div key={path.join(".")}>
            <div
              onClick={() => onSelect(path)}
              style={{
                ...rowStyle,
                paddingLeft: 6 + parentPath.length * 16,
                background: isSel ? "var(--accent-soft, rgba(60,120,240,0.16))" : "transparent",
                fontWeight: isSel ? 600 : 400,
              }}
            >
              <span>{labelFor(n, objectTypes, tools)}</span>
            </div>
            {n.kind === "GROUP" && n.children && n.children.length > 0 && (
              <TreeNodes nodes={n.children} parentPath={path} selected={selected}
                         onSelect={onSelect} objectTypes={objectTypes} tools={tools} />
            )}
          </div>
        );
      })}
    </>
  );
}

function NodeProperties({
  node, objectTypes, tools, onPatch,
}: {
  node: LayoutNode;
  objectTypes: { typeId: number; pluralLabel: string; iconHint: string }[];
  tools: ToolDescriptor[];
  onPatch: (patch: Partial<LayoutNode>) => void;
}) {
  return (
    <div className="form-grid">
      <div className="muted" style={{ fontSize: 11 }}>Node type: <strong>{node.kind}</strong></div>

      {node.kind === "GROUP" && (
        <>
          <FormField label="Group title" value={node.title ?? ""}
                     onChange={v => onPatch({ title: v })} required />
          <FormField label="Icon (emoji)" value={node.icon ?? ""}
                     onChange={v => onPatch({ icon: v })} />
        </>
      )}

      {node.kind === "OBJECT" && (
        <>
          <label className="form-field">
            <span className="form-field__label">Database object *</span>
            <select className="form-field__input"
                    value={node.typeId ?? ""}
                    onChange={e => onPatch({ typeId: e.target.value ? Number(e.target.value) : null })}>
              <option value="">— choose a type —</option>
              {objectTypes.map(t => (
                <option key={t.typeId} value={t.typeId}>{t.pluralLabel}</option>
              ))}
            </select>
          </label>
          <FormField label="Title (override)" value={node.title ?? ""}
                     onChange={v => onPatch({ title: v })}
                     description="Empty - the type name is taken from the metadata" />
          <FormField label="Icon (override)" value={node.icon ?? ""}
                     onChange={v => onPatch({ icon: v })} />
        </>
      )}

      {node.kind === "TOOL" && (
        <>
          <label className="form-field">
            <span className="form-field__label">Tool *</span>
            <select className="form-field__input"
                    value={node.tool ?? ""}
                    onChange={e => onPatch({ tool: e.target.value })}>
              <option value="">— choose a tool —</option>
              {tools.map(t => (
                <option key={t.key} value={t.key}>{t.label}</option>
              ))}
            </select>
          </label>
          <FormField label="Title (override)" value={node.title ?? ""}
                     onChange={v => onPatch({ title: v })} />
          <FormField label="Icon (override)" value={node.icon ?? ""}
                     onChange={v => onPatch({ icon: v })} />
        </>
      )}
    </div>
  );
}

function labelFor(
  n: LayoutNode,
  objectTypes: { typeId: number; pluralLabel: string; iconHint: string }[],
  tools: ToolDescriptor[],
): string {
  if (n.kind === "GROUP") return `${n.icon ?? "📁"} ${n.title ?? "Group"}`;
  if (n.kind === "OBJECT") {
    const td = objectTypes.find(t => t.typeId === n.typeId);
    return `${n.icon ?? td?.iconHint ?? "📄"} ${n.title ?? td?.pluralLabel ?? `type ${n.typeId ?? "?"}`}`;
  }
  const tool = tools.find(t => t.key === n.tool);
  return `${n.icon ?? tool?.icon ?? "🧰"} ${n.title ?? tool?.label ?? n.tool ?? "tool"}`;
}

// Immutable tree helpers (path = масив дочірніх індексів від кореня)

type Path = number[];

function clone(t: LayoutNode[]): LayoutNode[] {
  return JSON.parse(JSON.stringify(t)) as LayoutNode[];
}

/** Нормалізує дерево: гарантує масив children у кожному вузлі. */
function normalize(nodes: LayoutNode[]): LayoutNode[] {
  return nodes.map(n => ({ ...n, children: normalize(n.children ?? []) }));
}

function nodeAt(tree: LayoutNode[], path: Path): LayoutNode | null {
  let nodes = tree;
  let node: LayoutNode | null = null;
  for (const idx of path) {
    node = nodes[idx] ?? null;
    if (!node) return null;
    nodes = node.children ?? [];
  }
  return node;
}

/** Масив-контейнер, у якому лежить вузол за path (children батька або корінь). */
function siblingArray(tree: LayoutNode[], path: Path): LayoutNode[] {
  let nodes = tree;
  for (let i = 0; i < path.length - 1; i++) {
    const n = nodes[path[i]];
    n.children = n.children ?? [];
    nodes = n.children;
  }
  return nodes;
}

function samePath(a: Path, b: Path): boolean {
  return a.length === b.length && a.every((v, i) => v === b[i]);
}

const builderStyle: CSSProperties = {
  display: "flex", gap: 12, alignItems: "stretch",
};
const treePanelStyle: CSSProperties = {
  flex: "1 1 55%", border: "1px solid var(--border, #e3e6ea)", borderRadius: 8, padding: 8, minWidth: 0,
};
const propPanelStyle: CSSProperties = {
  flex: "1 1 45%", border: "1px solid var(--border, #e3e6ea)", borderRadius: 8, padding: 8, minWidth: 0,
};
const rowStyle: CSSProperties = {
  padding: "5px 6px", borderRadius: 6, cursor: "pointer",
  whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis", fontSize: 14,
};
