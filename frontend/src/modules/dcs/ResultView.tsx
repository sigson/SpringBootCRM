import { useMemo, useState } from "react";
import type {
  Appearance, CompositionResult, ReportTemplate, ResultColumnHeader, ResultNode,
} from "./types";
import { Banner, Btn, Empty, Panel, Spacer, Toolbar } from "./ui";

/**
 * <h2>Вывод результата компоновки.</h2>
 *
 * <p>Результат — дерево, а не таблица, поэтому и рендерится деревом: группировки
 * сворачиваются, итоги остаются на своих уровнях, отступ показывает вложенность.
 * Плоский грид здесь потребовал бы либо дублировать значения группировок в каждой
 * строке, либо потерять итоги промежуточных уровней.
 *
 * <p>Клик по ячейке — расшифровка: наверх уходят значения группировок этой строки
 * ({@code details}), а движок пересобирает отчёт с дополнительным отбором. Поэтому
 * расшифровка работает и тогда, когда СУБД агрегировала данные и детальных записей в
 * первом результате не было вовсе.
 *
 * <p>Кросс-таблица рендерится отдельной веткой: у неё дерево заголовков колонок, а
 * ячейки адресуются ключом «колонка|ресурс», который сформировал процессор.
 *
 * <p>Макеты областей «Шапка отчёта» и «Подвал» выводятся полосами над и под таблицей с
 * подстановкой значений вместо плейсхолдеров {@code [Поле]}. Макеты областей
 * «Группировка» и «Детальные записи» пока хранятся и редактируются, но на вывод не
 * влияют: строки рисует автоматический вывод.
 */
export function ResultView({
  result, onDrilldown, onExport, busy, templates,
}: {
  result: CompositionResult | null;
  onDrilldown?: (details: Record<string, unknown>, action: "detail" | "groupBy", field?: string) => void;
  onExport?: (format: "xlsx" | "csv") => void;
  busy?: boolean;
  templates?: ReportTemplate[];
}) {
  const [collapsed, setCollapsed] = useState<Set<string>>(new Set());
  const [showSql, setShowSql] = useState(false);

  const toggle = (id: string) => {
    const next = new Set(collapsed);
    if (next.has(id)) next.delete(id); else next.add(id);
    setCollapsed(next);
  };

  if (busy) return <Empty>Composing…</Empty>;
  if (!result) return <Empty>Press «Compose» to build the report.</Empty>;

  const hasTable = result.rows.some((r) => r.kind === "table");

  return (
    <div style={{ display: "flex", flexDirection: "column", minHeight: 0, gap: 6 }}>
      <Toolbar>
        {result.title && <strong>{result.title}</strong>}
        <span className="dcs-muted">
          {result.sourceRowCount} source record(s) · {result.elapsedMs} ms
          {result.aggregatedInSql ? " · aggregated in SQL" : " · totals computed by the processor"}
        </span>
        <Spacer />
        <Btn small onClick={() => setCollapsed(new Set())}>Expand all</Btn>
        <Btn small onClick={() => setCollapsed(collectIds(result.rows))}>Collapse all</Btn>
        {onExport && <Btn small onClick={() => onExport("xlsx")}>XLSX</Btn>}
        {onExport && <Btn small onClick={() => onExport("csv")}>CSV</Btn>}
        <Btn small kind="ghost" onClick={() => setShowSql(!showSql)}>SQL</Btn>
      </Toolbar>

      {result.truncated && (
        <Banner kind="warn">
          The selection hit its limit — the totals are computed over incomplete data.
        </Banner>
      )}
      {result.warnings.map((w, i) => <Banner key={i} kind="warn">{w}</Banner>)}

      {result.parameters.length > 0 && (
        <div className="dcs-chips">
          {result.parameters.map((p) => (
            <span key={p.name} className="dcs-chip" style={{ paddingRight: 8 }}>
              {p.title}: <strong>{p.presentation || "—"}</strong>
            </span>
          ))}
        </div>
      )}

      {showSql && <pre className="dcs-sql">{result.sql}</pre>}

      <TemplateBand templates={templates} area="reportHeader"
                    node={result.rows[0]} result={result} />

      <div style={{ overflow: "auto", flex: 1, minHeight: 200 }}>
        {hasTable
          ? result.rows.map((r) => (
              r.kind === "table"
                ? <CrossTable key={r.id} node={r} collapsed={collapsed} toggle={toggle}
                              onDrilldown={onDrilldown} />
                : <FlatTable key={r.id} result={result} rows={[r]} collapsed={collapsed}
                             toggle={toggle} onDrilldown={onDrilldown} />
            ))
          : <FlatTable result={result} rows={result.rows} collapsed={collapsed}
                       toggle={toggle} onDrilldown={onDrilldown} />}
      </div>

      <TemplateBand templates={templates} area="footer"
                    node={result.rows[0]} result={result} />
    </div>
  );
}

