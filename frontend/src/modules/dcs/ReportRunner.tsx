import { useCallback, useEffect, useMemo, useState } from "react";
import { DcsApiError, DcsClient } from "./api";
import { FilterBuilder } from "./FilterBuilder";
import { ResultView } from "./ResultView";
import { emptySettings } from "./ReportDesigner";
import type {
  AvailableField, CompositionResult, DcsSettings, FormNode, ReportForm,
  ReportTemplate, SchemaParameter, SettingsVariant, UUID,
} from "./types";
import {
  Banner, Btn, Empty, Field, Panel, Select, Spacer, TextInput, Toolbar, clone,
} from "./ui";
import "./styles.css";

/**
 * <h2>Форма отчёта — то, что видит конечный пользователь.</h2>
 *
 * <p>Здесь формы из конструктора перестают быть декларацией и начинают работать:
 * runtime обходит дерево {@link FormNode} и подставляет на место каждого элемента
 * живой контрол — поле параметра, построитель отбора, кнопку, область результата.
 * Форма не хранит контролы, она хранит <b>привязки</b>, поэтому переименование
 * параметра в схеме не ломает форму молча: элемент просто перестаёт находить цель и
 * честно говорит об этом.
 *
 * <p>Если форм у отчёта нет, показывается стандартная: параметры, отбор, кнопки,
 * результат. Так отчёт можно сформировать сразу после описания схемы, не рисуя форму.
 */
export interface ReportRunnerProps {
  reportId: UUID;
  getToken?: () => string | null | undefined;
  /** Имя отчёта для заголовка файла выгрузки. */
  title?: string;
  onClose?: () => void;
}

export function ReportRunner({ reportId, getToken, title, onClose }: ReportRunnerProps) {
  const client = useMemo(() => new DcsClient({ getToken }), [getToken]);

  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [fields, setFields] = useState<AvailableField[]>([]);
  const [parameters, setParameters] = useState<SchemaParameter[]>([]);
  const [forms, setForms] = useState<ReportForm[]>([]);
  const [templates, setTemplates] = useState<ReportTemplate[]>([]);
  const [variants, setVariants] = useState<SettingsVariant[]>([]);
  const [variantId, setVariantId] = useState<string>("");
  const [settings, setSettings] = useState<DcsSettings>(emptySettings);
  const [result, setResult] = useState<CompositionResult | null>(null);
  const [composing, setComposing] = useState(false);

  useEffect(() => {
    let alive = true;
    setLoading(true);
    client.reportMeta(reportId)
      .then((meta) => {
        if (!alive) return;
        setFields(meta.fields ?? []);
        setParameters(meta.parameters ?? []);
        setForms(meta.forms ?? []);
        setTemplates(meta.templates ?? []);
        setVariants(meta.variants ?? []);
        setSettings({ ...emptySettings(), ...(meta.defaultSettings ?? {}) });
      })
      .catch((e) => { if (alive) setError(messageOf(e)); })
      .finally(() => { if (alive) setLoading(false); });
    return () => { alive = false; };
  }, [client, reportId]);

  const compose = useCallback(async () => {
    setComposing(true);
    setError(null);
    try {
      setResult(await client.composeSaved(reportId, userDelta(settings), variantId || null));
    } catch (e) {
      setError(messageOf(e));
    } finally {
      setComposing(false);
    }
  }, [client, reportId, settings, variantId]);

  function exportTo(format: "xlsx" | "csv") {
    client.exportSaved(reportId, userDelta(settings), variantId || null, format, title || "report")
      .catch((e) => setError(messageOf(e)));
  }

  function drilldown(details: Record<string, unknown>, action: "detail" | "groupBy", field?: string) {
    setComposing(true);
    client.drilldown(reportId, {
      variantId: variantId || null, settings: userDelta(settings),
      details, action, field: field ?? null,
    })
      .then(setResult)
      .catch((e) => setError(messageOf(e)))
      .finally(() => setComposing(false));
  }

  function setParameter(name: string, value: unknown) {
    setSettings((s) => ({ ...s, dataParameters: { ...s.dataParameters, [name]: value } }));
  }

  if (loading) return <div className="dcs-root"><Empty>Loading the report…</Empty></div>;

  const form = forms.find((f) => f.purpose === "settings") ?? forms[0] ?? null;

  const ctx: RuntimeContext = {
    parameters, fields, settings, setSettings, setParameter,
    compose, exportTo, result, composing, drilldown, templates,
    reset: () => setSettings(emptySettings()),
  };

  return (
    <div className="dcs-root">
      <Toolbar>
        {title && <strong>{title}</strong>}
        {variants.length > 0 && (
          <>
            <span className="dcs-muted">Variant:</span>
            <Select value={variantId} onChange={setVariantId} empty="Default"
                    options={variants.map((v) => ({ value: v.id, label: v.name }))} />
          </>
        )}
        <Spacer />
        <Btn kind="primary" onClick={() => void compose()} disabled={composing}>
          {composing ? "Composing…" : "Compose"}
        </Btn>
        {onClose && <Btn kind="ghost" onClick={onClose}>Close</Btn>}
      </Toolbar>

      {error && <Banner kind="error" onClose={() => setError(null)}>{error}</Banner>}

      {/*
        Форма прокручивается внутри окна: у неё переменная высота (отбор растёт с
        каждым условием, результат — с каждой группировкой), а окно фиксировано.
      */}
      <div style={{ overflow: "auto", flex: 1, minHeight: 0, paddingRight: 2 }}>
        {form ? (
          <div className="dcs-form-preview" style={{ marginTop: 8 }}>
            <RuntimeNodes nodes={form.nodes} ctx={ctx} />
          </div>
        ) : (
          <DefaultForm ctx={ctx} />
        )}
      </div>
    </div>
  );
}

