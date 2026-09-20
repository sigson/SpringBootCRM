import { useEffect, useState } from "react";
import { useSpringBootCrm } from "../proxy/WorkbenchContext";
import { Modal } from "./builderUi";
import {
  ReferenceGraph, STANDARD_LOOKUP_COLUMNS, SSYLKA_LABEL, extendPath, refFromGraphField,
  refFromOutputRequisite,
  type GraphType, type MergedRequisite, type OutputRequisite, type RefFieldRef,
  type RefLeaf, type RefPath, type SourceSchema,
} from "../querymodel/referenceModel";

let _graphPromise: Promise<ReferenceGraph> | null = null;

export function useReferenceGraph(): { graph: ReferenceGraph | null; loading: boolean; error: string | null } {
  const { client } = useSpringBootCrm();
  const [state, setState] = useState<{ graph: ReferenceGraph | null; loading: boolean; error: string | null }>(
    { graph: null, loading: true, error: null });
  useEffect(() => {
    let alive = true;
    if (!_graphPromise) {
      _graphPromise = client.metadataGraph().then((r) => new ReferenceGraph(r.types));
    }
    _graphPromise
      .then((g) => { if (alive) setState({ graph: g, loading: false, error: null }); })
      .catch((e: unknown) => {
        _graphPromise = null;
        if (alive) setState({ graph: null, loading: false, error: (e as Error)?.message ?? String(e) });
      });
    return () => { alive = false; };
  }, [client]);
  return state;
}

export interface RequisitePick { path: RefPath; leaf: RefLeaf; label: string; }

// Глубина реальных дереференсов (cast'ов) — ограничивает длину цепочки джойнов.
const MAX_DEPTH = 6;
// Глубина рекурсии «Ссылка.Ссылка…» — идентичность, джойнов не добавляет, но
// бесконечную UI-рекурсию ограничиваем разумно.
const MAX_REF_DEPTH = 6;

interface NodeCtx {
  graph: ReferenceGraph;
  onPick: (p: RequisitePick) => void;
  depth: number;
  refDepth: number;
  visited: number[];    // typeId-цепочка для защиты от cast-циклов
  /**
   * Путь реквизита (метки через точку) внутри источника, на котором пикер должен
   * открыться развёрнутым. Узел, через который этот путь проходит, авто-раскрывается.
   */
  autoOpen?: string;
}

/** Лежит ли целевой путь на узле с полной меткой {@code full} (точное совпадение/префикс). */
function onPath(auto: string | undefined, full: string): boolean {
  return !!auto && (auto === full || auto.startsWith(full + "."));
}

// Отступ строки фиксированный и небольшой; визуальную иерархию даёт <Nested>
// (рамка-направляющая + margin), а не растущий paddingLeft.
const indentStyle = (_d: number) => ({ paddingLeft: 6 });

/**
 * Контейнер вложенных узлов: визуально выделяет уровень иерархии — отступ слева +
 * вертикальная направляющая линия. Делает «спрятанные за ссылкой» реквизиты явно
 * вложенными, а не плоским сплошным списком.
 */
function Nested({ children }: { children: React.ReactNode }) {
  return (
    <div style={{
      marginLeft: 14,
      paddingLeft: 6,
      borderLeft: "1px solid var(--border, #d4d4d8)",
    }}>
      {children}
    </div>
  );
}

function ScalarLeaf({ indent, label, hint, onClick }: {
  indent: number; label: string; hint?: string; onClick: () => void;
}) {
  return (
    <button type="button" className="rdr-pick" style={{ ...indentStyle(indent), textAlign: "left", display: "block", width: "100%" }}
            onClick={onClick} title={`Add attribute: ${label}`}>
      <span className="rdr-pick__ico">＋</span>{label}
      {hint && <span className="hint mono">&nbsp;· {hint}</span>}
    </button>
  );
}

/**
 * Узел «Ссылка» — само-реквизит ссылочного объекта.
 *   • клик по подписи → ВЫБРАТЬ (вывести удержанную ссылку);
 *   • ▸/▾ → раскрыть рекурсию (Ссылка.<что-угодно>, тот же объект).
 */
