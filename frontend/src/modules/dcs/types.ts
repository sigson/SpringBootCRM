// Зеркало модели бэкенда (app.modules.dcs.model). Структуры описаны там же, здесь —
// только формы данных, которыми обменивается конструктор с сервером.

export type UUID = string;

// ------------------------------------------------------------------- схема

export type FieldRole =
  | "dimension" | "resource" | "period" | "additionalPeriod"
  | "beginBalance" | "endBalance" | "account";

export type ValueType = "string" | "number" | "date" | "boolean";

export interface DataSetField {
  name: string;
  dataPath?: string | null;
  title?: string | null;
  role?: FieldRole | null;
  periodOrder?: number | null;
  valueType?: ValueType | null;
  usableInSelection: boolean;
  usableInFilter: boolean;
  usableInGroup: boolean;
  usableInOrder: boolean;
  ignoreNull: boolean;
  mandatory: boolean;
}

export interface DataSet {
  name: string;
  title?: string | null;
  type: "query" | "union";
  items: string[];
  autoFill: boolean;
  fields: DataSetField[];
  /** Текст запроса. На бэкенд уходит внутри упакованной строки, не отдельным полем. */
  query?: string;
  /**
   * Дерево визуального конструктора (Statement из конструктора Workbench'а), если
   * запрос собран мышью, а не набран текстом.
   *
   * <p>Тип намеренно {@code unknown}: модель дерева принадлежит модулю Workbench'а, и
   * жёсткий импорт из неё сделал бы этот файл незагружаемым там, где Workbench удалён.
   * Разворачивает его только {@code DatasetQueryBuilder} — единственное место, которое
   * и так без Workbench'а не работает.
   *
   * <p>{@code null} означает «SQL правили руками»: разобрать произвольный текст обратно
   * в дерево нельзя, и конструктор в этом случае начинает с чистого листа.
   */
  builder?: unknown | null;
}

export type LinkType = "inner" | "left" | "right" | "full" | "cross";

export interface LinkCondition {
  sourceExpr: string;
  operator: string;
  targetExpr: string;
}

export interface DataSetLink {
  source: string;
  target: string;
  linkType: LinkType;
  conditions: LinkCondition[];
  rawCondition?: string | null;
  disabled: boolean;
}

export interface CalculatedField {
  name: string;
  title?: string | null;
  expression: string;
  role?: FieldRole | null;
  valueType?: ValueType | null;
  usableInSelection: boolean;
  usableInFilter: boolean;
  usableInGroup: boolean;
  usableInOrder: boolean;
}

export interface ResourceField {
  name: string;
  title?: string | null;
  expression: string;
  calcByGroups: string[];
  format?: string | null;
}

export interface AvailableValue { value: unknown; presentation: string; }

export interface SchemaParameter {
  name: string;
  title?: string | null;
  valueType: ValueType | "list";
  value: unknown;
  availableValues: AvailableValue[];
  stdPeriod: boolean;
  useRestriction: boolean;
  userVisible: boolean;
  required: boolean;
}

export interface DcsSchema {
  /** Упакованная строка: несколько запросов + директивы связей (читает Workbench). */
  packed: string;
  dataSets: DataSet[];
  links: DataSetLink[];
  calculatedFields: CalculatedField[];
  resources: ResourceField[];
  parameters: SchemaParameter[];
}

// ---------------------------------------------------------------- настройки

export type CompareOp =
  | "eq" | "ne" | "in" | "notIn" | "gt" | "ge" | "lt" | "le"
  | "contains" | "notContains" | "beginsWith" | "filled" | "notFilled" | "between";

export interface FilterNode {
  combinator?: string | null;
  items?: FilterNode[];
  left?: string;
  op?: CompareOp;
  right?: unknown;
  disabled?: boolean;
  userVisible?: boolean;
}

export interface FilterGroup {
  combinator: "and" | "or" | "not";
  items: FilterNode[];
}

export interface OrderItem {
  field: string;
  direction: "asc" | "desc";
  disabled?: boolean;
}

export type StructureKind = "grouping" | "table" | "chart";
export type GroupingType = "items" | "hierarchy" | "hierarchyOnly";

export interface StructureNode {
  id: string;
  kind: StructureKind;
  /** null/пусто — «Детальные записи». */
  field?: string | null;
  groupingType: GroupingType;
  title?: string | null;
  selection: string[];
  order: OrderItem[];
  filter?: FilterGroup | null;
  children: StructureNode[];
  rows: StructureNode[];
  columns: StructureNode[];
  chartType?: string;
  disabled?: boolean;
}

export interface Appearance {
  textColor?: string | null;
  backColor?: string | null;
  bold?: boolean | null;
  italic?: boolean | null;
  format?: string | null;
  text?: string | null;
  visible?: boolean | null;
  align?: string | null;
}

export type AppearanceArea = "header" | "details" | "groupTotals" | "grandTotal";

export interface AppearanceItem {
  id: string;
  filter?: FilterGroup | null;
  fields: string[];
  appearance: Appearance;
  areas: AppearanceArea[];
  disabled?: boolean;
}

export interface OutputParameters {
  title?: string | null;
  showTitle: boolean;
  verticalTotals: "begin" | "end" | "none";
  horizontalTotals: "begin" | "end" | "none";
  groupPlacement: "begin" | "end";
  showParameters: boolean;
  showFilter: boolean;
  maxRows?: number | null;
}

export interface UserField {
  name: string;
  title?: string | null;
  kind: "expression" | "select";
  expression?: string;
  cases: { filter?: FilterGroup | null; value: string }[];
}

