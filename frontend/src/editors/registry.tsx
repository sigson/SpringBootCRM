import type {
  ComponentType, ForwardRefExoticComponent, RefAttributes,
} from "react";
import { api } from "../api/client";
import type { Column } from "../components/ListView";
import type { ReferenceSource } from "../components/filterTypes";

/**
 * Реєстр редакторів та picker'ів по typeId — єдине джерело правди про те, як
 * виглядає редактор елемента справочника заданого типу та його picker-таблиця
 * (drill-down з RefField модально без зміни URL; picker з повним набором колонок).
 *
 * Модуль чисто статичний: реєстрація через bundle ({@code editors/index.ts}), без
 * Provider'а та side-effect'ів на mount, тож усі реєстрації виконані до першого виклику.
 */

/** Пропси редактора елемента справочника. Навігацією керує викликач, не редактор. */
export interface TypeEditorProps {
  /** UUID існуючого елемента; {@code null} — створення нового. */
  id: string | null;
  /**
   * Заздалегідь отриманий код від {@code /next-code} (prefetch у {@code useOpenTypeEditor}
   * до відкриття вікна — уникає подвійного інкременту від StrictMode double-mount).
   * {@code null}/{@code undefined} — «не довідник» або prefetch провалився (поле «Код» порожнє).
   */
  prefetchedCode?: string | null;
  /** Закрити вікно (без збереження). Викликач вирішує, чи синхронізувати URL. */
  onClose: () => void;
  /** Викликається після успішного збереження. Викликач робить reload+navigate. */
  onSaved?: (createdId?: string) => void;
}

type EditorComponent = ComponentType<TypeEditorProps>;

/**
 * Ширина модального вікна редактора. За замовчуванням {@code "wide"} — саме стільки
 * потрібно field-формі. Конструктори з майстер-деталь розкладкою (схема компоновки
 * звіту) просять {@code "full"}: у 900px три панелі поруч не поміщаються.
 */
export type EditorWidth = "narrow" | "default" | "wide" | "full";

const editorRegistry = new Map<number, EditorComponent>();
const editorWidths = new Map<number, EditorWidth>();

/** Зареєструвати редактор для типу. Викликається з top-level коду модуля. */
export function registerEditor(
  typeId: number, component: EditorComponent, width?: EditorWidth,
): void {
  editorRegistry.set(typeId, component);
  if (width) editorWidths.set(typeId, width);
}

/** Ширина вікна зареєстрованого редактора ({@code "wide"}, якщо не задана). */
export function getEditorWidth(typeId: number): EditorWidth {
  return editorWidths.get(typeId) ?? "wide";
}

/**
 * <h3>Додаткові дії в рядку generic-списку.</h3>
 *
 * <p>Симетрично до {@link registerEditor}: тип може додати в колонку «Дії» власну
 * кнопку, не переозначуючи весь список. Це потрібно там, де в об'єкта є дія,
 * відмінна від «відкрити на редагування»: звіт, наприклад, значно частіше
 * <i>формують</i>, ніж правлять його схему.
 *
 * <p>Обробник отримує id рядка та {@link RowActionContext} з доступом до стеку вікон —
 * щоб відкрити власне подання, не знаючи нічого про пейдж списку.
 */
export interface RowActionContext {
  /** Відкрити модальне вікно; {@code content} отримує колбек закриття. */
  openWindow: (opts: {
    title: string;
    width?: EditorWidth;
    content: (close: () => void) => import("react").ReactNode;
  }) => void;
  /** Перезавантажити список (після дії, що змінила дані). */
  reload: () => void;
}

export interface ExtraRowAction {
  label: string;
  icon?: string;
  kind?: "default" | "danger" | "primary";
  /** {@code row} — сирий рядок списку (мапа полів метаданих). */
  onClick: (id: string, row: Record<string, unknown>, ctx: RowActionContext) => void;
}

const rowActions = new Map<number, ExtraRowAction[]>();

export function registerRowAction(typeId: number, action: ExtraRowAction): void {
  const list = rowActions.get(typeId) ?? [];
  list.push(action);
  rowActions.set(typeId, list);
}

export function getRowActions(typeId: number): ExtraRowAction[] {
  return rowActions.get(typeId) ?? [];
}

/** Отримати редактор для типу (або null, якщо не зареєстровано). */
export function getEditor(typeId: number): EditorComponent | null {
  return editorRegistry.get(typeId) ?? null;
}

// Generic-editor розширення: payload-адаптери та таблична частина.
// Тип лишається на generic {@code GenericAggregateEditor}, але може (а) трансформувати
// тіло create/update під свій REST-контракт і (б) додати owned-колекцію (ТЧ) під формою.

/** Контекст для payload-адаптера. */
export interface PayloadAdaptContext {
  /** {@code null} — створення; інакше — оновлення наявного запису. */
  id: string | null;
  /** Поточні значення форми (як їх зібрав generic-редактор). */
  data: Record<string, unknown>;
  /** Початкові значення (для обчислення «що змінилось»). */
  original: Record<string, unknown> | null;
}

/**
 * Трансформує тіло запиту перед POST/PUT. Повертає об'єкт, який піде на бекенд.
 * Якщо не зареєстровано — generic-редактор шле тіло без змін.
 */
export type PayloadAdapter = (
  body: Record<string, unknown>, ctx: PayloadAdaptContext,
) => Record<string, unknown>;

