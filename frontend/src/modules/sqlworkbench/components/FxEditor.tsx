import { useRef, useState } from "react";
import { Modal } from "./builderUi";
import {
  SourcesRequisiteForest, type ForestSource, type SelectedFieldRef, type RequisitePick,
} from "./ReferenceModeUi";
import { addRequisite, resolveExpression, type ReferenceGraph } from "../querymodel/referenceModel";
import { newQuery } from "../querymodel/builderModel";

// Шаблоны функций (1С-style): строки, даты, число, агрегаты, условия.
// Маркер ⎵ обозначает место установки каретки после вставки.

interface FnTemplate { label: string; snippet: string; hint?: string; }
interface FnGroup { title: string; items: FnTemplate[]; }

const FN_GROUPS: FnGroup[] = [
  {
    title: "Rows",
    items: [
      { label: "SUBSTRING (SUBSTRING)", snippet: "SUBSTRING(⎵, 1, 10)", hint: "part of the string" },
      { label: "LENGTH (LENGTH)", snippet: "LENGTH(⎵)" },
      { label: "UPPER (UPPER)", snippet: "UPPER(⎵)" },
      { label: "LOWER (LOWER)", snippet: "LOWER(⎵)" },
      { label: "TRIM (TRIM)", snippet: "TRIM(⎵)" },
      { label: "Union (||)", snippet: "⎵ || ' ' || " },
      { label: "REPLACE (REPLACE)", snippet: "REPLACE(⎵, 'a', 'b')" },
      { label: "POSITION (POSITION)", snippet: "POSITION('x' IN ⎵)" },
    ],
  },
  {
    title: "Dates",
    items: [
      { label: "CURRENTDATE (CURRENT_DATE)", snippet: "CURRENT_DATE⎵" },
      { label: "NOW (CURRENT_TIMESTAMP)", snippet: "CURRENT_TIMESTAMP⎵" },
      { label: "YEAR (EXTRACT YEAR)", snippet: "EXTRACT(YEAR FROM ⎵)" },
      { label: "MONTH (EXTRACT MONTH)", snippet: "EXTRACT(MONTH FROM ⎵)" },
      { label: "DAY (EXTRACT DAY)", snippet: "EXTRACT(DAY FROM ⎵)" },
      { label: "DATEDIFF (-)", snippet: "(⎵ - CURRENT_DATE)" },
    ],
  },
  {
    title: "Number",
    items: [
      { label: "ROUND (ROUND)", snippet: "ROUND(⎵, 2)" },
      { label: "ABS", snippet: "ABS(⎵)" },
      { label: "INT (FLOOR)", snippet: "FLOOR(⎵)" },
      { label: "CEIL (CEIL)", snippet: "CEIL(⎵)" },
      { label: "Modulo (MOD)", snippet: "MOD(⎵, 2)" },
    ],
  },
  {
    title: "Condition / NULL",
    items: [
      { label: "SELECT (CASE)", snippet: "CASE WHEN ⎵ THEN 1 ELSE 0 END" },
      { label: "COALESCE", snippet: "COALESCE(⎵, '—')" },
      { label: "NULLIF", snippet: "NULLIF(⎵, 0)" },
      { label: "Is NULL", snippet: "⎵ IS NULL" },
      { label: "NOT NULL", snippet: "⎵ IS NOT NULL" },
    ],
  },
  {
    title: "Aggregates",
    items: [
      { label: "SUM (SUM)", snippet: "SUM(⎵)" },
      { label: "COUNT (COUNT)", snippet: "COUNT(⎵)" },
      { label: "AVG (AVG)", snippet: "AVG(⎵)" },
      { label: "MAX (MAX)", snippet: "MAX(⎵)" },
      { label: "MIN (MIN)", snippet: "MIN(⎵)" },
    ],
  },
];

interface FxEditorProps {
  title: string;
  initialText: string;
  graph: ReferenceGraph | null;
  sources: ForestSource[];
  selectedFields: SelectedFieldRef[];
  onApply: (text: string) => void;
  onClose: () => void;
}

/**
 * Полноценный редактор произвольного SQL (fx):
 *   • поле ввода предзаполнено сгенерированным SQL для текущего поля/условия;
 *   • ЛЕВО — шаблоны функций (1С-style), клик вставляет шаблон в позицию каретки;
 *   • ПРАВО — лес источников + «Все реквизиты» + «Поля запиту»; выбор реквизита
 *     вставляет в позицию каретки тот SQL, который конструктор сгенерировал бы
 *     для этого реквизита (для ссылок — пару колонок).
 */