// -------------------------------------------------------------- рендер формы

interface RuntimeContext {
  parameters: SchemaParameter[];
  fields: AvailableField[];
  settings: DcsSettings;
  setSettings: React.Dispatch<React.SetStateAction<DcsSettings>>;
  setParameter: (name: string, value: unknown) => void;
  compose: () => void | Promise<void>;
  exportTo: (f: "xlsx" | "csv") => void;
  reset: () => void;
  result: CompositionResult | null;
  composing: boolean;
  drilldown: (d: Record<string, unknown>, a: "detail" | "groupBy", f?: string) => void;
  templates: ReportTemplate[];
}

function RuntimeNodes({ nodes, ctx }: { nodes: FormNode[]; ctx: RuntimeContext }) {
  return (
    <>
      {nodes.map((n) => (
        <div key={n.id} style={{ gridColumn: `span ${Math.min(12, Math.max(1, n.span ?? 4))}`, minWidth: 0 }}>
          <RuntimeNode node={n} ctx={ctx} />
        </div>
      ))}
    </>
  );
}

function RuntimeNode({ node, ctx }: { node: FormNode; ctx: RuntimeContext }) {
  switch (node.kind) {
    case "group":
    case "tab":
    case "tabs":
      return (
        <Panel title={node.title ?? undefined}>
          <div className="dcs-form-preview">
            <RuntimeNodes nodes={node.children} ctx={ctx} />
          </div>
        </Panel>
      );

    case "parameter": {
      const p = ctx.parameters.find((x) => x.name === node.target);
      if (!p) {
        return (
          <Banner kind="warn">
            The form refers to parameter «{node.target}», which the schema no longer has.
          </Banner>
        );
      }
      const value = ctx.settings.dataParameters[p.name];
      return (
        <Field label={node.title || p.title || p.name} hint={p.required ? "required" : undefined}>
          {p.availableValues.length > 0 ? (
            <Select
              value={value == null ? "" : String(value)}
              empty="—"
              disabled={p.useRestriction || node.readOnly}
              onChange={(v) => ctx.setParameter(p.name, coerce(v, p.valueType))}
              options={p.availableValues.map((av) => ({
                value: String(av.value), label: av.presentation || String(av.value),
              }))}
            />
          ) : (
            <TextInput
              value={value == null ? "" : String(value)}
              readOnly={p.useRestriction || node.readOnly}
              onChange={(v) => ctx.setParameter(p.name, coerce(v, p.valueType))}
            />
          )}
        </Field>
      );
    }

    case "filter": {
      // Без собственного заголовка элемент рисуется голым: он почти всегда лежит
      // в группе, которая его уже назвала, и вторая рамка с тем же словом — шум.
      const filter = (
        <FilterBuilder
          group={ctx.settings.filter}
          onChange={(filter) => ctx.setSettings((s) => ({ ...s, filter }))}
          fields={node.target ? ctx.fields.filter((f) => f.id === node.target) : ctx.fields}
          parameters={ctx.parameters}
        />
      );
      return node.title ? <Panel title={node.title}>{filter}</Panel> : filter;
    }

    case "selection": {
      const chips = (
        <div className="dcs-chips">
            {ctx.fields.filter((f) => f.usableInSelection).map((f) => (
              <label key={f.id} className="dcs-check">
                <input
                  type="checkbox"
                  checked={ctx.settings.selection.includes(f.id)}
                  onChange={(e) => ctx.setSettings((s) => ({
                    ...s,
                    selection: e.target.checked
                      ? [...s.selection, f.id]
                      : s.selection.filter((x) => x !== f.id),
                  }))}
                />
                <span>{f.title}</span>
              </label>
            ))}
        </div>
      );
      return node.title ? <Panel title={node.title}>{chips}</Panel> : chips;
    }

    case "structure": {
      const summary = <StructureSummary ctx={ctx} />;
      return node.title ? <Panel title={node.title}>{summary}</Panel> : summary;
    }

    case "button":
      return (
        <Btn
          kind={node.action === "compose" ? "primary" : undefined}
          disabled={ctx.composing}
          onClick={() => {
            if (node.action === "compose") void ctx.compose();
            else if (node.action === "export-xlsx") ctx.exportTo("xlsx");
            else if (node.action === "export-csv") ctx.exportTo("csv");
            else if (node.action === "reset") ctx.reset();
          }}
        >
          {node.title || "Button"}
        </Btn>
      );

    case "label":
      return <div style={{ fontWeight: 600, padding: "4px 0" }}>{node.title}</div>;

    case "spacer":
      return <div style={{ height: 12 }} />;

    case "result": {
      const view = (
        <ResultView result={ctx.result} busy={ctx.composing} templates={ctx.templates}
                    onDrilldown={ctx.drilldown} onExport={ctx.exportTo} />
      );
      return node.title ? <Panel title={node.title}>{view}</Panel> : view;
    }

    default:
      return null;
  }
}

