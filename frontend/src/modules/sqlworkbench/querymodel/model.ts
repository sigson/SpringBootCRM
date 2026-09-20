// TS-порт app.springbootcrm.querymodel.QueryModel + SqlFormatter.
// Позволяет визуальному конструктору формировать SQL и на клиенте (предпросмотр),
// и отправлять идентичную модель на бэкенд для исполнения.

export type Quantifier = "ALL" | "DISTINCT";
export type JoinType = "INNER" | "LEFT_OUTER" | "RIGHT_OUTER" | "FULL_OUTER" | "CROSS";

export const JOIN_SQL: Record<JoinType, string> = {
  INNER: "INNER JOIN",
  LEFT_OUTER: "LEFT OUTER JOIN",
  RIGHT_OUTER: "RIGHT OUTER JOIN",
  FULL_OUTER: "FULL OUTER JOIN",
  CROSS: "CROSS JOIN",
};

export interface TableRef { schema?: string; name: string; alias?: string; }
export interface ColumnRef { table?: string; name: string; alias?: string; expression?: boolean; }
export interface Condition { append?: "AND" | "OR"; left: string; operator: string; right: string; }
export interface Join {
  type: JoinType; leftTable: string; leftColumn: string;
  operator: string; rightTable: string; rightColumn: string;
}
export interface Sort { expression: string; ascending: boolean; }

export interface QueryModel {
  schema?: string;
  quantifier: Quantifier;
  asterisk: boolean;
  select: ColumnRef[];
  from: TableRef[];
  joins: Join[];
  where: Condition[];
  groupBy: string[];
  having: Condition[];
  orderBy: Sort[];
  limit?: number;
}

export function emptyModel(): QueryModel {
  return {
    quantifier: "ALL", asterisk: false, select: [], from: [], joins: [],
    where: [], groupBy: [], having: [], orderBy: [],
  };
}

export function tableRef(t: TableRef): string {
  return t.alias && t.alias.trim() ? t.alias : tableIdentifier(t);
}
function tableIdentifier(t: TableRef): string {
  return t.schema && t.schema.trim() ? `${t.schema}.${t.name}` : t.name;
}

// Зеркало SqlFormatter.toSql — используется для живого предпросмотра.
export function toSql(m: QueryModel, wrap = false): string {
  const nl = wrap ? "\n" : " ";
  let sb = "SELECT ";
  if (m.quantifier === "DISTINCT") sb += "DISTINCT ";

  if (m.asterisk || m.select.length === 0) {
    sb += "*";
  } else {
    sb += m.select.map(col).join("," + (wrap ? nl + "       " : " "));
  }

  sb += nl + "FROM " + fromClause(m, nl);

  if (m.where.length) sb += nl + "WHERE " + conditions(m.where);
  if (m.groupBy.length) sb += nl + "GROUP BY " + m.groupBy.join(", ");
  if (m.having.length) sb += nl + "HAVING " + conditions(m.having);
  if (m.orderBy.length)
    sb += nl + "ORDER BY " + m.orderBy.map((s) => `${s.expression} ${s.ascending ? "ASC" : "DESC"}`).join(", ");
  if (m.limit != null) sb += nl + "LIMIT " + m.limit;
  return sb;
}

function col(c: ColumnRef): string {
  const base = c.expression ? c.name : c.table ? `${c.table}.${c.name}` : c.name;
  return c.alias && c.alias.trim() ? `${base} AS ${c.alias}` : base;
}

function fromClause(m: QueryModel, nl: string): string {
  if (m.joins.length === 0) {
    return m.from.map(table).join(", ");
  }
  const first = m.joins[0];
  let sb = tableByRef(m, first.leftTable);
  for (const j of m.joins) {
    sb += nl + "  " + JOIN_SQL[j.type] + " " + tableByRef(m, j.rightTable);
    if (j.type !== "CROSS") {
      sb += ` ON ${j.leftTable}.${j.leftColumn} ${j.operator} ${j.rightTable}.${j.rightColumn}`;
    }
  }
  return sb;
}

function tableByRef(m: QueryModel, ref: string): string {
  const t = m.from.find((x) => tableRef(x) === ref);
  return t ? table(t) : ref;
}
function table(t: TableRef): string {
  return t.alias && t.alias.trim() ? `${tableIdentifier(t)} AS ${t.alias}` : tableIdentifier(t);
}
function conditions(conds: Condition[]): string {
  return conds
    .map((c, i) => (i === 0 ? "" : ` ${c.append ?? "AND"} `) + `${c.left} ${c.operator} ${c.right}`)
    .join("");
}
