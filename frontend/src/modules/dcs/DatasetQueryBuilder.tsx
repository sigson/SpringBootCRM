import { useEffect, useMemo, useState } from "react";
import { WorkbenchClient } from "../sqlworkbench/api/client";
import { WorkbenchContext } from "../sqlworkbench/proxy/WorkbenchContext";
import { StatementEditor } from "../sqlworkbench/components/StatementEditor";
import type { EditorEnv } from "../sqlworkbench/components/builderUi";
import {
  newStatement, statementToSql, type Statement,
} from "../sqlworkbench/querymodel/builderModel";
import { deriveSourceSchema } from "../sqlworkbench/querymodel/referenceModel";
import type { TableInfo, TableMetadata } from "../sqlworkbench/types";
import "../sqlworkbench/styles.css";
import type { DcsClient } from "./api";
import type { DataSetPreview, SchemaParameter } from "./types";
import { Banner, Btn, Empty, Spacer, Toolbar } from "./ui";

/**
 * <h2>Визуальный конструктор запроса набора данных.</h2>
 *
 * <p>Набор данных схемы компоновки — это обычный SELECT, а такой конструктор уже есть
 * в Workbench'е. Поэтому здесь не второй билдер, а <b>тот же самый</b>: компонент
 * {@code StatementEditor} монтируется прямо во вкладку «Наборы данных» вместе со своим
 * окружением. Копия неизбежно разошлась бы с оригиналом — ссылочный режим, соединения,
 * объединения и подзапросы пришлось бы поддерживать дважды.
 *
 * <p>Две вещи делают стыковку точной, а не приблизительной:
 * <ul>
 *   <li>{@code statementToSql} отдаёт текст <b>без подстановки параметров</b> (инлайном
 *       занимается {@code buildExecutableSql}, он здесь не нужен). Поэтому {@code &Имя},
 *       набранное в условии конструктора, доезжает до компоновщика макета и связывается
 *       параметром схемы — синтаксис у обоих один и тот же;</li>
 *   <li>дерево конструктора хранится в самом наборе ({@code dataSet.builder}), а в
 *       упакованную строку уходит только сгенерированный SQL. Строка остаётся читаемой
 *       и исполнимой, а запрос при этом можно открыть и править дальше мышью.</li>
 * </ul>
 *
 * <p>Контекст Workbench'а поднимается локальный: конструктору нужен его {@code client}
 * (список таблиц, колонки, граф ссылочных типов). Права здесь не решаются — {@code can}
 * возвращает {@code true}, потому что источником истины остаётся сервер, который
 * проверяет доступ на каждом запросе.
 */
export interface DatasetQueryBuilderProps {
  /** Имя набора — только для заголовка. */
  dataSetName: string;
  /** Датасорс Workbench'а, из которого берутся таблицы и колонки. */
  dataSourceId: string;
  /** Сохранённое дерево конструктора; {@code null} — начать с пустого запроса. */
  builder: unknown | null;
  /** Текущий SQL набора — показывается, когда дерева нет (SQL писали руками). */
  currentSql: string;
  getToken?: () => string | null | undefined;
  /** Отдаёт наружу и дерево, и сгенерированный из него SQL. */
  onChange: (builder: Statement, sql: string) => void;

  /** Параметры схемы — подсказка «что можно писать через &» и значения для предпросмотра. */
  parameters: SchemaParameter[];
  /** Клиент компоновки: предпросмотр исполняется им, чтобы параметры связались. */
  dcsClient: DcsClient;
  /** Упакованная строка с уже применённым текущим SQL — вход предпросмотра. */
  packedFor: (sql: string) => string;
}

export function DatasetQueryBuilder(props: DatasetQueryBuilderProps) {
  const client = useMemo(
    () => new WorkbenchClient({ baseUrl: "/api/sqlworkbench", getToken: props.getToken }),
    [props.getToken],
  );

  const ctx = useMemo(
    () => ({ client, can: () => true, capabilities: {}, loading: false }),
    [client],
  );

  return (
    <WorkbenchContext.Provider value={ctx}>
      <BuilderBody {...props} client={client} />
    </WorkbenchContext.Provider>
  );
}