function SsylkaNode({ indent, label, onSelect, renderChildren, defaultOpen }: {
  indent: number; label: string; onSelect: () => void;
  renderChildren: (() => React.ReactNode) | null;
  defaultOpen?: boolean;
}) {
  const [open, setOpen] = useState(!!defaultOpen);
  return (
    <div>
      <div className="rdr-pick" style={{ ...indentStyle(indent), display: "flex", gap: 4, alignItems: "center" }}>
        {renderChildren
          ? <span className="rdr-pick__ico" style={{ cursor: "pointer" }} onClick={() => setOpen((o) => !o)}>{open ? "▾" : "▸"}</span>
          : <span className="rdr-pick__ico">·</span>}
        <span style={{ flex: 1, cursor: "pointer", fontWeight: 600 }} onClick={onSelect}
              title="Choose «Reference» (keep the reference as a field)">🔗 {label}</span>
      </div>
      {open && renderChildren && <Nested>{renderChildren()}</Nested>}
    </div>
  );
}

// Реквизиты МАТЕРИАЛИЗОВАННОГО объекта (корень или после cast).

function ObjectRequisites({
  ctx, typeId, path, labelPrefix, showSelfRef, selfRefPath,
}: {
  ctx: NodeCtx; typeId: number; path: RefPath; labelPrefix: string; showSelfRef: boolean;
  // Путь для само-«Ссылка» объекта. Если объект достигнут cast'ом, его «Ссылка» =
  // удержанная ссылка-родитель без cast-join'а (selfRefPath — путь до field-шага).
  // Если не задан — совпадает с path (корневой объект).
  selfRefPath?: RefPath;
}) {
  const type = ctx.graph.type(typeId);
  if (!type) return <div className="off" style={indentStyle(ctx.depth)}>type {typeId} unknown</div>;
  const srPath = selfRefPath ?? path;

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 1 }}>
      {showSelfRef && (
        <SsylkaNode
          indent={ctx.depth}
          label={SSYLKA_LABEL}
          defaultOpen={onPath(ctx.autoOpen, labelPrefix + SSYLKA_LABEL)}
          onSelect={() => ctx.onPick({
            path: srPath, leaf: { kind: "reference", label: labelPrefix + SSYLKA_LABEL },
            label: labelPrefix + SSYLKA_LABEL,
          })}
          renderChildren={ctx.refDepth < MAX_REF_DEPTH ? () => (
            // Ссылка концертного объекта = тот же объект → рекурсивно те же реквизиты
            // (с ростом отступа). Само-Ссылка глубже держит тот же selfRefPath.
            <ObjectRequisites
              ctx={{ ...ctx, refDepth: ctx.refDepth + 1, depth: ctx.depth + 1 }}
              typeId={typeId} path={path}
              labelPrefix={`${labelPrefix}${SSYLKA_LABEL}.`} showSelfRef selfRefPath={srPath} />
          ) : null}
        />
      )}

      {type.fields.filter((f) => f.kind === "SCALAR").map((f) => (
        <ScalarLeaf key={f.name} indent={ctx.depth} label={f.label} hint={f.column ?? f.name}
          onClick={() => ctx.onPick({
            path, leaf: { kind: "scalar", column: f.column ?? f.name, label: f.label },
            label: labelPrefix + f.label,
          })} />
      ))}

      {type.fields.filter((f) => f.kind === "REF").map((f) => (
        <RefFieldNode key={f.name} ctx={ctx} field={refFromGraphField(f)} path={path} labelPrefix={labelPrefix} />
      ))}
    </div>
  );
}

// ---- Узел ссылочного реквизита (держим указатель; без join до дереференса) --
// Работает над любым ссылочным реквизитом — графа или производного источника —
// через единый RefFieldRef.