/**
 * Полоса макета: строки и ячейки макета с подставленными значениями.
 *
 * <p>Плейсхолдер {@code [Поле]} заменяется значением из ячеек узла (для шапки и подвала —
 * из общего итога). Неизвестное имя поля остаётся в тексте как есть: молча стирать его
 * значило бы прятать опечатку в макете.
 */
function TemplateBand({
  templates, area, node, result,
}: {
  templates?: ReportTemplate[];
  area: ReportTemplate["area"];
  node: ResultNode | undefined;
  result: CompositionResult;
}) {
  const band = (templates ?? []).filter((t) => t.area === area);
  if (band.length === 0 || band.every((t) => t.rows.length === 0)) return null;

  const values: Record<string, unknown> = { ...(node?.cells ?? {}), ...(node?.details ?? {}) };
  // Колонки результата — это поля, которые отчёт действительно выводит. Плейсхолдер
  // такого поля без значения (его нет в этой компоновке — например, ресурс в
  // расшифровке) подставляется пустым; в тексте остаются только опечатки.
  const known = new Set(result.columns.map((c) => c.id));

  return (
    <div style={{ margin: "4px 0" }}>
      {band.map((t) => (
        <table key={t.id} className="dcs-table" style={{ tableLayout: "fixed" }}>
          <tbody>
            {t.rows.map((row, ri) => (
              <tr key={ri}>
                {row.cells.map((cell, ci) => (
                  <td
                    key={ci}
                    style={{
                      fontWeight: cell.bold ? 700 : undefined,
                      fontStyle: cell.italic ? "italic" : undefined,
                      textAlign: cell.align ?? "left",
                      color: cell.textColor ?? undefined,
                      background: cell.backColor ?? undefined,
                    }}
                  >
                    {substitute(cell.text, values, known)}
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      ))}
    </div>
  );
}

/**
 * Подстановка {@code [Поле]}. Известное поле без значения даёт пустоту, неизвестное
 * имя остаётся в тексте — опечатку в макете лучше увидеть, чем молча потерять.
 */
function substitute(text: string, values: Record<string, unknown>, known: Set<string>): string {
  return (text ?? "").replace(/\[([^\]]+)\]/g, (whole, id: string) => {
    const key = id.trim();
    if (key in values) return format(values[key], undefined);
    return known.has(key) ? "" : whole;
  });
}

function FlatTable({
  result, rows, collapsed, toggle, onDrilldown,
}: {
  result: CompositionResult;
  rows: ResultNode[];
  collapsed: Set<string>;
  toggle: (id: string) => void;
  onDrilldown?: (d: Record<string, unknown>, a: "detail" | "groupBy", f?: string) => void;
}) {
  const flat = useMemo(() => flatten(rows, collapsed), [rows, collapsed]);

  return (
    <table className="dcs-table">
      <thead>
        <tr>
          <th style={{ minWidth: 220 }}>Grouping</th>
          {result.columns.map((c) => (
            <th key={c.id} style={{ textAlign: c.align === "right" ? "right" : "left" }}>{c.title}</th>
          ))}
        </tr>
      </thead>
      <tbody>
        {flat.length === 0 && (
          <tr><td colSpan={result.columns.length + 1}><Empty>No data</Empty></td></tr>
        )}
        {flat.map((n) => (
          <tr key={n.id}
              className={n.kind === "grandTotal" ? "dcs-result-row--total"
                       : n.kind === "group" ? "dcs-result-row--group" : undefined}
              style={styleOf(n.appearance)}>
            <td style={{ paddingLeft: 6 + n.level * 16 }}>
              {(n.children?.length ?? 0) > 0 && (
                <button type="button" className="dcs-result-toggle" onClick={() => toggle(n.id)}>
                  {collapsed.has(n.id) ? "▸" : "▾"}
                </button>
              )}
              <span
                className={onDrilldown && n.details ? "dcs-result-cell dcs-result-cell--drill" : "dcs-result-cell"}
                title={onDrilldown ? "Drill down" : undefined}
                onClick={() => onDrilldown && n.details && onDrilldown(n.details, "detail")}
              >
                {n.display ?? ""}
              </span>
              {n.rowCount > 1 && <span className="dcs-muted" style={{ marginLeft: 6, fontSize: 10 }}>
                ({n.rowCount})
              </span>}
            </td>
            {result.columns.map((c) => (
              <td
                key={c.id}
                className={c.kind === "resource" || c.valueType === "number" ? "is-number" : undefined}
                style={styleOf(n.cellAppearance?.[c.id])}
              >
                {format(n.cells[c.id], n.cellAppearance?.[c.id])}
              </td>
            ))}
          </tr>
        ))}
      </tbody>
    </table>
  );
}

function CrossTable({
  node, collapsed, toggle, onDrilldown,
}: {
  node: ResultNode;
  collapsed: Set<string>;
  toggle: (id: string) => void;
  onDrilldown?: (d: Record<string, unknown>, a: "detail" | "groupBy", f?: string) => void;
}) {
  const leaves = useMemo(() => collectLeaves(node.columnHeaders ?? []), [node.columnHeaders]);
  const resources = useMemo(() => resourceKeys(node), [node]);
  const rows = useMemo(() => flatten([node], collapsed), [node, collapsed]);

  if (leaves.length === 0) return <Empty>The cross-tab has no column groupings.</Empty>;

  return (
    <table className="dcs-table">
      <thead>
        <tr>
          <th rowSpan={2} style={{ minWidth: 200 }}>{node.display ?? "Cross-tab"}</th>
          {leaves.map((l) => (
            <th key={l.key} colSpan={resources.length} style={{ textAlign: "center" }}>{l.display}</th>
          ))}
          <th colSpan={resources.length} style={{ textAlign: "center" }}>Total</th>
        </tr>
        <tr>
          {[...leaves.map((l) => l.key), ""].flatMap((key) =>
            resources.map((r) => (
              <th key={`${key}|${r}`} style={{ textAlign: "right" }}>{r}</th>
            )),
          )}
        </tr>
      </thead>
      <tbody>
        {rows.map((n) => (
          <tr key={n.id}
              className={n.kind === "table" ? "dcs-result-row--total"
                       : n.kind === "group" ? "dcs-result-row--group" : undefined}>
            <td style={{ paddingLeft: 6 + n.level * 16 }}>
              {(n.children?.length ?? 0) > 0 && (
                <button type="button" className="dcs-result-toggle" onClick={() => toggle(n.id)}>
                  {collapsed.has(n.id) ? "▸" : "▾"}
                </button>
              )}
              <span
                className={onDrilldown && n.details ? "dcs-result-cell dcs-result-cell--drill" : undefined}
                onClick={() => onDrilldown && n.details && onDrilldown(n.details, "detail")}
              >
                {n.kind === "table" ? (n.display ?? "Total") : (n.display ?? "")}
              </span>
            </td>
            {[...leaves.map((l) => l.key), ""].flatMap((key) =>
              resources.map((r) => (
                <td key={`${n.id}|${key}|${r}`} className="is-number">
                  {format(n.cells[`${key}|${r}`], undefined)}
                </td>
              )),
            )}
          </tr>
        ))}
      </tbody>
    </table>
  );
}

// ------------------------------------------------------------------ helpers

/** Разворачивает дерево в список строк, пропуская детей свёрнутых узлов. */
function flatten(nodes: ResultNode[], collapsed: Set<string>): ResultNode[] {
  const out: ResultNode[] = [];
  const walk = (list: ResultNode[]) => {
    for (const n of list) {
      out.push(n);
      if (!collapsed.has(n.id) && n.children) walk(n.children);
    }
  };
  walk(nodes);
  return out;
}

function collectIds(nodes: ResultNode[]): Set<string> {
  const ids = new Set<string>();
  const walk = (list: ResultNode[]) => {
    for (const n of list) {
      if (n.children && n.children.length > 0) { ids.add(n.id); walk(n.children); }
    }
  };
  walk(nodes);
  return ids;
}

function collectLeaves(headers: ResultColumnHeader[]): ResultColumnHeader[] {
  const out: ResultColumnHeader[] = [];
  const walk = (list: ResultColumnHeader[]) => {
    for (const h of list) {
      if (!h.children || h.children.length === 0) out.push(h);
      else walk(h.children);
    }
  };
  walk(headers);
  return out;
}

/** Имена ресурсов кросс-таблицы выводятся из ключей ячеек «колонка|ресурс». */
function resourceKeys(node: ResultNode): string[] {
  const set = new Set<string>();
  for (const key of Object.keys(node.cells)) {
    const bar = key.lastIndexOf("|");
    if (bar >= 0) set.add(key.slice(bar + 1));
  }
  return [...set];
}

function styleOf(a: Appearance | undefined): React.CSSProperties | undefined {
  if (!a) return undefined;
  return {
    color: a.textColor ?? undefined,
    background: a.backColor ?? undefined,
    fontWeight: a.bold ? 700 : undefined,
    fontStyle: a.italic ? "italic" : undefined,
    textAlign: (a.align as React.CSSProperties["textAlign"]) ?? undefined,
    display: a.visible === false ? "none" : undefined,
  };
}

/** Числа выводятся с разделителями групп — иначе итоги нечитаемы. */
function format(value: unknown, appearance: Appearance | undefined): string {
  if (appearance?.text) return appearance.text;
  if (value == null) return "";
  if (typeof value === "number") return new Intl.NumberFormat().format(value);
  if (typeof value === "string" && value !== "" && !Number.isNaN(Number(value))
      && /^-?\d+(\.\d+)?$/.test(value)) {
    return new Intl.NumberFormat().format(Number(value));
  }
  if (typeof value === "boolean") return value ? "yes" : "no";
  return String(value);
}
