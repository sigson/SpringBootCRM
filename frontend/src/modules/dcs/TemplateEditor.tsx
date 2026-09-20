import { useState } from "react";
import type { AvailableField, ReportTemplate, TemplateCell } from "./types";
import {
  Banner, Btn, Check, Empty, Field, Grid, MasterList, Panel, Select, Spacer,
  TextInput, Toolbar, clone, move, newId,
} from "./ui";

/**
 * <h2>Вкладка «Макеты» — оформление областей вывода.</h2>
 *
 * <p>Макет в СКД переопределяет автоматический вывод: вместо «поле → колонка» область
 * рисуется вручную ячейками, а значения подставляются в них по местам. Здесь это
 * прямоугольная сетка, где текст ячейки может содержать {@code [ИмяПоля]} — при выводе
 * плейсхолдер заменяется значением поля или ресурса.
 *
 * <p>Редактор намеренно не пытается быть Excel'ем: у макета отчёта задача узкая —
 * зафиксировать шапку, подписи и порядок значений, а не считать формулы. Полноценная
 * электронная таблица здесь дала бы функциональность, которой негде примениться.
 */
export function TemplatesTab({
  templates, onChange, fields,
}: {
  templates: ReportTemplate[];
  onChange: (t: ReportTemplate[]) => void;
  fields: AvailableField[];
}) {
  const [selected, setSelected] = useState(0);
  const [cell, setCell] = useState<{ row: number; col: number } | null>(null);
  const current = templates[selected] ?? null;

  function patch(p: Partial<ReportTemplate>) {
    if (!current) return;
    const next = clone(templates);
    next[selected] = { ...next[selected], ...p };
    onChange(next);
  }

  function patchCell(row: number, col: number, p: Partial<TemplateCell>) {
    if (!current) return;
    const rows = clone(current.rows);
    rows[row].cells[col] = { ...rows[row].cells[col], ...p };
    patch({ rows });
  }

  const selectedCell = current && cell ? current.rows[cell.row]?.cells[cell.col] ?? null : null;
  const width = current ? Math.max(1, ...current.rows.map((r) => r.cells.length)) : 0;

  return (
    <div className="dcs-split" style={{ gridTemplateColumns: "230px minmax(0, 1fr) 230px" }}>
      <Panel title="Templates">
        <div style={{ margin: -8 }}>
          <MasterList
            items={templates}
            selected={selected}
            onSelect={(i) => { setSelected(i); setCell(null); }}
            addLabel="Template"
            onAdd={() => {
              onChange([...templates, blankTemplate(`Template ${templates.length + 1}`)]);
              setSelected(templates.length);
            }}
            onRemove={(i) => { onChange(templates.filter((_, idx) => idx !== i)); setSelected(Math.max(0, i - 1)); }}
            onMove={(from, to) => { onChange(move(templates, from, to)); setSelected(to); }}
            emptyText="No templates — the output is built automatically."
            label={(t) => (
              <>
                <span>{t.name}</span>
                <Spacer />
                <span className="dcs-muted" style={{ fontSize: 10 }}>{AREA_LABELS[t.area]}</span>
              </>
            )}
          />
        </div>
      </Panel>

      <Panel title={current ? `Template: ${current.name}` : "Template"} scroll>
        {!current ? <Empty>Select a template or add a new one.</Empty> : (
          <>
            <Grid cols={3}>
              <Field label="Name">
                <TextInput value={current.name} onChange={(v) => patch({ name: v })} />
              </Field>
              <Field label="Output area">
                <Select value={current.area}
                        onChange={(v) => patch({ area: v as ReportTemplate["area"] })}
                        options={Object.entries(AREA_LABELS).map(([value, label]) => ({ value, label }))} />
              </Field>
              {current.area === "grouping" && (
                <Field label="Grouping field">
                  <Select value={current.field ?? ""} empty="— any —"
                          onChange={(v) => patch({ field: v || null })}
                          options={fields.filter((f) => f.usableInGroup)
                                         .map((f) => ({ value: f.id, label: f.title }))} />
                </Field>
              )}
            </Grid>

            <Banner kind="info">
              Cell text may contain field placeholders: <span className="dcs-mono">[Sales.amount]</span>.
              On output the placeholder is replaced with the value of that field or resource.
            </Banner>

            <Toolbar>
              <Btn small onClick={() => patch({ rows: [...current.rows, { cells: blankCells(width) }] })}>
                ＋ Row
              </Btn>
              <Btn small onClick={() => patch({
                rows: current.rows.map((r) => ({ cells: [...r.cells, blankCell()] })),
              })}>
                ＋ Column
              </Btn>
              <Spacer />
              <Btn small kind="danger" disabled={current.rows.length <= 1}
                   onClick={() => patch({ rows: current.rows.slice(0, -1) })}>
                − Row
              </Btn>
              <Btn small kind="danger" disabled={width <= 1}
                   onClick={() => patch({ rows: current.rows.map((r) => ({ cells: r.cells.slice(0, -1) })) })}>
                − Column
              </Btn>
            </Toolbar>

            <table className="dcs-table" style={{ tableLayout: "fixed" }}>
              <tbody>
                {current.rows.map((row, ri) => (
                  <tr key={ri}>
                    {row.cells.map((c, ci) => (
                      <td
                        key={ci}
                        className={`dcs-template-cell${cell && cell.row === ri && cell.col === ci ? " is-selected" : ""}`}
                        style={{
                          fontWeight: c.bold ? 700 : undefined,
                          fontStyle: c.italic ? "italic" : undefined,
                          textAlign: c.align ?? "left",
                          color: c.textColor ?? undefined,
                          background: c.backColor ?? undefined,
                        }}
                        onClick={() => setCell({ row: ri, col: ci })}
                      >
                        <input
                          value={c.text}
                          onChange={(e) => patchCell(ri, ci, { text: e.target.value })}
                          onFocus={() => setCell({ row: ri, col: ci })}
                          style={{ textAlign: c.align ?? "left" }}
                        />
                      </td>
                    ))}
                  </tr>
                ))}
              </tbody>
            </table>
          </>
        )}
      </Panel>

      <Panel title="Cell" scroll>
        {!selectedCell || !cell ? <Empty>Select a cell.</Empty> : (
          <>
            <Field label="Text">
              <TextInput value={selectedCell.text}
                         onChange={(v) => patchCell(cell.row, cell.col, { text: v })} />
            </Field>
            <Field label="Insert a field">
              <Select
                value=""
                empty="— field —"
                onChange={(v) => {
                  if (v) patchCell(cell.row, cell.col, { text: `${selectedCell.text}[${v}]` });
                }}
                options={fields.map((f) => ({ value: f.id, label: f.title }))}
              />
            </Field>
            <Field label="Alignment">
              <Select value={selectedCell.align ?? "left"}
                      onChange={(v) => patchCell(cell.row, cell.col, { align: v as TemplateCell["align"] })}
                      options={[
                        { value: "left", label: "Left" },
                        { value: "center", label: "Centre" },
                        { value: "right", label: "Right" },
                      ]} />
            </Field>
            <Field label="Text colour">
              <TextInput value={selectedCell.textColor ?? ""} placeholder="#1a1f2b"
                         onChange={(v) => patchCell(cell.row, cell.col, { textColor: v || null })} />
            </Field>
            <Field label="Background">
              <TextInput value={selectedCell.backColor ?? ""} placeholder="#f4f6fa"
                         onChange={(v) => patchCell(cell.row, cell.col, { backColor: v || null })} />
            </Field>
            <Toolbar>
              <Check label="Bold" value={!!selectedCell.bold}
                     onChange={(v) => patchCell(cell.row, cell.col, { bold: v })} />
              <Check label="Italic" value={!!selectedCell.italic}
                     onChange={(v) => patchCell(cell.row, cell.col, { italic: v })} />
            </Toolbar>
          </>
        )}
      </Panel>
    </div>
  );
}

function blankCell(): TemplateCell {
  return { text: "", bold: false, italic: false, align: "left", textColor: null, backColor: null, width: null };
}

function blankCells(n: number): TemplateCell[] {
  return Array.from({ length: Math.max(1, n) }, blankCell);
}

function blankTemplate(name: string): ReportTemplate {
  return {
    id: newId("tpl"),
    name,
    area: "detail",
    field: null,
    rows: [{ cells: blankCells(3) }, { cells: blankCells(3) }],
  };
}

const AREA_LABELS: Record<ReportTemplate["area"], string> = {
  reportHeader: "Report header",
  header: "Column header",
  grouping: "Grouping",
  detail: "Detail records",
  total: "Total",
  footer: "Footer",
};