export interface DcsSettings {
  structure: StructureNode[];
  filter?: FilterGroup | null;
  selection: string[];
  order: OrderItem[];
  conditionalAppearance: AppearanceItem[];
  outputParameters: OutputParameters;
  dataParameters: Record<string, unknown>;
  userFields: UserField[];
}

export interface SettingsVariant { id: string; name: string; settings: DcsSettings; }

export interface SettingsBundle {
  defaultSettings: DcsSettings;
  variants: SettingsVariant[];
}

// ------------------------------------------------------------ макеты и формы

/** Макет: прямоугольная область ячеек с оформлением, как табличный документ 1С. */
export interface ReportTemplate {
  id: string;
  name: string;
  /** Назначение области: заголовок отчёта, шапка, группировка, итог, детали. */
  area: "reportHeader" | "header" | "grouping" | "detail" | "total" | "footer";
  /** Для area=grouping — имя поля группировки, к которой привязан макет. */
  field?: string | null;
  rows: TemplateRow[];
}

export interface TemplateRow { cells: TemplateCell[]; }

export interface TemplateCell {
  /** Текст ячейки; {@code [поле]} подставляет значение поля при выводе. */
  text: string;
  bold?: boolean;
  italic?: boolean;
  align?: "left" | "center" | "right";
  textColor?: string | null;
  backColor?: string | null;
  width?: number | null;
}

/** Форма отчёта, собранная визуальным построителем. */
export interface ReportForm {
  id: string;
  name: string;
  /** Назначение: форма настроек, форма результата, произвольная. */
  purpose: "settings" | "result" | "custom";
  nodes: FormNode[];
}

export type FormNodeKind =
  | "group" | "tabs" | "tab" | "parameter" | "filter"
  | "selection" | "structure" | "result" | "button" | "label" | "spacer";

export interface FormNode {
  id: string;
  kind: FormNodeKind;
  title?: string | null;
  /** Для kind=parameter — имя параметра схемы; для filter/selection — поле. */
  target?: string | null;
  /** Для kind=button — что делает кнопка. */
  action?: "compose" | "export-xlsx" | "export-csv" | "reset" | null;
  /** Ширина колонки в гриде формы (1..12). */
  span?: number;
  readOnly?: boolean;
  children: FormNode[];
}

// ---------------------------------------------------------------- результат

export interface ResultColumn {
  id: string;
  title: string;
  kind: "dimension" | "resource" | "field";
  valueType?: string | null;
  format?: string | null;
  align?: string | null;
}

export interface ResultColumnHeader {
  key: string;
  field: string;
  value: unknown;
  display: string;
  level: number;
  children?: ResultColumnHeader[];
}

export interface ResultNode {
  id: string;
  kind: "grandTotal" | "group" | "detail" | "table";
  level: number;
  field?: string | null;
  fieldTitle?: string | null;
  value?: unknown;
  display?: string | null;
  cells: Record<string, unknown>;
  cellAppearance?: Record<string, Appearance>;
  appearance?: Appearance;
  details?: Record<string, unknown>;
  children?: ResultNode[];
  rowCount: number;
  columnHeaders?: ResultColumnHeader[];
}

export interface CompositionResult {
  reportId?: string | null;
  title?: string | null;
  columns: ResultColumn[];
  rows: ResultNode[];
  parameters: { name: string; title: string; value: unknown; presentation: string }[];
  sql?: string | null;
  warnings: string[];
  sourceRowCount: number;
  truncated: boolean;
  elapsedMs: number;
  aggregatedInSql: boolean;
}

// --------------------------------------------------------------- REST-обмен

export interface ReportDto {
  id: UUID;
  code: string;
  name: string;
  scheme: DcsSchema | null;
  settings: SettingsBundle | null;
  templates: ReportTemplate[] | null;
  forms: ReportForm[] | null;
  dataSourceId: string | null;
  enabled: boolean;
}

export interface AvailableField {
  id: string;
  title: string;
  kind: "source" | "calculated" | "resource" | "user";
  role?: string | null;
  valueType?: string | null;
  usableInSelection: boolean;
  usableInFilter: boolean;
  usableInGroup: boolean;
  usableInOrder: boolean;
}

export interface SqlPreview {
  sql: string;
  parameterCount: number;
  warnings: string[];
  packed: string;
}

export interface DescribedColumn { name: string; typeName: string; valueType: ValueType; }
export interface DescribedDataSet { name: string; columns: DescribedColumn[]; error?: string | null; }

/** Предпросмотр данных одного набора (конструктор запроса). */
export interface DataSetPreview {
  name: string;
  columns: string[];
  rows: unknown[][];
  sql?: string | null;
  elapsedMs: number;
  error?: string | null;
}

export interface ExpressionCheck { valid: boolean; message?: string | null; fields: string[]; }

/** Структура пакета запросов, как её отдаёт Workbench. */
export interface QueryPackDto {
  name?: string | null;
  queries: { name: string; title?: string | null; sql: string; disabled: boolean }[];
  links: {
    source: string; target: string;
    type: "INNER" | "LEFT" | "RIGHT" | "FULL" | "CROSS";
    conditions: LinkCondition[];
    rawCondition?: string | null;
    disabled: boolean;
  }[];
  select: string[];
  where: string[];
  groupBy: string[];
  having: string[];
  orderBy: string[];
  limit?: number | null;
  distinct: boolean;
}

export interface QueryPackSqlResponse {
  sql: string;
  sqlPretty: string;
  joinedQueries: string[];
  warnings: string[];
  packed: string;
}