function RefFieldNode({
  ctx, field, path, labelPrefix,
}: {
  ctx: NodeCtx; field: RefFieldRef; path: RefPath; labelPrefix: string;
}) {
  const fullLabel = `${labelPrefix}${field.label}`;
  const [open, setOpen] = useState(() => onPath(ctx.autoOpen, fullLabel));
  const isAny = !!field.anyReference;
  const isUnion = !isAny && field.refTypeIds.length > 1;
  const fieldPath = extendPath(path, { kind: "field", field });
  const myPrefix = `${labelPrefix}${field.label}.`;

  // Прямой выбор ссылочного реквизита как поля результата (leaf=reference): две
  // колонки type_id+id, без join.
  const pickThisRef = () => ctx.onPick({
    path: fieldPath,
    leaf: { kind: "reference", label: labelPrefix + field.label },
    label: labelPrefix + field.label,
  });

  return (
    <div>
      <div className="rdr-pick" style={{ ...indentStyle(ctx.depth), display: "flex", gap: 4, alignItems: "center", fontWeight: 600 }}>
        <span className="rdr-pick__ico" style={{ cursor: "pointer" }} onClick={() => setOpen((o) => !o)}>{open ? "▾" : "▸"}</span>
        {}
        <span style={{ flex: 1, cursor: "pointer" }} onClick={pickThisRef}
              title={`Add reference «${field.label}» into the result (as a reference attribute)`}>
          <span className="rdr-pick__ico">＋</span>🔗 {field.label}
          <span className="hint">&nbsp;· {isAny ? "any type" : isUnion ? `union(${field.refTypeIds.length})` : "reference"}</span>
        </span>
      </div>

      {open && (
        <Nested>
          {/* Разворот ссылочного реквизита = дерево реквизитов того, на что он
              указывает. Внутри — собственная «Ссылка» (рекурсия .Ссылка.Ссылка…
              как вложенное дерево). */}
          <RefDeref
            ctx={{ ...ctx, depth: ctx.depth + 1 }}
            field={field} fieldPath={fieldPath} myPrefix={myPrefix} />
        </Nested>
      )}
    </div>
  );
}

/**
 * Дерево реквизитов того, НА ЧТО указывает удерживаемая ссылка.
 * Моно → реквизиты целевого объекта (с его «Ссылка», дающей рекурсию вглубь).
 * Union → объединённый набор (свёртка по имени) + «Ссылка» (рекурсия) + раздел
 * «по конкретному типу».
 */
