import { useEffect, useMemo, useRef, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { api } from "../api/client";
import { referencesApi } from "../api/endpoints";
import type { TypeDescriptor } from "../types/api";
import { ListView, type Column, type RowAction } from "./ListView";
import { buildColumnsFromMetadata } from "./buildColumnsFromMetadata";
import { serverPagedSource, type RowSource } from "./rowSource";
import { useOpenTypeEditor } from "../editors/openTypeEditor";
import { getRowActions } from "../editors/registry";
import { useWindowStack, nextWindowId } from "../windows/WindowStack";
import { useDisplayResolver } from "../metadata/DisplayResolver";
import { useHasInsertAccess, useHasUpdateAccess } from "../auth/permissions";
import { PageHead } from "./Common";
import { useToast } from "./Toast";
import { useConfirm } from "./ConfirmDialog";
import { useApiErrorHandler } from "./useApiErrorHandler";

type Row = Record<string, unknown>;

/**
 * <h2>Універсальне подання списку об'єкта БД (агрегата), зібране з метаданих.</h2>
 *
 * <p>Це фінальна «уніфікована таблиця» розділу — єдиний {@link ListView} + {@link RowSource}.
 * Жодних типоспецифічних колонок: рядки тягнуться зі стандартного CRUD-ендпоінта типу
 * ({@code GET {apiBase}}), а колонки будуються виключно з відомо-типізованих реквізитів
 * ({@code TypeDescriptor.fields}) через {@link buildColumnsFromMetadata} — зі стандартними
 * елементами керування під кожний {@code FieldKind} (текст/число/булеве/дата/посилання),
 * резолвом REF-колонок через {@code DisplayResolver} і прихованими audit-колонками.
 *
 * <p>Відкриття/створення/редагування — через {@link useOpenTypeEditor} (модально, без URL):
 * якщо для типу зареєстровано власний редактор — рендериться він, інакше generic
 * field-форма з метаданих. Видалення — уніфіковано {@code DELETE {apiBase}/{id}}.
 *
 * <p>Якщо подання типу принципово нестандартне (календар, матриця норм) — для нього
 * реєструється {@code registerListView} і {@code ObjectListPage} віддає bespoke-сторінку
 * замість цього компонента.
 */
export function ObjectList({ td }: { td: TypeDescriptor }) {
  const resolver = useDisplayResolver();
  const openEditor = useOpenTypeEditor();
  const hasInsert = useHasInsertAccess();
  const hasUpdate = useHasUpdateAccess();
  const handleApiError = useApiErrorHandler();
  const toast = useToast();
  const confirmDialog = useConfirm();
  const { open: openWindow, closeById: closeWindowById } = useWindowStack();

  const [version, setVersion] = useState(0);
  const reload = () => setVersion(v => v + 1);

  // Відкриття елемента (або /new) саме з цього вікна списку відображається в URL
  // як /o/{slug}/{id|new}: посилання можна скопіювати й відкрити в іншій вкладці —
  // там відкриється список цього розділу та редактор саме цього запису. Дрилл-даун
  // глибше (через ссилкові пікери у Ролі/Інтерфейс тощо) йде окремим модальним
  // шляхом і URL не зачіпає.
  const navigate = useNavigate();
  const { recordId } = useParams<{ recordId?: string }>();
  const listPath = `/o/${td.slug}`;
  // Для якого recordId уже відкрито вікно (анти-дубль, зокрема StrictMode).
  const openedForRef = useRef<string | null>(null);

  /** Відкрити редактор «зі списку» — через URL (а не напряму модально). */
  const openFromList = (id: string | null) => {
    navigate(`${listPath}/${id ?? "new"}`);
  };

  useEffect(() => {
    if (!recordId) { openedForRef.current = null; return; }
    if (openedForRef.current === recordId) return;  // вже відкрито для цього id
    openedForRef.current = recordId;
    const id = recordId === "new" ? null : recordId;
    openEditor({
      typeId: td.typeId,
      id,
      onSaved: reload,
      onClosed: () => {
        openedForRef.current = null;
        // Прибираємо запис з адресного рядка (повертаємось до списку розділу),
        // якщо ми все ще на цьому записі.
        navigate(listPath);
      },
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [recordId, td.typeId]);

  const columns = useMemo<Column<Row>[]>(() =>
    buildColumnsFromMetadata<Row>(td, {
      resolveRef: (typeId, id) => resolver.displayOf(typeId, id),
    }),
    [td, resolver],
  );

  // Поточна keyset-колонка (code/name) — оновлюється у fetchPage за активним
  // сортуванням і читається keyOf'ом. null → keyset не застосовний (offset).
  const keysetColRef = useRef<string | null>(null);

  // Серверна пагінація: ListView формує повний RowQuery (фільтри/пошук/сортування)
  // і передає у fetchPage — бекенд застосовує все на рівні SQL і повертає лише сторінку.
  const dataSource = useMemo<RowSource<Row>>(() =>
    serverPagedSource<Row>({
      fetchPage: (page, size, query, opts) => {
        // Визначаємо keyset-придатність ПОТОЧНОГО сорту: рівно один ключ по code/name.
        const sortList = (query.sorts && query.sorts.length > 0)
          ? query.sorts
          : (query.sort ? [query.sort] : []);
        const single = sortList.length === 1 ? sortList[0] : null;
        const eligible = single && (single.columnId === "code" || single.columnId === "name");
        // Дефолт бекенду — сорт за code; коли сорт не заданий, keyset теж по code.
        keysetColRef.current = eligible ? single!.columnId
          : (sortList.length === 0 ? "code" : null);
        return referencesApi.rowsPaged(td.slug, page, size, {
          search: query.search,
          columnFilters: query.columnFilters,
          advanced: query.advanced,
          sort: query.sort,
          sorts: query.sorts,
          withCount: opts?.withCount,
          afterValue: opts?.afterValue,
          afterId: opts?.afterId,
        }).then(r => ({ content: r.content as Row[], total: r.total }));
      },
      pageSize: 100,
      version,
      // keyset-ключ рядка: значення поточної keyset-колонки + id. null коли неpridatно.
      keyOf: (row) => {
        const col = keysetColRef.current;
        if (!col) return null;
        const id = row.id == null ? "" : String(row.id);
        if (!id) return null;
        const raw = (row as Record<string, unknown>)[col];
        return { value: raw == null ? null : String(raw), id };
      },
    }),
    [td.slug, version],
  );

  const rowIdOf = (r: Row) => String(r.id ?? "");

  async function onDelete(row: Row) {
    const id = rowIdOf(row);
    const yes = await confirmDialog({
      title: "Delete record?",
      message: "The record will be deleted. This cannot be undone.",
      confirmLabel: "Delete",
      kind: "danger",
    });
    if (!yes) return;
    try {
      await api.delete(`${td.apiBase}/${id}`);
      reload();
      toast.success("Record deleted");
    } catch (e) {
      handleApiError(e);
    }
  }

  const rowActions: RowAction<Row>[] = [
    {
      label: "Open", kind: "primary",
      onClick: row => openFromList(rowIdOf(row)),
    },
  ];

  // Дії, зареєстровані типом (див. {@code registerRowAction}). Ідуть одразу за
  // «Відкрити»: для звітів головна дія — «Сформувати», а не редагування схеми.
  for (const extra of getRowActions(td.typeId)) {
    rowActions.push({
      label: extra.label,
      icon: extra.icon,
      kind: extra.kind,
      onClick: row => extra.onClick(rowIdOf(row), row as Record<string, unknown>, {
        openWindow: ({ title, width, content }) => {
          const winId = nextWindowId();
          openWindow({
            id: winId,
            title,
            width: width ?? "wide",
            content: content(() => closeWindowById(winId)),
          });
        },
        reload,
      }),
    });
  }

  if (hasUpdate(td.typeId)) {
    rowActions.push({
      label: "Delete", icon: "🗑", kind: "danger",
      onClick: row => void onDelete(row),
    });
  }

  // Кнопка створення — за наявності WRITE_INSERT (admin завжди має через bypass).
  // {@code userCreatable} тут не використовується як блокер: це лише підказка
  // «звичайний користувач може створювати», а право створення вирішує grant.
  const toolbar = hasInsert(td.typeId) ? (
    <button
      className="btn btn--primary"
      onClick={() => openFromList(null)}
    >
      ＋ Add
    </button>
  ) : undefined;

  return (
    <main className="page">
      <PageHead title={td.pluralLabel} subtitle={td.singularLabel} />
      <ListView<Row>
        persistKey={`obj-${td.slug}`}
        dataSource={dataSource}
        enableModeToggle
        displayMode="scroll"
        columns={columns}
        rowId={rowIdOf}
        rowActions={rowActions}
        onRefresh={reload}
        onRowDoubleClick={row => openFromList(rowIdOf(row))}
        emptyText="No records"
        toolbar={toolbar}
      />
    </main>
  );
}
