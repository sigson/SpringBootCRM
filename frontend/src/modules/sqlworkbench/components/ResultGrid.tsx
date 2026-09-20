import type { ResultSetDto } from "../types";

/** Объединённая (ссылочная) колонка: пара физических колонок результата
 *  <alias>_type_id + <alias>_id схлопывается в ОДНУ с заресолвленным значением. */
export interface RefColumnSpec {
  /** Имя колонки id в результате (например, "Контрагент_id"). */
  idCol: string;
  /** Имя колонки дискриминатора типа (например, "Контрагент_type_id"). */
  typeCol: string;
  /** Заголовок объединённой колонки. */
  label: string;
}

export interface ResolvedRefLite { display: string; accessible: boolean }

/**
 * Переиспользуемая панель результата запроса. Облик унифицирован с host
 * (.data-table): sticky-заголовок, единые границы/цвета. Скролл (в т.ч.
 * горизонтальный при большом числе колонок) происходит ВНУТРИ .rdr-grid-wrap —
 * окно не ломается.
 *
 * <p><b>Ссылочный режим.</b> При заданном {@link refColumns} каждая ссылочная пара
 * (<alias>_type_id, <alias>_id) показывается как одна колонка с заресолвленным
 * значением (через {@link resolveRef}); клик открывает объект ({@link onOpenRef}).
 * Без {@code refColumns} — сырые колонки.
 *
 * Если задан {@link onClose}, сверху появляется панель-шапка с краткой сводкой и
 * кнопкой «✕ Закрыть».
 */
export function ResultGrid({
  data, fill = true, onClose,
  refColumns, resolveRef, onOpenRef,
}: {
  data: ResultSetDto | null;
  fill?: boolean;
  onClose?: () => void;
  refColumns?: RefColumnSpec[];
  resolveRef?: (typeId: number, id: string) => ResolvedRefLite | undefined;
  onOpenRef?: (typeId: number, id: string) => void;
}) {
  if (!data) return null;

  // Индексы колонок по имени (без учёта регистра — некоторые СУБД меняют регистр меток).
  const colIndex = new Map<string, number>();
  data.columns.forEach((c, i) => colIndex.set(c.toLowerCase(), i));

  // hidden — индексы колонок-дискриминаторов типа (их не показываем отдельно);
  // refByIdIdx — индекс id-колонки → {спецификация, индекс колонки типа}.
  const hidden = new Set<number>();
  const refByIdIdx = new Map<number, { spec: RefColumnSpec; typeIdx: number }>();
  for (const spec of refColumns ?? []) {
    const idIdx = colIndex.get(spec.idCol.toLowerCase());
    const typeIdx = colIndex.get(spec.typeCol.toLowerCase());
    if (idIdx == null || typeIdx == null) continue;   // колонок нет в этом результате — пропускаем
    hidden.add(typeIdx);
    refByIdIdx.set(idIdx, { spec, typeIdx });
  }

  const visibleCount = data.columns.length - hidden.size;

  const renderRefCell = (typeRaw: unknown, idRaw: unknown) => {
    if (idRaw == null || typeRaw == null) return <span className="null">NULL</span>;
    const typeId = Number(typeRaw);
    const id = String(idRaw);
    if (!Number.isFinite(typeId)) return <span>{id}</span>;
    const r = resolveRef?.(typeId, id);
    const text = r?.display ?? `${typeId}:${id}`;
    const canOpen = !!onOpenRef && r?.accessible !== false;
    if (!canOpen) return <span title={`${typeId}:${id}`}>{text}</span>;
    return (
      <button type="button" className="rdr-reflink" title={`Open · ${typeId}:${id}`}
              onClick={() => onOpenRef!(typeId, id)}>
        {text}
      </button>
    );
  };

  return (
    <div
      className="rdr-result"
      style={fill
        ? { display: "flex", flexDirection: "column", flex: 1, minHeight: 0, minWidth: 0 }
        : { display: "flex", flexDirection: "column", flex: "none", maxHeight: 360, minWidth: 0 }}
    >
      {onClose && (
        <div className="rdr-result__bar">
          <span className="rdr-result__title">Query result</span>
          <span className="hint mono">
            rows: {data.rows.length} · p. {data.page + 1}{data.hasMore ? " (more available)" : ""} · {data.elapsedMs} ms
          </span>
          <span className="rdr-spacer" />
          <button className="btn btn--small" onClick={onClose} title="Close the result panel (reclaim the space)">
            ✕ Close
          </button>
        </div>
      )}
      <div className="rdr-grid-wrap">
        <table className="rdr-grid">
          <thead>
            <tr>
              <th className="rownum">#</th>
              {data.columns.map((c, i) => {
                if (hidden.has(i)) return null;
                const ref = refByIdIdx.get(i);
                if (ref) return <th key={i}>{ref.spec.label}<span className="coltype">reference</span></th>;
                return <th key={i}>{c}<span className="coltype">{data.columnTypes[i]}</span></th>;
              })}
            </tr>
          </thead>
          <tbody>
            {data.rows.map((row, ri) => (
              <tr key={ri}>
                <td className="rownum">{data.page * data.pageSize + ri + 1}</td>
                {row.map((v, ci) => {
                  if (hidden.has(ci)) return null;
                  const ref = refByIdIdx.get(ci);
                  if (ref) return <td key={ci}>{renderRefCell(row[ref.typeIdx], v)}</td>;
                  return <td key={ci} className={v == null ? "null" : ""}>{v == null ? "NULL" : String(v)}</td>;
                })}
              </tr>
            ))}
            {data.rows.length === 0 && (
              <tr><td className="empty" colSpan={visibleCount + 1}>No rows</td></tr>
            )}
          </tbody>
        </table>
        <div className="rdr-grid-foot">
          Rows: {data.rows.length} · page {data.page + 1}
          {data.hasMore ? " (more available)" : ""} · {data.elapsedMs} ms
        </div>
      </div>
    </div>
  );
}
