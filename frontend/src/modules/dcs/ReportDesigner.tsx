import { useCallback, useEffect, useMemo, useState } from "react";
import { DcsClient, DcsApiError } from "./api";
import { DataSetsTab } from "./DataSetsTab";
import { FormsTab } from "./FormBuilder";
import { LinksTab } from "./LinksTab";
import { ReportRunner } from "./ReportRunner";
import { ResultView } from "./ResultView";
import { CalculatedFieldsTab, ParametersTab, ResourcesTab } from "./SchemaExtrasTabs";
import { SettingsDesigner } from "./SettingsDesigner";
import { TemplatesTab } from "./TemplateEditor";
import { decodePack, emptySchema } from "./packing";
import type {
  AvailableField, CompositionResult, DcsSchema, DcsSettings, FormNode, ReportForm,
  ReportTemplate, SettingsBundle, StructureNode, UUID,
} from "./types";
import { Banner, Btn, Empty, Field, Grid, Select, Spacer, TabBar, TextInput, Toolbar, clone } from "./ui";
import "./styles.css";

/**
 * <h2>Конструктор отчёта — корневой компонент модуля.</h2>
 *
 * <p>Повторяет набор вкладок конструктора схемы 1С: наборы данных, связи, вычисляемые
 * поля, ресурсы, параметры, макеты, формы, настройки — и добавляет девятую, «Результат»,
 * чтобы проверять отчёт не выходя из редактора.
 *
 * <p>Всё состояние держится здесь и сохраняется одним запросом в справочник отчётов:
 * четыре JSON-реквизита элемента и есть содержимое вкладок. Отдельного «сохранить
 * схему» и «сохранить настройки» нет намеренно — отчёт целостен, и частичное сохранение
 * оставляло бы настройки, ссылающиеся на несуществующие поля.
 */
export interface ReportDesignerProps {
  /** UUID редактируемого отчёта; {@code null} — создание нового. */
  id: UUID | null;
  /** Предварительно полученный код нового элемента справочника. */
  prefetchedCode?: string | null;
  getToken?: () => string | null | undefined;
  onClose: () => void;
  onSaved?: (id?: string) => void;
}