function RefDeref({
  ctx, field, fieldPath, myPrefix,
}: {
  ctx: NodeCtx; field: RefFieldRef; fieldPath: RefPath; myPrefix: string;
}) {
  const isAny = !!field.anyReference;
  const isUnion = !isAny && field.refTypeIds.length > 1;
  const canDeeper = ctx.depth <= MAX_DEPTH;

  // Any-reference (маркер AnyReference): ссылка на ЛЮБОЙ объект БД. Общих для всех
  // объектов реквизитов гарантированно нет — поэтому НЕ показываем объединённый
  // набор/стандартные lookup-колонки, а разворачиваем только в тип-иерархию:
  // «🔗 Ссылка» (удержать полиморфную ссылку) + выбор конкретного типа (cast).
  if (isAny) {
    return (
      <div style={{ display: "flex", flexDirection: "column", gap: 1 }}>
        <SsylkaNode
          indent={ctx.depth}
          label={SSYLKA_LABEL}
          defaultOpen={onPath(ctx.autoOpen, myPrefix + SSYLKA_LABEL)}
          onSelect={() => ctx.onPick({
            path: fieldPath, leaf: { kind: "reference", label: myPrefix + SSYLKA_LABEL },
            label: myPrefix + SSYLKA_LABEL,
          })}
          renderChildren={ctx.refDepth < MAX_REF_DEPTH ? () => (
            <RefDeref
              ctx={{ ...ctx, refDepth: ctx.refDepth + 1, depth: ctx.depth + 1 }}
              field={field} fieldPath={fieldPath} myPrefix={`${myPrefix}${SSYLKA_LABEL}.`} />
          ) : null}
        />
        <div className="hint" style={{ ...indentStyle(ctx.depth), fontStyle: "italic" }}>
          a reference to any type - choose a specific type ({field.refTypeIds.length}):
        </div>
        {canDeeper ? (
          field.refTypeIds.map((tid) => {
            const t = ctx.graph.type(tid);
            if (!t) return null;
            return (
              <CastVariant key={tid} ctx={{ ...ctx, depth: ctx.depth + 1 }} type={t}
                           fieldPath={fieldPath} myPrefix={myPrefix} canDeeper={canDeeper} />
            );
          })
        ) : (
          <div className="off" style={indentStyle(ctx.depth)}>…cannot go deeper</div>
        )}
      </div>
    );
  }

  if (!isUnion) {
    const t = ctx.graph.type(field.refTypeIds[0]);
    if (!t) return <div className="off" style={indentStyle(ctx.depth)}>the target type is unknown</div>;
    if (ctx.visited.includes(t.typeId)) {
      return <div className="off" style={indentStyle(ctx.depth)}>↺ cycle ({t.singularLabel}) — further only through «Reference»</div>;
    }
    if (!canDeeper) return <div className="off" style={indentStyle(ctx.depth)}>…cannot go deeper</div>;
    // Моно: cast в целевой объект и инлайн его реквизитов, включая собственную
    // «Ссылка» (showSelfRef=true) — это даёт рекурсию .Ссылка.Ссылка…
    const castPath = extendPath(fieldPath, { kind: "cast", typeId: t.typeId, table: t.table, idColumn: t.idColumn });
    return (
      <ObjectRequisites
        ctx={{ ...ctx, visited: [...ctx.visited, t.typeId] }}
        typeId={t.typeId} path={castPath} labelPrefix={myPrefix} showSelfRef
        selfRefPath={fieldPath} />
    );
  }

  // Union: объединённый набор реквизитов (свёртка по имени). Общий для всех типов —
  // универсален; частичный — тоже доступен. Деление по типам — визуальный раздел ниже.
  const merged = ctx.graph.mergedUnionRequisites(field.refTypeIds);
  const scalars = merged.filter((m) => m.kind === "SCALAR");
  const refs = merged.filter((m) => m.kind === "REF");
  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 1 }}>
      {/* «Ссылка» составной ссылки (1С): держит ту же union-ссылку; рекурсия
          .Ссылка.Ссылка… раскрывает тот же объединённый набор глубже (отступ растёт). */}
      <SsylkaNode
        indent={ctx.depth}
        label={SSYLKA_LABEL}
        defaultOpen={onPath(ctx.autoOpen, myPrefix + SSYLKA_LABEL)}
        onSelect={() => ctx.onPick({
          path: fieldPath, leaf: { kind: "reference", label: myPrefix + SSYLKA_LABEL },
          label: myPrefix + SSYLKA_LABEL,
        })}
        renderChildren={ctx.refDepth < MAX_REF_DEPTH ? () => (
          <RefDeref
            ctx={{ ...ctx, refDepth: ctx.refDepth + 1, depth: ctx.depth + 1 }}
            field={field} fieldPath={fieldPath} myPrefix={`${myPrefix}${SSYLKA_LABEL}.`} />
        ) : null}
      />
      <div className="hint" style={{ ...indentStyle(ctx.depth), fontStyle: "italic" }}>
        attributes of a composite reference (union {field.refTypeIds.length} types):
      </div>
      {/* Универсальные стандартные через reference_lookup — если все типы это поддерживают. */}
      {field.unionViewName && (
        STANDARD_LOOKUP_COLUMNS.map((c) => (
          <ScalarLeaf key={"lk_" + c.column} indent={ctx.depth} label={c.label} hint={`lookup.${c.column}`}
            onClick={() => ctx.onPick({
              path: fieldPath,
              leaf: { kind: "lookupCol", column: c.column, label: c.label, viewName: field.unionViewName! },
              label: myPrefix + c.label,
            })} />
        ))
      )}
      {/* Объединённые скалярные реквизиты — полиморфный pull (CASE по типу). */}
      {scalars.map((m) => (
        <ScalarLeaf key={"m_" + m.name} indent={ctx.depth}
          label={m.label + (m.universal ? "" : " ◐")}
          hint={m.universal ? "all types" : `types: ${m.presentIn.join(", ")}`}
          onClick={() => ctx.onPick({
            path: fieldPath,
            leaf: { kind: "mergedScalar", merged: m, label: m.label },
            label: myPrefix + m.label,
          })} />
      ))}
      {/* Объединённые ССЫЛОЧНЫЕ реквизиты. Ссылочный реквизит конкретного типа
          физически лежит в ТАБЛИЦЕ ЭТОГО ТИПА, а не на union-якоре — поэтому к
          нему нельзя обратиться напрямую от union'а: нужно сперва спуститься
          (cast) в тип-владелец, и лишь там это обычная ссылка. Если реквизит
          есть в нескольких типах — показываем по типу-владельцу. */}
      {refs.map((m) => (
        <MergedRefNode key={"mr_" + m.name} ctx={{ ...ctx, depth: ctx.depth }}
          merged={m} fieldPath={fieldPath} myPrefix={myPrefix} canDeeper={canDeeper} />
      ))}
      {}
      {canDeeper && (
        <details style={{ paddingLeft: 6 }}
                 open={field.refTypeIds.some((tid) => {
                   const t = ctx.graph.type(tid);
                   return !!t && onPath(ctx.autoOpen, `${myPrefix}(${t.singularLabel})`);
                 })}>
          <summary className="hint" style={{ cursor: "pointer" }}>show by a specific type…</summary>
          <Nested>
            {field.refTypeIds.map((tid) => {
              const t = ctx.graph.type(tid);
              if (!t) return null;
              return (
                <CastVariant key={tid} ctx={{ ...ctx, depth: ctx.depth + 1 }} type={t}
                             fieldPath={fieldPath} myPrefix={myPrefix} canDeeper={canDeeper} />
              );
            })}
          </Nested>
        </details>
      )}
    </div>
  );
}

