import type { AvailableField, CompareOp, FilterGroup, FilterNode, SchemaParameter } from "./types";
import { Btn, Check, Empty, Select, Spacer, TextInput, Toolbar } from "./ui";

/**
 * <h2>Построитель отбора — дерево групп И/ИЛИ/НЕ с элементами сравнения.</h2>
 *
 * <p>Виды сравнения повторяют набор 1С, включая «Заполнено»/«Не заполнено» и «В списке».
 * Правая часть умеет ссылаться на параметр схемы: значение, введённое как
 * {@code &Параметр}, движок подставит перед выполнением, а не сравнит со строкой, —
 * так один и тот же отбор работает с разными периодами без правки отчёта.
 */
export function FilterBuilder({
  group, onChange, fields, parameters, emptyText,
}: {
  group: FilterGroup | null | undefined;
  onChange: (g: FilterGroup | null) => void;
  fields: AvailableField[];
  parameters?: SchemaParameter[];
  emptyText?: string;
}) {
  const value: FilterGroup = group ?? { combinator: "and", items: [] };
  const filterable = fields.filter((f) => f.usableInFilter);

  function update(next: FilterGroup) {
    // Пустой отбор хранится как отсутствующий: так в JSON отчёта не копятся
    // пустые узлы, а компоновщик не тратит проход на заведомо истинное дерево.
    onChange(next.items.length === 0 ? null : next);
  }

  return (
    <div>
      <GroupEditor
        node={{ combinator: value.combinator, items: value.items }}
        fields={filterable}
        parameters={parameters ?? []}
        depth={0}
        onChange={(n) => update({ combinator: (n.combinator ?? "and") as FilterGroup["combinator"],
                                  items: n.items ?? [] })}
        onRemove={() => onChange(null)}
        root
      />
      {value.items.length === 0 && <Empty>{emptyText ?? "No filter — every record is included."}</Empty>}
    </div>
  );
}

function GroupEditor({
  node, onChange, onRemove, fields, parameters, depth, root,
}: {
  node: FilterNode;
  onChange: (n: FilterNode) => void;
  onRemove: () => void;
  fields: AvailableField[];
  parameters: SchemaParameter[];
  depth: number;
  root?: boolean;
}) {
  const items = node.items ?? [];

  function patchItem(i: number, n: FilterNode) {
    const next = items.slice();
    next[i] = n;
    onChange({ ...node, items: next });
  }

  function removeItem(i: number) {
    onChange({ ...node, items: items.filter((_, idx) => idx !== i) });
  }

  return (
    <div className={depth > 0 ? "dcs-filter-group" : undefined}>
      <Toolbar>
        <Select
          value={node.combinator ?? "and"}
          onChange={(v) => onChange({ ...node, combinator: v })}
          options={[
            { value: "and", label: "AND — all conditions" },
            { value: "or", label: "OR — any condition" },
            { value: "not", label: "NOT — negation" },
          ]}
        />
        <Btn small onClick={() => onChange({
          ...node,
          items: [...items, { left: fields[0]?.id ?? "", op: "eq", right: "" }],
        })}>
          ＋ Condition
        </Btn>
        <Btn small onClick={() => onChange({
          ...node,
          items: [...items, { combinator: "and", items: [] }],
        })}>
          ＋ Group
        </Btn>
        <Spacer />
        {!root && <Btn small kind="danger" onClick={onRemove}>🗑</Btn>}
      </Toolbar>

      {items.map((item, i) =>
        item.combinator ? (
          <GroupEditor
            key={i}
            node={item}
            depth={depth + 1}
            fields={fields}
            parameters={parameters}
            onChange={(n) => patchItem(i, n)}
            onRemove={() => removeItem(i)}
          />
        ) : (
          <ItemEditor
            key={i}
            node={item}
            fields={fields}
            parameters={parameters}
            onChange={(n) => patchItem(i, n)}
            onRemove={() => removeItem(i)}
          />
        ),
      )}
    </div>
  );
}

