import type { DataSet, DataSetLink, DcsSchema, LinkType } from "./types";

/**
 * <h2>Упаковка наборов данных в одну строку — клиентское зеркало {@code QueryPackCodec}.</h2>
 *
 * <p>Каноническим носителем запросов остаётся строка {@code schema.packed}: её читает
 * Workbench, из неё собирается SQL. Конструктор же удобнее редактировать наборами и
 * связями по отдельности, поэтому здесь те же кодирование и разбор, что на сервере.
 *
 * <p>Дублирование формата осознанное: гонять запрос на сервер на каждое нажатие клавиши
 * ради переупаковки текста — плохой обмен. Разбор строки, набранной руками, всё равно
 * подтверждается сервером при формировании, поэтому расхождение (если оно появится)
 * всплывёт сразу же, а не тихо накопится.
 */

const NL = "\n";
const DIRECTIVE = /^\s*--\s*#\s*(\w+)\s*(.*)$/;
const LINK = /^\s*([A-Za-z_][A-Za-z0-9_]*)\s*(?:->|=>|:)\s*([A-Za-z_][A-Za-z0-9_]*)\s*([A-Za-z]+(?:\s+OUTER)?)?\s*(?:\bON\b\s*(.*))?$/i;
const COND = /^\s*(.+?)\s*(<>|!=|>=|<=|=|>|<)\s*(.+?)\s*$/;

/** Имя набора: латиница/цифры/подчёркивание, не с цифры. */
export const NAME_RE = /^[A-Za-z_][A-Za-z0-9_]*$/;

export interface ParsedPack {
  datasets: { name: string; title: string | null; sql: string }[];
  links: DataSetLink[];
}

/** Разбирает упакованную строку в наборы и связи. */
export function decodePack(packed: string | null | undefined): ParsedPack {
  const out: ParsedPack = { datasets: [], links: [] };
  if (!packed || !packed.trim()) return out;

  let current: { name: string; title: string | null; sql: string } | null = null;
  let body: string[] = [];
  let sawDirective = false;

  const closeCurrent = () => {
    if (current) current.sql = trimBody(body);
    current = null;
    body = [];
  };

  for (const line of packed.split(/\r?\n/)) {
    const m = DIRECTIVE.exec(line);
    if (!m) { body.push(line); continue; }
    sawDirective = true;
    closeCurrent();

    const keyword = m[1].toLowerCase();
    const arg = (m[2] ?? "").trim();

    if (keyword === "query" || keyword === "dataset" || keyword === "set") {
      const bar = arg.indexOf("|");
      const name = (bar >= 0 ? arg.slice(0, bar) : arg).trim();
      current = {
        name: name || `Query${out.datasets.length + 1}`,
        title: bar >= 0 ? arg.slice(bar + 1).trim() || null : null,
        sql: "",
      };
      out.datasets.push(current);
    } else if (keyword === "link" || keyword === "join") {
      const link = decodeLink(arg);
      if (link) out.links.push(link);
    }
    // Остальные директивы (select/where/order/…) конструктор не редактирует:
    // эти части итогового запроса строит компоновщик из настроек отчёта.
  }
  closeCurrent();

  if (!sawDirective) {
    const sql = trimBody(body);
    if (sql) out.datasets.push({ name: "Query1", title: null, sql });
  }
  out.datasets = out.datasets.filter((d) => d.sql.trim().length > 0);
  return out;
}

function decodeLink(arg: string): DataSetLink | null {
  const m = LINK.exec(arg);
  if (!m) return null;
  const link: DataSetLink = {
    source: m[1],
    target: m[2],
    linkType: normalizeLinkType(m[3]),
    conditions: [],
    rawCondition: null,
    disabled: false,
  };
  const on = m[4];
  if (on && on.trim()) {
    const parts = on.trim().split(/\s+AND\s+/i);
    const conditions = [];
    let structured = true;
    for (const part of parts) {
      const c = COND.exec(part);
      if (!c) { structured = false; break; }
      conditions.push({ sourceExpr: c[1].trim(), operator: c[2], targetExpr: c[3].trim() });
    }
    if (structured && conditions.length > 0) link.conditions = conditions;
    else link.rawCondition = on.trim();
  }
  return link;
}

function normalizeLinkType(s: string | undefined): LinkType {
  const k = (s ?? "").replace(/outer|join/gi, "").trim().toLowerCase();
  return (["inner", "left", "right", "full", "cross"].includes(k) ? k : "left") as LinkType;
}

/** Собирает наборы и связи обратно в упакованную строку. */
export function encodePack(datasets: DataSet[], links: DataSetLink[], packName?: string | null): string {
  const lines: string[] = [];
  if (packName && packName.trim()) lines.push(`--#pack ${packName.trim()}`);

  for (const d of datasets) {
    const title = d.title && d.title.trim() ? ` | ${d.title.trim()}` : "";
    lines.push(`--#query ${d.name}${title}`);
    lines.push((d.query ?? "").trim());
  }
  for (const l of links) {
    if (!l.source || !l.target || l.disabled) continue;
    const on = onText(l);
    lines.push(`--#link ${l.source} -> ${l.target} ${l.linkType.toUpperCase()}${on ? ` ON ${on}` : ""}`);
  }
  return lines.join(NL) + (lines.length ? NL : "");
}

/** Текст ON-условия связи; null — связь без условий (декартово произведение). */
export function onText(l: DataSetLink): string | null {
  if (l.rawCondition && l.rawCondition.trim()) return l.rawCondition.trim();
  const parts = (l.conditions ?? [])
    .filter((c) => c && c.sourceExpr && c.targetExpr)
    .map((c) => `${c.sourceExpr.trim()} ${c.operator || "="} ${c.targetExpr.trim()}`);
  return parts.length ? parts.join(" AND ") : null;
}

function trimBody(body: string[]): string {
  let s = body.join(NL).trim();
  while (s.endsWith(";")) s = s.slice(0, -1).trim();
  return s;
}

/**
 * Синхронизирует схему со строкой: после любой правки наборов или связей
 * {@code packed} пересобирается, чтобы носитель запросов не разъезжался с моделью.
 */
export function repack(schema: DcsSchema): DcsSchema {
  return { ...schema, packed: encodePack(schema.dataSets, schema.links) };
}

/** Схема из упакованной строки — когда строку правили текстом. */
export function applyPacked(schema: DcsSchema, packed: string): DcsSchema {
  const parsed = decodePack(packed);
  const dataSets: DataSet[] = parsed.datasets.map((d) => {
    const existing = schema.dataSets.find((x) => x.name === d.name);
    return {
      name: d.name,
      title: d.title ?? existing?.title ?? null,
      type: existing?.type ?? "query",
      items: existing?.items ?? [],
      autoFill: existing?.autoFill ?? true,
      // Метаданные полей переживают правку текста: заголовки и роли — ручная работа,
      // терять её из-за смены одной строки в SELECT'е нельзя.
      fields: existing?.fields ?? [],
      query: d.sql,
      // А вот дерево конструктора не переживает: строку правили текстом, и
      // соответствие дерева этому тексту больше не гарантировано.
      builder: null,
    };
  });
  return { ...schema, packed, dataSets, links: parsed.links };
}

/** Пустая схема — с чего начинается новый отчёт. */
export function emptySchema(): DcsSchema {
  return {
    packed: "",
    dataSets: [],
    links: [],
    calculatedFields: [],
    resources: [],
    parameters: [],
  };
}