export function ReportDesigner({ id, prefetchedCode, getToken, onClose, onSaved }: ReportDesignerProps) {
  const client = useMemo(() => new DcsClient({ getToken }), [getToken]);

  const [tab, setTab] = useState("datasets");
  const [loading, setLoading] = useState(id != null);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const [code, setCode] = useState(prefetchedCode ?? "");
  const [name, setName] = useState("");
  const [dataSourceId, setDataSourceId] = useState("main");
  const [dataSources, setDataSources] = useState<{ id: string; name: string }[]>([]);

  const [schema, setSchema] = useState<DcsSchema>(emptySchema);
  const [bundle, setBundle] = useState<SettingsBundle>(() => ({
    defaultSettings: emptySettings(), variants: [],
  }));
  const [templates, setTemplates] = useState<ReportTemplate[]>([]);
  const [forms, setForms] = useState<ReportForm[]>([]);

  const [fields, setFields] = useState<AvailableField[]>([]);
  const [result, setResult] = useState<CompositionResult | null>(null);
  const [composing, setComposing] = useState(false);

  const settings = bundle.defaultSettings;

  // ------------------------------------------------------------ загрузка

  useEffect(() => {
    client.dataSources()
      .then((ds) => setDataSources(ds.map((d) => ({ id: d.id, name: d.name }))))
      .catch(() => setDataSources([{ id: "main", name: "main" }]));
  }, [client]);

  useEffect(() => {
    if (id == null) { setLoading(false); return; }
    let alive = true;
    client.getReport(id)
      .then((dto) => {
        if (!alive) return;
        setCode(dto.code);
        setName(dto.name);
        setDataSourceId(dto.dataSourceId || "main");
        setSchema(dto.scheme ? normalizeSchema(dto.scheme) : emptySchema());
        setBundle(normalizeBundle(dto.settings));
        setTemplates(normalizeTemplates(dto.templates));
        setForms(normalizeForms(dto.forms));
      })
      .catch((e) => { if (alive) setError(messageOf(e)); })
      .finally(() => { if (alive) setLoading(false); });
    return () => { alive = false; };
  }, [id, client]);

  // Доступные поля пересчитываются на сервере: их состав зависит от схемы целиком
  // (наборы + вычисляемые + ресурсы + пользовательские поля настроек), и повторять
  // эту логику в браузере значило бы держать вторую её версию.
  useEffect(() => {
    if (schema.dataSets.length === 0) { setFields([]); return; }
    let alive = true;
    const timer = setTimeout(() => {
      client.availableFields(schema, settings)
        .then((f) => { if (alive) setFields(f); })
        .catch(() => { if (alive) setFields([]); });
    }, 300);
    return () => { alive = false; clearTimeout(timer); };
  }, [schema, settings, client]);

  // ------------------------------------------------------------ действия

  const compose = useCallback(async () => {
    setComposing(true);
    setError(null);
    try {
      setResult(await client.compose(schema, settings, dataSourceId));
    } catch (e) {
      setError(messageOf(e));
      setResult(null);
    } finally {
      setComposing(false);
    }
  }, [client, schema, settings, dataSourceId]);

  async function save() {
    if (!name.trim()) { setError("Enter the report name"); return; }
    setSaving(true);
    setError(null);
    try {
      const body = {
        name: name.trim(),
        scheme: schema,
        settings: bundle,
        templates,
        forms,
        dataSourceId,
        enabled: true,
      };
      if (id == null) {
        const dto = await client.createReport({ code: code.trim() || undefined, ...body });
        setNotice(`Report ${dto.code} saved`);
        onSaved?.(dto.id);
      } else {
        await client.updateReport(id, body);
        setNotice("Saved");
        onSaved?.(id);
      }
    } catch (e) {
      setError(messageOf(e));
    } finally {
      setSaving(false);
    }
  }

  function exportResult(format: "xlsx" | "csv") {
    client.exportAdHoc(schema, settings, dataSourceId, format, name || "report")
      .catch((e) => setError(messageOf(e)));
  }

  function drilldown(details: Record<string, unknown>, action: "detail" | "groupBy", field?: string) {
    // Расшифровка из конструктора идёт по присланной схеме: отчёт может быть ещё не
    // сохранён, поэтому серверный эндпоинт по id здесь не годится — собираем
    // эквивалентные настройки на месте.
    const drill = clone(settings);
    const items = Object.entries(details).map(([left, value]) => ({
      left, op: value == null ? ("notFilled" as const) : ("eq" as const), right: value,
    }));
    drill.filter = drill.filter && drill.filter.items.length > 0
      ? { combinator: "and", items: [{ combinator: drill.filter.combinator, items: drill.filter.items }, ...items] }
      : { combinator: "and", items };
    // Детальная расшифровка показывает исходные поля, а не ресурсы: именно ради
    // строк её и открывают.
    const detailSelection = fields
      .filter((f) => f.usableInSelection && (f.kind === "source" || f.kind === "calculated"))
      .map((f) => f.id);
    drill.structure = [{
      id: "drilldown", kind: "grouping",
      field: action === "groupBy" ? (field ?? null) : null,
      groupingType: "items",
      title: action === "groupBy" ? `Breakdown by ${field}` : "Detail records",
      selection: detailSelection,
      order: [], filter: null, children: [], rows: [], columns: [],
    }];
    setComposing(true);
    client.compose(schema, drill, dataSourceId)
      .then(setResult)
      .catch((e) => setError(messageOf(e)))
      .finally(() => setComposing(false));
  }

  if (loading) return <div className="dcs-root"><Empty>Loading the report…</Empty></div>;

  return (
    <div className="dcs-root">
      <Grid cols={4}>
        <Field label="Code" hint={id == null ? "Generated; may be changed" : undefined}>
          <TextInput value={code} onChange={setCode} readOnly={id != null} mono />
        </Field>
        <Field label="Report name">
          <TextInput value={name} onChange={setName} />
        </Field>
        <Field label="Data source" hint="SQL Workbench data source the queries run against">
          <Select value={dataSourceId} onChange={setDataSourceId}
                  options={dataSources.map((d) => ({ value: d.id, label: d.name }))} />
        </Field>
        <Field label="&nbsp;">
          <Toolbar>
            <Btn kind="primary" onClick={() => void compose()} disabled={composing}>
              {composing ? "Composing…" : "Compose"}
            </Btn>
            <Btn onClick={() => void save()} disabled={saving}>{saving ? "Saving…" : "Save"}</Btn>
            <Btn kind="ghost" onClick={onClose}>Close</Btn>
          </Toolbar>
        </Field>
      </Grid>

      {error && <Banner kind="error" onClose={() => setError(null)}>{error}</Banner>}
      {notice && <Banner kind="ok" onClose={() => setNotice(null)}>{notice}</Banner>}

      <TabBar
        active={tab}
        onChange={setTab}
        tabs={[
          { id: "datasets", label: "Datasets", badge: schema.dataSets.length },
          { id: "links", label: "Links", badge: schema.links.length },
          { id: "calculated", label: "Calculated fields", badge: schema.calculatedFields.length },
          { id: "resources", label: "Resources", badge: schema.resources.length },
          { id: "parameters", label: "Parameters", badge: schema.parameters.length },
          { id: "settings", label: "Settings" },
          { id: "templates", label: "Templates", badge: templates.length },
          { id: "forms", label: "Forms", badge: forms.length },
          { id: "result", label: "Result" },
          // Форма звіту працює лише зі збереженим звітом: вона звертається до
          // рушія по id, а не шле схему в тілі запиту.
          ...(id != null ? [{ id: "run", label: "Report form" }] : []),
        ]}
      />

      <div style={{ flex: 1, minHeight: 0, display: "flex", flexDirection: "column" }}>
        {tab === "datasets" && (
          <DataSetsTab schema={schema} onChange={setSchema} client={client}
                       dataSourceId={dataSourceId} getToken={getToken} />
        )}
        {tab === "links" && <LinksTab schema={schema} onChange={setSchema} client={client} />}
        {tab === "calculated" && (
          <CalculatedFieldsTab schema={schema} onChange={setSchema} client={client} fields={fields} />
        )}
        {tab === "resources" && (
          <ResourcesTab schema={schema} onChange={setSchema} client={client} fields={fields} />
        )}
        {tab === "parameters" && <ParametersTab schema={schema} onChange={setSchema} />}
        {tab === "settings" && (
          <SettingsDesigner
            settings={settings}
            onChange={(s) => setBundle({ ...bundle, defaultSettings: s })}
            schema={schema}
            fields={fields}
          />
        )}
        {tab === "templates" && (
          <TemplatesTab templates={templates} onChange={setTemplates} fields={fields} />
        )}
        {tab === "forms" && (
          <FormsTab forms={forms} onChange={setForms} schema={schema} fields={fields} />
        )}
        {tab === "result" && (
          <ResultView result={result} busy={composing} templates={templates}
                      onDrilldown={drilldown} onExport={exportResult} />
        )}
        {tab === "run" && id != null && (
          <div style={{ overflow: "auto", minHeight: 0 }}>
            <Banner kind="info">
              This is the report as its users see it: the form from the «Forms» tab, driven by
              the saved schema. Unsaved changes do not affect it — save the report first.
            </Banner>
            <ReportRunner reportId={id} title={name} getToken={getToken} />
          </div>
        )}
      </div>

      {tab !== "result" && result && (
        <Toolbar>
          <span className="dcs-muted">
            Last composition: {result.sourceRowCount} record(s), {result.elapsedMs} ms
          </span>
          <Spacer />
          <Btn small onClick={() => setTab("result")}>Show the result</Btn>
        </Toolbar>
      )}
    </div>
  );
}