function BuilderBody({
  dataSetName, dataSourceId, builder, currentSql, onChange, client,
  parameters, dcsClient, packedFor,
}: DatasetQueryBuilderProps & { client: WorkbenchClient }) {
  const [statement, setStatement] = useState<Statement>(
    () => (builder as Statement | null) ?? newStatement());
  const [tables, setTables] = useState<TableInfo[]>([]);
  const [metaCache, setMetaCache] = useState<Record<string, TableMetadata>>({});
  const [error, setError] = useState<string | null>(null);
  const [preview, setPreview] = useState<DataSetPreview | null>(null);
  const [previewing, setPreviewing] = useState(false);

  // Запрос набора мог быть написан руками — разобрать произвольный SQL конструктор
  // не умеет. Спрашиваем явно, вместо того чтобы молча затереть текст пустым деревом.
  const [confirmed, setConfirmed] = useState(builder != null || !currentSql.trim());

  useEffect(() => {
    if (!dataSourceId) return;
    let alive = true;
    client.tables(dataSourceId)
      .then((t) => { if (alive) setTables(t); })
      .catch((e) => { if (alive) setError(e?.message ?? String(e)); });
    return () => { alive = false; };
  }, [client, dataSourceId]);

  const env: EditorEnv = useMemo(() => ({
    candidates: tables.map((t) => ({
      schema: t.schema ?? undefined, name: t.name, kind: "table" as const,
    })),
    columnsOf: (name) => (metaCache[name]?.columns ?? []).map((c) => c.name),
    ensureMeta: (name, schema) => {
      if (!dataSourceId || metaCache[name]) return;
      client.table(dataSourceId, name, undefined, schema)
        .then((m) => setMetaCache((prev) => ({ ...prev, [name]: m })))
        .catch(() => { /* нет метаданных — редактор просто не подскажет колонки */ });
    },
    // Временных таблиц у набора данных нет: набор — один SELECT, а не пакет
    // операторов. Схему ВТ выводим только для вложенных подзапросов конструктора.
    tempSchema: () => null,
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }), [tables, metaCache, dataSourceId, client]);

  const sql = useMemo(() => statementToSql(statement), [statement]);

  function apply(next: Statement) {
    setStatement(next);
    // Предыдущий предпросмотр относится к предыдущему запросу — показывать его
    // рядом с изменившимся деревом значит врать про то, что вернёт набор.
    setPreview(null);
    onChange(next, statementToSql(next));
  }

  async function runPreview() {
    setPreviewing(true);
    setError(null);
    try {
      const values: Record<string, unknown> = {};
      for (const p of parameters) values[p.name] = p.value ?? null;
      setPreview(await dcsClient.previewDataSet({
        packed: packedFor(sql), dataSet: dataSetName,
        dataSourceId, parameters: values, limit: 20,
      }));
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setPreviewing(false);
    }
  }

  if (!confirmed) {
    return (
      <div style={{ padding: 4 }}>
        <Banner kind="warn">
          The dataset <strong>{dataSetName}</strong> has hand-written SQL. The builder cannot
          parse arbitrary SQL, so it starts from an empty query and the text below will be
          replaced once you change anything.
        </Banner>
        <pre className="dcs-sql">{currentSql}</pre>
        <Toolbar>
          <Btn kind="primary" onClick={() => setConfirmed(true)}>Build from scratch</Btn>
          <Spacer />
          <span className="dcs-muted">Or switch back to «SQL» and keep editing the text.</span>
        </Toolbar>
      </div>
    );
  }

  return (
    <div style={{ display: "flex", flexDirection: "column", minHeight: 0, gap: 6 }}>
      {error && <Banner kind="error" onClose={() => setError(null)}>{error}</Banner>}
      {!dataSourceId && (
        <Banner kind="warn">No data source selected — the builder has no tables to offer.</Banner>
      )}

      {/*
        Стили Workbench'а ограничены префиксом .springbootcrm-root, поэтому конструктор
        монтируется внутрь такого контейнера — иначе он отрендерится без оформления.
      */}
      {parameters.length > 0 && (
        <div className="dcs-chips">
          <span className="dcs-muted" style={{ fontSize: 11 }}>
            Schema parameters — write them as a condition value:
          </span>
          {parameters.map((p) => (
            <span key={p.name} className="dcs-chip" title={p.title ?? p.name}>
              <span className="dcs-mono">&amp;{p.name}</span>
            </span>
          ))}
        </div>
      )}

      <div className="springbootcrm-root" style={{ minHeight: 320, flex: 1 }}>
        <StatementEditor value={statement} onChange={apply} env={env} />
      </div>

      <Toolbar>
        <Btn small onClick={() => void runPreview()} disabled={previewing || !sql.trim()}>
          {previewing ? "Running…" : "▶ Preview data"}
        </Btn>
        <Spacer />
        <span className="dcs-muted" style={{ fontSize: 11 }}>
          runs this dataset alone, with the schema parameters bound
        </span>
      </Toolbar>

      {preview && <PreviewTable preview={preview} onClose={() => setPreview(null)} />}

      <details>
        <summary className="dcs-muted" style={{ cursor: "pointer", fontSize: 11 }}>
          Generated SQL for the dataset
        </summary>
        <pre className="dcs-sql" style={{ marginTop: 4 }}>{sql || "— the query is empty —"}</pre>
      </details>
    </div>
  );
}

/** Небольшая таблица предпросмотра: двадцати строк хватает, чтобы увидеть форму данных. */
function PreviewTable({ preview, onClose }: { preview: DataSetPreview; onClose: () => void }) {
  if (preview.error) return <Banner kind="error" onClose={onClose}>{preview.error}</Banner>;
  if (preview.columns.length === 0) return <Banner kind="warn" onClose={onClose}>The query returned no columns.</Banner>;

  return (
    <div>
      <Toolbar>
        <span className="dcs-muted">
          {preview.rows.length} row(s) · {preview.elapsedMs} ms
        </span>
        <Spacer />
        <Btn small kind="ghost" onClick={onClose}>✕</Btn>
      </Toolbar>
      <div style={{ overflow: "auto", maxHeight: 260 }}>
        <table className="dcs-table">
          <thead>
            <tr>{preview.columns.map((c) => <th key={c}>{c}</th>)}</tr>
          </thead>
          <tbody>
            {preview.rows.length === 0 && (
              <tr><td colSpan={preview.columns.length}><Empty>No rows</Empty></td></tr>
            )}
            {preview.rows.map((row, i) => (
              <tr key={i}>
                {preview.columns.map((c, j) => (
                  <td key={c} className={typeof row[j] === "number" ? "is-number" : undefined}>
                    {row[j] == null ? "" : String(row[j])}
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}

export default DatasetQueryBuilder;