/**
 * Объединённый ссылочный реквизит union'а. Реквизит физически живёт в таблице
 * конкретного типа-владельца, поэтому навигация в него = cast в этот тип +
 * обычная ссылка этого типа. Если владельцев несколько — раскрываем по каждому.
 */
function MergedRefNode({
  ctx, merged, fieldPath, myPrefix, canDeeper,
}: {
  ctx: NodeCtx; merged: MergedRequisite; fieldPath: RefPath; myPrefix: string; canDeeper: boolean;
}) {
  const [open, setOpen] = useState(() => onPath(ctx.autoOpen, `${myPrefix}${merged.label}`));
  const owners = merged.presentIn;
  const single = owners.length === 1;

  // Прямой выбор ссылочного реквизита union-члена в результат — без проваливания.
  // Для единственного владельца это однозначно: cast в его тип + удержать ссылку.
  // Для нескольких владельцев — оставляем только разворот (нужно выбрать тип).
  const pickDirect = single ? () => {
    const tid = owners[0];
    const t = ctx.graph.type(tid);
    if (!t) return;
    const gf = t.fields.find((f) => f.name === merged.name && f.kind === "REF");
    if (!gf) return;
    const castPath = extendPath(fieldPath, { kind: "cast", typeId: tid, table: t.table, idColumn: t.idColumn });
    const refPath = extendPath(castPath, { kind: "field", field: refFromGraphField(gf) });
    ctx.onPick({ path: refPath, leaf: { kind: "reference", label: myPrefix + merged.label }, label: myPrefix + merged.label });
  } : null;

  return (
    <div>
      <div className="rdr-pick" style={{ ...indentStyle(ctx.depth), display: "flex", gap: 4, alignItems: "center", fontWeight: 600 }}>
        <span className="rdr-pick__ico" style={{ cursor: "pointer" }} onClick={() => setOpen((o) => !o)}>{open ? "▾" : "▸"}</span>
        <span style={{ flex: 1, cursor: pickDirect ? "pointer" : "default" }}
              onClick={pickDirect ?? (() => setOpen((o) => !o))}
              title={pickDirect ? `Add reference «${merged.label}» into the result` : "Expand and choose the owner type"}>
          {pickDirect && <span className="rdr-pick__ico">＋</span>}
          🔗 {merged.label}{!merged.universal && <span className="off"> ◐</span>}
          <span className="hint">&nbsp;· reference{single ? "" : ` · types: ${owners.join(", ")}`}</span>
        </span>
      </div>
      {open && (
        <Nested>
          {owners.map((tid) => {
            const t = ctx.graph.type(tid);
            if (!t) return null;
            const gf = t.fields.find((f) => f.name === merged.name && f.kind === "REF");
            if (!gf) return null;
            // cast в тип-владелец, затем его реальное ссылочное поле как обычно.
            const castPath = extendPath(fieldPath, { kind: "cast", typeId: tid, table: t.table, idColumn: t.idColumn });
            const ownerPrefix = single ? myPrefix : `${myPrefix}(${t.singularLabel}).`;
            const child = (
              <RefFieldNode key={tid} ctx={{ ...ctx, depth: ctx.depth + 1, visited: [...ctx.visited, tid] }}
                field={refFromGraphField(gf)} path={castPath} labelPrefix={ownerPrefix} />
            );
            if (single) return child;
            return (
              <div key={tid}>
                <div className="hint" style={{ fontStyle: "italic", paddingLeft: 6 }}>
                  {t.iconHint ? t.iconHint + " " : ""}{t.singularLabel}:
                </div>
                <Nested>{child}</Nested>
              </div>
            );
          })}
          {!canDeeper && <div className="off" style={{ paddingLeft: 6 }}>…cannot go deeper</div>}
        </Nested>
      )}
    </div>
  );
}