export function FxEditor({
  title, initialText, graph, sources, selectedFields, onApply, onClose,
}: FxEditorProps) {
  const [text, setText] = useState(initialText);
  const taRef = useRef<HTMLTextAreaElement | null>(null);

  /** Вставить строку в позицию каретки; ⎵ — куда поставить каретку после. */
  const insertAtCaret = (raw: string) => {
    const ta = taRef.current;
    const caretMarker = raw.indexOf("⎵");
    const snippet = raw.replace("⎵", "");
    if (!ta) { setText((t) => t + snippet); return; }
    const start = ta.selectionStart ?? text.length;
    const end = ta.selectionEnd ?? text.length;
    const next = text.slice(0, start) + snippet + text.slice(end);
    setText(next);
    // позиция каретки: либо на месте ⎵ внутри сниппета, либо в конце вставки
    const caretPos = start + (caretMarker >= 0 ? caretMarker : snippet.length);
    requestAnimationFrame(() => {
      ta.focus();
      ta.setSelectionRange(caretPos, caretPos);
    });
  };

  // Сгенерировать SQL-выражение для выбранного реквизита (как сделал бы
  // конструктор), и вставить в каретку. Используем пустой Query как «арену»:
  // resolveExpression вернёт выражение пути; для ссылки — пара колонок.
  const insertRequisite = (pick: RequisitePick) => {
    const arena = newQuery();
    if (pick.leaf.kind === "reference") {
      // ссылка: генерируем те же две колонки, что и в SELECT (через addRequisite)
      const q = addRequisite(arena, pick.path, pick.leaf, pick.label, graph ?? undefined);
      const f = q.fields[q.fields.length - 1];
      if (f?.ref) {
        const a = f.alias?.trim() || "ref";
        const typeExpr = f.ref.constTypeId != null ? String(f.ref.constTypeId) : (f.ref.typeIdExpr ?? "NULL");
        insertAtCaret(`${typeExpr} AS ${a}_type_id, ${f.ref.idExpr} AS ${a}_id⎵`);
        return;
      }
    }
    const { expression } = resolveExpression(arena, pick.path, pick.leaf, graph ?? undefined);
    insertAtCaret(expression + "⎵");
  };

  return (
    <Modal title={title} width="full" onClose={onClose}
           footer={<><button className="btn" onClick={onClose}>Cancel</button>
                     <button className="btn btn--primary" onClick={() => onApply(text)}>Apply</button></>}>
      <div style={{ display: "flex", gap: 8, minHeight: "56vh" }}>
        {}
        <div style={{ width: 230, overflow: "auto", borderRight: "1px solid var(--border,#ddd)", paddingRight: 6 }}>
          <div className="rdr-pane__head">Functions</div>
          {FN_GROUPS.map((g) => (
            <details key={g.title} open>
              <summary className="hint" style={{ cursor: "pointer", fontWeight: 600, padding: "4px 0" }}>{g.title}</summary>
              {g.items.map((it) => (
                <button key={it.label} type="button" className="rdr-pick"
                        style={{ textAlign: "left", display: "block", width: "100%" }}
                        title={it.hint ?? it.snippet}
                        onClick={() => insertAtCaret(it.snippet)}>
                  {it.label}
                </button>
              ))}
            </details>
          ))}
        </div>

        {}
        <div style={{ flex: 1, display: "flex", flexDirection: "column" }}>
          <div className="rdr-pane__head">SQL-expression</div>
          <p className="hint" style={{ marginTop: 0 }}>
            Clicking a function on the left or an attribute on the right inserts code at the caret.
          </p>
          <textarea ref={taRef} className="rdr-sql-editor" style={{ flex: 1, minHeight: 280, width: "100%" }}
                    value={text} spellCheck={false} onChange={(e) => setText(e.target.value)} />
        </div>

        {}
        <div style={{ width: 320, overflow: "auto", borderLeft: "1px solid var(--border,#ddd)", paddingLeft: 6 }}>
          <div className="rdr-pane__head">Attributes</div>
          {graph ? (
            <SourcesRequisiteForest
              graph={graph} sources={sources} selectedFields={selectedFields}
              onPick={insertRequisite}
              onPickSelected={(f) => insertAtCaret(f.expression + "⎵")} />
          ) : (
            <div className="off">graph unavailable (raw mode)</div>
          )}
        </div>
      </div>
    </Modal>
  );
}