// ---------------------------------------------------------------- значения

export function emptySettings(): DcsSettings {
  return {
    structure: [],
    filter: null,
    selection: [],
    order: [],
    conditionalAppearance: [],
    outputParameters: {
      title: null,
      showTitle: true,
      verticalTotals: "end",
      horizontalTotals: "end",
      groupPlacement: "begin",
      showParameters: true,
      showFilter: false,
      maxRows: null,
    },
    dataParameters: {},
    userFields: [],
  };
}

/**
 * Достройка схемы до полной формы.
 *
 * <p>JSON-реквизит отчёта хранится как есть: его мог записать более ранний конструктор,
 * другой клиент или человек руками, и любое необязательное поле в нём может
 * отсутствовать. Нормализация — единственное место, где это учитывается; дальше по
 * модулю коллекции считаются существующими, и компоненты не обрастают проверками на
 * undefined (одна пропущенная проверка роняет всю вкладку).
 */
function normalizeSchema(s: Partial<DcsSchema>): DcsSchema {
  const schema: DcsSchema = {
    packed: s.packed ?? "",
    dataSets: (s.dataSets ?? []).map((d) => ({
      name: d.name,
      title: d.title ?? null,
      type: d.type ?? "query",
      items: d.items ?? [],
      autoFill: d.autoFill ?? true,
      fields: (d.fields ?? []).map((f) => ({
        name: f.name,
        dataPath: f.dataPath ?? f.name,
        title: f.title ?? f.name,
        role: f.role ?? null,
        periodOrder: f.periodOrder ?? null,
        valueType: f.valueType ?? "string",
        usableInSelection: f.usableInSelection ?? true,
        usableInFilter: f.usableInFilter ?? true,
        usableInGroup: f.usableInGroup ?? true,
        usableInOrder: f.usableInOrder ?? true,
        ignoreNull: f.ignoreNull ?? false,
        mandatory: f.mandatory ?? false,
      })),
      query: d.query ?? "",
      // Дерево конструктора переживает загрузку: иначе набор, собранный мышью,
      // после переоткрытия отчёта предлагал бы строить его заново.
      builder: d.builder ?? null,
    })),
    links: (s.links ?? []).map((l) => ({
      source: l.source,
      target: l.target,
      linkType: l.linkType ?? "left",
      conditions: l.conditions ?? [],
      rawCondition: l.rawCondition ?? null,
      disabled: l.disabled ?? false,
    })),
    calculatedFields: (s.calculatedFields ?? []).map((c) => ({
      name: c.name,
      title: c.title ?? null,
      expression: c.expression ?? "",
      role: c.role ?? null,
      valueType: c.valueType ?? null,
      usableInSelection: c.usableInSelection ?? true,
      usableInFilter: c.usableInFilter ?? false,
      usableInGroup: c.usableInGroup ?? false,
      usableInOrder: c.usableInOrder ?? true,
    })),
    resources: (s.resources ?? []).map((r) => ({
      name: r.name,
      title: r.title ?? null,
      expression: r.expression ?? "",
      calcByGroups: r.calcByGroups ?? [],
      format: r.format ?? null,
    })),
    parameters: (s.parameters ?? []).map((p) => ({
      name: p.name,
      title: p.title ?? null,
      valueType: p.valueType ?? "string",
      value: p.value ?? null,
      availableValues: p.availableValues ?? [],
      stdPeriod: p.stdPeriod ?? false,
      useRestriction: p.useRestriction ?? false,
      userVisible: p.userVisible ?? true,
      required: p.required ?? false,
    })),
  };

  // Тексты запросов живут только в упакованной строке, поэтому при загрузке
  // раскладываем их обратно по наборам — иначе вкладка «Запрос» была бы пустой.
  const parsed = decodePack(schema.packed);
  schema.dataSets = schema.dataSets.map((d) => ({
    ...d,
    query: parsed.datasets.find((p) => p.name === d.name)?.sql ?? d.query ?? "",
  }));
  // Набор, который есть в строке, но не описан метаданными, всё равно должен быть виден.
  for (const p of parsed.datasets) {
    if (!schema.dataSets.some((d) => d.name === p.name)) {
      schema.dataSets.push({
        name: p.name, title: p.title, type: "query", items: [],
        autoFill: true, fields: [], query: p.sql,
      });
    }
  }
  return schema;
}