/** Один union-вариант «как <Тип>» → cast → реквизиты концертного типа. */
function CastVariant({
  ctx, type, fieldPath, myPrefix, canDeeper,
}: {
  ctx: NodeCtx; type: GraphType; fieldPath: RefPath; myPrefix: string; canDeeper: boolean;
}) {
  const [open, setOpen] = useState(() => onPath(ctx.autoOpen, `${myPrefix}(${type.singularLabel})`));
  const cycle = ctx.visited.includes(type.typeId) || !canDeeper;
  const castPath = extendPath(fieldPath, { kind: "cast", typeId: type.typeId, table: type.table, idColumn: type.idColumn });
  const childPrefix = `${myPrefix}(${type.singularLabel}).`;
  return (
    <div>
      <button type="button" className="rdr-pick" style={{ ...indentStyle(ctx.depth), textAlign: "left" }}
              disabled={cycle} onClick={() => setOpen((o) => !o)}>
        <span className="rdr-pick__ico">{open ? "▾" : "▸"}</span>
        {type.iconHint ? type.iconHint + " " : ""}{type.singularLabel}{cycle && <span className="off"> ↺</span>}
      </button>
      {open && !cycle && (
        <Nested>
          <ObjectRequisites
            ctx={{ ...ctx, depth: ctx.depth + 1, visited: [...ctx.visited, type.typeId] }}
            typeId={type.typeId} path={castPath} labelPrefix={childPrefix} showSelfRef
            selfRefPath={fieldPath} />
        </Nested>
      )}
    </div>
  );
}

/** Дерево реквизитов одного бизнес-объекта (корень). */
export function RequisiteTree({
  graph, rootTypeId, rootRef, onPick, autoOpen,
}: {
  graph: ReferenceGraph; rootTypeId: number; rootRef: string;
  onPick: (pick: RequisitePick) => void; autoOpen?: string;
}) {
  const rootType = graph.type(rootTypeId);
  const path: RefPath = { rootRef, rootTypeId, rootIdColumn: rootType?.idColumn ?? "id", steps: [] };
  return (
    <ObjectRequisites
      ctx={{ graph, onPick, depth: 0, refDepth: 0, visited: [rootTypeId], autoOpen }}
      typeId={rootTypeId} path={path} labelPrefix="" showSelfRef />
  );
}

