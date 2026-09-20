import type { ResolvedRef, TypeDescriptor } from "../types/api";
import type { ReferenceSource, PickerColumn } from "./filterTypes";
import { referencesApi } from "../api/endpoints";
import { getPicker, pickerToReferenceSource } from "../editors/registry";

/**
 * <h2>Єдине джерело правди для побудови {@link ReferenceSource} за {@code refTypeId}.</h2>
 *
 * <p>Єдина точка диспатчу «користувацький picker-конфіг vs universal fallback»:
 * сюди делегують і {@code useReferenceSource} (inline-пікер RefField /
 * RefAutocompleteInput / ReferencePicker), і filter-picker у діалозі фільтрів.
 *
 * <h3>Логіка вибору джерела:</h3>
 * <ol>
 *   <li>Якщо в {@code editors/registry} зареєстровано користувацький
 *       <b>picker-конфіг</b> (User, AccessRole, …) — беремо його: повноцінні
 *       колонки таблиці + доменний fetch, що повертає повний DTO. Picker стає
 *       візуально <i>тим самим</i> компонентом, що й сторінка списку.</li>
 *   <li>Інакше — fallback на universal {@code referencesApi} з мінімальними
 *       колонками «Код + Найменування» та <b>серверною пагінацією</b>
 *       ({@code fetchPage}). Цього достатньо для будь-яких довідників із простою
 *       семантикою (Coefficient, RepairType, …), а пагінація прибирає
 *       завантаження всього довідника у пам'ять (критично для великих типів).</li>
 * </ol>
 *
 * <p>Тип {@link ReferenceSource} у обох випадках однаковий — компоненти-споживачі
 * нічого не знають про джерело.
 */
export function buildReferenceSource(
  refTypeId: number | null | undefined,
  byTypeId: (id: number) => TypeDescriptor | null,
): ReferenceSource<any> | null {
  if (refTypeId == null) return null;
  const td = byTypeId(refTypeId);
  if (!td) return null;

  // 1) Користувацький picker-конфіг з registry (повна таблиця з метаданих).
  const custom = getPicker(refTypeId);
  if (custom) {
    return pickerToReferenceSource(custom, td);
  }

  // 2) Fallback: universal references API + мінімальні колонки.
  const pickerColumns: PickerColumn<ResolvedRef>[] = [
    {
      id: "code",
      header: "Code",
      width: "120px",
      textOf: r => r.code ?? "",
      // Коди alphanumeric (напр. «ТС000001») → filterType="string".
      filterType: "string",
    },
    {
      id: "name",
      header: "Name",
      textOf: r => r.name ?? r.display,
      filterType: "string",
    },
  ];

  return {
    fetchAll: () => referencesApi.list(td.slug),
    // Справжня серверна пагінація для пікера/автокомпліту: лише потрібна
    // сторінка/збіги, з SQL-фільтрацією. Прибирає завантаження всього довідника
    // у пам'ять (критично для типів на мільйони рядків).
    fetchPage: (page, size, query, opts) =>
      referencesApi.listPaged(td.slug, page, size, {
        search: query.search,
        columnFilters: query.columnFilters,
        advanced: query.advanced,
        sort: query.sort,
        sorts: query.sorts,
        withCount: opts?.withCount,
      }).then(r => ({ content: r.content, total: r.total })),
    getId: r => r.id,
    getDisplay: r => r.display || r.name || r.code || r.id,
    pickerColumns,
    // Lazy-варіант ідентичний — у цьому fallback'у ref-колонок немає, тому
    // контекст з {@code resolveRef} ігнорується. Експортуємо для одноманітності API.
    buildPickerColumns: () => pickerColumns,
    pickerTitle: `Select: ${td.pluralLabel}`,
    persistKey: `ref-${td.slug}`,
    sectionPath: `/o/${td.slug}`,  // реальний маршрут — /o/:slug
  };
}