/** Стандартная форма — когда отчёт не описал своей. */
function DefaultForm({ ctx }: { ctx: RuntimeContext }) {
  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 8, minHeight: 0 }}>
      {ctx.parameters.length > 0 && (
        <Panel title="Parameters">
          <div className="dcs-grid" style={{ gridTemplateColumns: "repeat(4, minmax(0, 1fr))" }}>
            {ctx.parameters.filter((p) => p.userVisible).map((p) => (
              <Field key={p.name} label={p.title || p.name} hint={p.required ? "required" : undefined}>
                <TextInput
                  value={ctx.settings.dataParameters[p.name] == null
                    ? "" : String(ctx.settings.dataParameters[p.name])}
                  readOnly={p.useRestriction}
                  onChange={(v) => ctx.setParameter(p.name, coerce(v, p.valueType))}
                />
              </Field>
            ))}
          </div>
        </Panel>
      )}

      <Panel title="Filter">
        <FilterBuilder
          group={ctx.settings.filter}
          onChange={(filter) => ctx.setSettings((s) => ({ ...s, filter }))}
          fields={ctx.fields}
          parameters={ctx.parameters}
        />
      </Panel>

      <ResultView result={ctx.result} busy={ctx.composing} templates={ctx.templates}
                  onDrilldown={ctx.drilldown} onExport={ctx.exportTo} />
    </div>
  );
}

function StructureSummary({ ctx }: { ctx: RuntimeContext }) {
  const nodes = ctx.settings.structure;
  if (nodes.length === 0) return <Empty>The report shows the grand total.</Empty>;
  return (
    <ul style={{ margin: 0, paddingLeft: 18 }}>
      {nodes.map((n) => (
        <li key={n.id}>
          {n.title || (n.field
            ? ctx.fields.find((f) => f.id === n.field)?.title ?? n.field
            : "Detail records")}
        </li>
      ))}
    </ul>
  );
}

// ------------------------------------------------------------------ helpers

/**
 * Пользовательские настройки шлются «дельтой»: на сервере они накладываются на
 * настройки отчёта. Отправлять всё дерево целиком нельзя — тогда пустая структура
 * в форме затёрла бы структуру, заданную автором отчёта.
 */
function userDelta(settings: DcsSettings): DcsSettings {
  const delta = clone(settings);
  delta.structure = [];
  delta.conditionalAppearance = [];
  delta.userFields = [];
  return delta;
}

function coerce(v: string, type: string): unknown {
  if (v === "") return null;
  if (type === "number") { const n = Number(v); return Number.isNaN(n) ? v : n; }
  if (type === "boolean") return v === "true" || v === "1";
  return v;
}

function messageOf(e: unknown): string {
  if (e instanceof DcsApiError) return e.message;
  return e instanceof Error ? e.message : String(e);
}