/**
 * Дерево реквизитов производного источника (подзапрос/ВТ) над его выходной схемой:
 * скалярные реквизиты — листья; ссылочные — узлы «как у таблиц» («🔗 Ссылка» +
 * дереференс). Корень не имеет собственной «Ссылка», навигация начинается с реквизитов.
 */
export function DerivedRequisiteTree({
  graph, rootRef, schema, onPick, autoOpen,
}: {
  graph: ReferenceGraph; rootRef: string; schema: SourceSchema;
  onPick: (pick: RequisitePick) => void; autoOpen?: string;
}) {
  // rootTypeId/rootIdColumn для корня-произв. источника не используются — нейтральные значения.
  const basePath: RefPath = { rootRef, rootTypeId: -1, rootIdColumn: "id", steps: [] };
  const ctx: NodeCtx = { graph, onPick, depth: 0, refDepth: 0, visited: [], autoOpen };
  if (schema.requisites.length === 0) {
    return <div className="off" style={{ paddingLeft: 8 }}>no output attributes - set fields/aliases in the source</div>;
  }
  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 1 }}>
      {schema.requisites.map((r: OutputRequisite) =>
        r.kind === "scalar" ? (
          <ScalarLeaf key={r.name} indent={0} label={r.label} hint={r.name}
            onClick={() => onPick({
              path: basePath, leaf: { kind: "scalar", column: r.name, label: r.label },
              label: r.label,
            })} />
        ) : (
          <RefFieldNode key={r.name} ctx={ctx} field={refFromOutputRequisite(r)}
                        path={basePath} labelPrefix="" />
        )
      )}
    </div>
  );
}

/**
 * Описание одного доступного источника для «леса реквизитов» (пикеры/fx).
 * Бизнес-объект → дерево по графу; подзапрос/ВТ → дерево по производной схеме.
 */
export interface ForestSource {
  ref: string;
  label: string;
  kind: "object" | "derived";
  typeId?: number;
  schema?: SourceSchema;
  derived?: boolean;           // технологический (авто-связь) — скрывается в ссылочном режиме
}

/** Уже выбранное в «Поля» (по псевдониму) — как готовое выражение/ссылка. */
export interface SelectedFieldRef {
  alias: string;
  label: string;
  expression: string;
  isRef: boolean;
}

/**
 * «Лес реквизитов» для пикеров и fx: каждый источник раскрывается в своё дерево,
 * виртуальный корень «Все реквизиты» — все разом, раздел «Поля запиту» — уже
 * выбранные поля. Derived-источники в лес не попадают.
 */