function ItemEditor({
  node, onChange, onRemove, fields, parameters,
}: {
  node: FilterNode;
  onChange: (n: FilterNode) => void;
  onRemove: () => void;
  fields: AvailableField[];
  parameters: SchemaParameter[];
}) {
  const needsValue = !["filled", "notFilled"].includes(node.op ?? "eq");
  const isList = ["in", "notIn"].includes(node.op ?? "eq");
  const isRange = (node.op ?? "eq") === "between";

  return (
    <div className="dcs-filter-row" style={{ opacity: node.disabled ? 0.5 : 1 }}>
      <Select
        value={node.left ?? ""}
        onChange={(v) => onChange({ ...node, left: v })}
        options={fields.map((f) => ({ value: f.id, label: f.title }))}
        empty="— field —"
      />
      <Select
        value={node.op ?? "eq"}
        onChange={(v) => onChange({ ...node, op: v as CompareOp, right: resetValue(v as CompareOp, node.right) })}
        options={OPS}
      />
      {needsValue ? (
        <ValueInput
          op={(node.op ?? "eq") as CompareOp}
          value={node.right}
          parameters={parameters}
          onChange={(v) => onChange({ ...node, right: v })}
          list={isList}
          range={isRange}
        />
      ) : (
        <span className="dcs-muted" style={{ fontSize: 11 }}>no value</span>
      )}
      <div style={{ display: "flex", gap: 4, alignItems: "center" }}>
        <Check label="" value={!node.disabled} title="Enabled"
               onChange={(v) => onChange({ ...node, disabled: !v })} />
        <Btn small kind="danger" onClick={onRemove}>✕</Btn>
      </div>
    </div>
  );
}

function ValueInput({
  op, value, onChange, parameters, list, range,
}: {
  op: CompareOp;
  value: unknown;
  onChange: (v: unknown) => void;
  parameters: SchemaParameter[];
  list: boolean;
  range: boolean;
}) {
  const asText = (v: unknown) => (Array.isArray(v) ? v.join(", ") : v == null ? "" : String(v));

  if (range) {
    const bounds = Array.isArray(value) ? value : ["", ""];
    return (
      <div style={{ display: "flex", gap: 4 }}>
        <TextInput value={String(bounds[0] ?? "")}
                   onChange={(v) => onChange([v, bounds[1] ?? ""])} placeholder="from" />
        <TextInput value={String(bounds[1] ?? "")}
                   onChange={(v) => onChange([bounds[0] ?? "", v])} placeholder="to" />
      </div>
    );
  }

  return (
    <div style={{ display: "flex", gap: 4, minWidth: 0 }}>
      <TextInput
        value={asText(value)}
        placeholder={list ? "value1, value2, …" : "value or &Parameter"}
        onChange={(v) => onChange(list ? v.split(",").map((s) => s.trim()).filter(Boolean) : v)}
      />
      {parameters.length > 0 && (
        <select
          className="dcs-input"
          style={{ maxWidth: 40 }}
          value=""
          title="Insert a schema parameter"
          onChange={(e) => { if (e.target.value) onChange(e.target.value); }}
        >
          <option value="">&amp;</option>
          {parameters.map((p) => (
            <option key={p.name} value={`&${p.name}`}>{p.title || p.name}</option>
          ))}
        </select>
      )}
    </div>
  );
}

/** Смена вида сравнения меняет и форму значения — старое чаще мешает, чем помогает. */
function resetValue(op: CompareOp, current: unknown): unknown {
  if (op === "between") return Array.isArray(current) && current.length === 2 ? current : ["", ""];
  if (op === "in" || op === "notIn") return Array.isArray(current) ? current : [];
  if (op === "filled" || op === "notFilled") return null;
  return Array.isArray(current) ? (current[0] ?? "") : current;
}

const OPS: { value: CompareOp; label: string }[] = [
  { value: "eq", label: "equals" },
  { value: "ne", label: "not equal" },
  { value: "gt", label: "greater" },
  { value: "ge", label: "greater or equal" },
  { value: "lt", label: "less" },
  { value: "le", label: "less or equal" },
  { value: "between", label: "between" },
  { value: "in", label: "in list" },
  { value: "notIn", label: "not in list" },
  { value: "contains", label: "contains" },
  { value: "notContains", label: "does not contain" },
  { value: "beginsWith", label: "begins with" },
  { value: "filled", label: "filled" },
  { value: "notFilled", label: "not filled" },
];