const payloadAdapters = new Map<number, PayloadAdapter>();

export function registerEditorPayloadAdapter(typeId: number, fn: PayloadAdapter): void {
  payloadAdapters.set(typeId, fn);
}

export function getEditorPayloadAdapter(typeId: number): PayloadAdapter | null {
  return payloadAdapters.get(typeId) ?? null;
}

/** Імперативний handle табличної частини: generic-редактор викликає {@link flush}
 * після збереження власника, передаючи валідний {@code ownerId}. */
export interface TabularPartHandle {
  hasPendingChanges: () => boolean;
  flush: (ownerId: string) => Promise<void>;
}

/** Реєстрація рендера ТЧ для типу-власника ({@code ownerTypeId} — typeId агрегата,
 * у формі якого показується ТЧ). Компонент приймає {@code ownerId} і ref на handle. */
export type TabularPartComponent =
  ForwardRefExoticComponent<
    { ownerId: string | null } & RefAttributes<TabularPartHandle>
  >;

const tabularParts = new Map<number, TabularPartComponent[]>();

export function registerTabularPart(ownerTypeId: number, component: TabularPartComponent): void {
  const list = tabularParts.get(ownerTypeId) ?? [];
  list.push(component);
  tabularParts.set(ownerTypeId, list);
}

export function getTabularParts(ownerTypeId: number): TabularPartComponent[] {
  return tabularParts.get(ownerTypeId) ?? [];
}

/**
 * Конфіг picker'а для типу. Колонки — це колонки основної таблиці довідника, тож
 * picker виглядає ідентично сторінці-розділу. Колонки лазливі: будуються з
 * {@link TypeDescriptor} у момент рендеру (метадані можуть бути ще недоступні на
 * момент {@code initEditors()}).
 */
export interface PickerConfig<I> {
  /** Завантажити всі записи. Необов'язково: без нього picker тягне список через
   * {@code GET td.apiBase} (як ObjectList). Перевизначати — лише для іншого джерела. */
  fetchAll?: () => Promise<I[]>;
  /** Витягти UUID з запису. */
  getId: (item: I) => string;
  /** Display-текст (для chips та inline-display'у). */
  getDisplay: (item: I) => string;
  /**
   * Колонки picker-таблиці — будуються з метаданих у момент рендеру. {@code ctx}
   * дає {@code resolveRef(refTypeId, id) → display|null}, щоб ref-комірки показували
   * читабельний текст замість UUID; якщо колонка від нього не залежить — ігнорується.
   */
  columns: (td: import("../types/api").TypeDescriptor,
            ctx: import("../components/filterTypes").PickerColumnContext) => Column<I>[];
  /** Заголовок picker-вікна. */
  pickerTitle?: string;
  /** Ключ для localStorage persist (як у звичайному ListView). */
  persistKey?: string;
}

const pickerRegistry = new Map<number, PickerConfig<unknown>>();

/** Зареєструвати picker-конфіг для типу. */
export function registerPicker<I>(typeId: number, cfg: PickerConfig<I>): void {
  pickerRegistry.set(typeId, cfg as PickerConfig<unknown>);
}

/** Отримати picker-конфіг (або null, якщо не зареєстровано — буде fallback). */
export function getPicker<I = unknown>(typeId: number): PickerConfig<I> | null {
  return (pickerRegistry.get(typeId) as PickerConfig<I> | undefined) ?? null;
}

/**
 * Перетворює {@link PickerConfig} на {@link ReferenceSource} для RefAutocompleteInput
 * та ReferencePicker. Колонки будуються з метаданих lazy; без метаданих — порожній набір.
 */
export function pickerToReferenceSource<I>(
  cfg: PickerConfig<I>,
  td: import("../types/api").TypeDescriptor,
): ReferenceSource<I> {
  // Eager-набір з NO-OP resolver'ом — fallback, якщо picker рендериться без
  // DisplayResolverProvider'а (ref-комірки тоді покажуть сирий UUID).
  const noopCtx = { resolveRef: () => null };
  const eagerColumns = cfg.columns(td, noopCtx);
  return {
    // За замовчуванням — generic GET по apiBase типу (як в ObjectList).
    fetchAll: cfg.fetchAll ?? (() => api.get<I[]>(td.apiBase)),
    getId: cfg.getId,
    getDisplay: cfg.getDisplay,
    pickerColumns: eagerColumns.map(toPickerColumn),
    // Lazy-побудова з реальним resolveRef від DisplayResolverProvider'а —
    // ref-комірки picker'а стають читабельними замість UUID.
    buildPickerColumns: (ctx) => cfg.columns(td, ctx).map(toPickerColumn),
    pickerTitle: cfg.pickerTitle,
    persistKey: cfg.persistKey ?? `picker-type-${td.typeId}`,
    // Фактичний маршрут розділу — /o/:slug (єдиний generic route у App.tsx).
    sectionPath: `/o/${td.slug}`,
  };
}

/** Конвертація {@code Column<I>} (ListView) → {@code PickerColumn<I>} (picker). */
function toPickerColumn<I>(c: Column<I>): import("../components/filterTypes").PickerColumn<I> {
  return {
    id: c.id,
    header: c.header,
    width: c.width,
    align: c.align,
    filterType: c.filterType,
    enumOptions: c.enumOptions,
    refSource: c.refSource,
    textOf: c.textOf,
    idOf: c.idOf,
    render: c.render,
  };
}