export function SourcesRequisiteForest({
  graph, sources, selectedFields, onPick, onPickSelected, target,
}: {
  graph: ReferenceGraph;
  sources: ForestSource[];
  selectedFields?: SelectedFieldRef[];
  onPick: (pick: RequisitePick) => void;
  onPickSelected?: (f: SelectedFieldRef) => void;
  /** Открыть пикер развёрнутым на этом реквизите (перевыбор уже заданного значения). */
  target?: { sourceRef: string; pathLabel: string };
}) {
  const visible = sources.filter((s) => !s.derived);
  const [openRef, setOpenRef] = useState<Record<string, boolean>>(
    () => (target ? { [target.sourceRef]: true } : {}));
  const [openAll, setOpenAll] = useState(false);
  const [openSel, setOpenSel] = useState(false);

  // Авто-развёртывание применяем только к источнику цели (его дереву передаём путь).
  const autoOpenFor = (ref: string) => (target && target.sourceRef === ref ? target.pathLabel : undefined);
  const renderSourceTree = (s: ForestSource) =>
    s.kind === "object" && s.typeId != null
      ? <RequisiteTree graph={graph} rootTypeId={s.typeId} rootRef={s.ref} onPick={onPick} autoOpen={autoOpenFor(s.ref)} />
      : s.schema
        ? <DerivedRequisiteTree graph={graph} rootRef={s.ref} schema={s.schema} onPick={onPick} autoOpen={autoOpenFor(s.ref)} />
        : <div className="off" style={{ paddingLeft: 8 }}>no attributes</div>;

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 2 }}>
      {}
      {selectedFields && selectedFields.length > 0 && (
        <div>
          <button type="button" className="rdr-pick" style={{ textAlign: "left", fontWeight: 600 }}
                  onClick={() => setOpenSel((o) => !o)}>
            <span className="rdr-pick__ico">{openSel ? "▾" : "▸"}</span>📋 Query fields
          </button>
          {openSel && (
            <Nested>
              {selectedFields.map((f) => (
                <button key={f.alias} type="button" className="rdr-pick" style={{ textAlign: "left", display: "block", width: "100%" }}
                        onClick={() => onPickSelected?.(f)} title={f.expression}>
                  <span className="rdr-pick__ico">＋</span>{f.isRef ? "🔗 " : ""}{f.label}
                  <span className="hint mono">&nbsp;· {f.alias}</span>
                </button>
              ))}
            </Nested>
          )}
        </div>
      )}

      {}
      {visible.map((s) => (
        <div key={s.ref}>
          <button type="button" className="rdr-pick" style={{ textAlign: "left", fontWeight: 600 }}
                  onClick={() => setOpenRef((o) => ({ ...o, [s.ref]: !o[s.ref] }))}>
            <span className="rdr-pick__ico">{openRef[s.ref] ? "▾" : "▸"}</span>
            {s.kind === "derived" ? "▤ " : "📂 "}{s.label}
            <span className="hint">&nbsp;· {s.ref}</span>
          </button>
          {openRef[s.ref] && <Nested>{renderSourceTree(s)}</Nested>}
        </div>
      ))}

      {}
      {visible.length > 0 && (
        <div>
          <button type="button" className="rdr-pick" style={{ textAlign: "left", fontWeight: 700 }}
                  onClick={() => setOpenAll((o) => !o)}>
            <span className="rdr-pick__ico">{openAll ? "▾" : "▸"}</span>🗂 All attributes
          </button>
          {openAll && (
            <Nested>
              {visible.map((s) => (
                <div key={s.ref}>
                  <div className="hint" style={{ fontStyle: "italic", paddingLeft: 6 }}>{s.label} ({s.ref}):</div>
                  <Nested>{renderSourceTree(s)}</Nested>
                </div>
              ))}
            </Nested>
          )}
        </div>
      )}

      {visible.length === 0 && <div className="off">no data sources</div>}
    </div>
  );
}

/**
 * Модальный выбор реквизита по дереву от заданного корневого источника
 * (для вкладок «Условия» и «Связи»).
 */
export function RequisitePicker({
  graph, sources, selectedFields, onPick, onPickSelected, onClose, target,
}: {
  graph: ReferenceGraph;
  sources: ForestSource[];
  selectedFields?: SelectedFieldRef[];
  onPick: (pick: RequisitePick) => void;
  /** Клік по вже відібраному полю розділу «Поля запиту». */
  onPickSelected?: (f: SelectedFieldRef) => void;
  onClose: () => void;
  target?: { sourceRef: string; pathLabel: string };
}) {
  return (
    <Modal title="Pick an attribute through references" width="wide" onClose={onClose}
           footer={<button className="btn" onClick={onClose}>Close</button>}>
      <div style={{ maxHeight: "55vh", overflow: "auto", border: "1px solid var(--border,#ddd)", borderRadius: 6, padding: 4 }}>
        <SourcesRequisiteForest
          graph={graph} sources={sources} selectedFields={selectedFields} target={target}
          onPick={(p) => { onPick(p); onClose(); }}
          onPickSelected={onPickSelected ? (f) => { onPickSelected(f); onClose(); } : undefined} />
      </div>
      <p className="hint" style={{ marginTop: 8 }}>
        Full functionality of the «Tables» window: walking through references, «🔗 Reference», union-attributes.
        «🗂 All attributes» - all sources together; «📋 Query fields» - already picked fields.
      </p>
    </Modal>
  );
}