function normalizeBundle(b: SettingsBundle | null): SettingsBundle {
  if (!b) return { defaultSettings: emptySettings(), variants: [] };
  return {
    defaultSettings: normalizeSettings(b.defaultSettings),
    variants: (b.variants ?? []).map((v) => ({ ...v, settings: normalizeSettings(v.settings) })),
  };
}

/** Та же достройка для настроек: недостающие разделы заменяются пустыми. */
function normalizeSettings(s: Partial<DcsSettings> | null | undefined): DcsSettings {
  const base = emptySettings();
  if (!s) return base;
  return {
    structure: (s.structure ?? []).map(normalizeStructureNode),
    filter: s.filter ?? null,
    selection: s.selection ?? [],
    order: s.order ?? [],
    conditionalAppearance: (s.conditionalAppearance ?? []).map((a) => ({
      ...a,
      fields: a.fields ?? [],
      areas: a.areas ?? [],
      appearance: a.appearance ?? {},
    })),
    outputParameters: { ...base.outputParameters, ...(s.outputParameters ?? {}) },
    dataParameters: s.dataParameters ?? {},
    userFields: (s.userFields ?? []).map((u) => ({ ...u, cases: u.cases ?? [] })),
  };
}

/** Макет без строк или с рваными строками не должен ронять вкладку. */
function normalizeTemplates(list: ReportTemplate[] | null): ReportTemplate[] {
  return (list ?? []).map((t) => ({
    ...t,
    area: t.area ?? "detail",
    field: t.field ?? null,
    rows: (t.rows ?? []).map((r) => ({
      cells: (r.cells ?? []).map((c) => ({
        text: c.text ?? "",
        bold: c.bold ?? false,
        italic: c.italic ?? false,
        align: c.align ?? "left",
        textColor: c.textColor ?? null,
        backColor: c.backColor ?? null,
        width: c.width ?? null,
      })),
    })),
  }));
}

function normalizeForms(list: ReportForm[] | null): ReportForm[] {
  const node = (n: FormNode): FormNode => ({
    ...n,
    title: n.title ?? null,
    target: n.target ?? null,
    action: n.action ?? null,
    span: n.span ?? 4,
    readOnly: n.readOnly ?? false,
    children: (n.children ?? []).map(node),
  });
  return (list ?? []).map((f) => ({
    ...f,
    purpose: f.purpose ?? "settings",
    nodes: (f.nodes ?? []).map(node),
  }));
}

function normalizeStructureNode(n: StructureNode): StructureNode {
  return {
    ...n,
    kind: n.kind ?? "grouping",
    groupingType: n.groupingType ?? "items",
    selection: n.selection ?? [],
    order: n.order ?? [],
    filter: n.filter ?? null,
    children: (n.children ?? []).map(normalizeStructureNode),
    rows: (n.rows ?? []).map(normalizeStructureNode),
    columns: (n.columns ?? []).map(normalizeStructureNode),
  };
}

function messageOf(e: unknown): string {
  if (e instanceof DcsApiError) return e.message;
  return e instanceof Error ? e.message : String(e);
}
