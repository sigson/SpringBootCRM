import { type ReactNode } from "react";
import type { FieldDescriptor, FieldKind, TypeDescriptor } from "../types/api";
import type { Column } from "./ListView";
import type { ColumnFilterType } from "./filterTypes";

/**
 * Metadata-driven column builder. Витягує колонки з {@code TypeDescriptor.fields},
 * які backend віддає за {@code @UiField}-аннотаціями — нове поле зʼявляється у
 * таблицях автоматично, без ручного синхронізування.
 *
 * Для конкретних полів можна перевизначити {@link Column#render} (вигляд клітинки)
 * або {@link Column#textOf} (текст для пошуку/sort'у). Сторінки на кшталт
 * {@code UsersPage} лишаються пейдж-специфічними, але беруть колонки звідси.
 */

export interface BuildColumnsOptions<T> {
  /** Перевизначення рендеру полів (ключ — {@code field.name}). Без нього —
   * дефолтний текст через {@link textForField}. */
  customRender?: Record<string, (row: T) => ReactNode>;

  /** Перевизначення {@code textOf}-екстрактора (напр. дата в локалі, ціна з валютою). */
  customTextOf?: Record<string, (row: T) => string>;

  /** Поля, які виключити з таблиці (наприклад, {@code passwordHash}). */
  excludeFields?: string[];

  /** Додаткові колонки поза метаданими (напр. «Роль» — агрегат ID, що резолвиться
   * через DisplayResolver). */
  extraColumns?: Column<T>[];

  /** Резолвер display'у для REF-колонок: показують читабельний рядок замість UUID
   * і використовують його для пошуку/sort'у. */
  resolveRef?: (refTypeId: number, id: string) => string | null;

  /** Втручання у згенеровану колонку (width/align/alwaysVisible). {@code null} →
   * колонку виключити (альтернатива {@code excludeFields}). */
  customizeColumn?: (column: Column<T>, field: FieldDescriptor) => Column<T> | null;
}

/**
 * Будує масив колонок для {@link Column}-таблиці з метаданих типу. Поля з
 * {@code hiddenInTable=true} стають прихованими за замовчуванням.
 */
export function buildColumnsFromMetadata<T extends Record<string, unknown>>(
  td: TypeDescriptor,
  options: BuildColumnsOptions<T> = {},
): Column<T>[] {
  const result: Column<T>[] = [];
  const {
    customRender = {},
    customTextOf = {},
    excludeFields = [],
    extraColumns = [],
    resolveRef,
    customizeColumn,
  } = options;

  for (const f of td.fields) {
    if (excludeFields.includes(f.name)) continue;

    const baseColumn: Column<T> = {
      id: f.name,
      header: f.label,
      filterType: filterTypeForFieldKind(f.kind),
      width: widthForFieldKind(f.kind),
      align: alignForFieldKind(f.kind),
      // Поля з hiddenInTable (audit «Автор»/«Корректировка») не виключаємо —
      // робимо прихованими за замовчуванням, але доступними через «⚙️ Колонки».
      defaultHidden: f.hiddenInTable,
      textOf: customTextOf[f.name] ?? ((row) => textForField(row, f)),
      render: customRender[f.name],
    };

    // Для REF-полів — пов'язуємо з refTypeId, щоб ListView побудував picker.
    if (f.kind === "REF" && f.refTypeId != null) {
      const refTid = f.refTypeId;
      const unionTypes = f.refTypeIds && f.refTypeIds.length > 1 ? f.refTypeIds : null;
      baseColumn.refTypeId = refTid;
      if (unionTypes) {
        // Union-ссилка: колонка фільтрується через [Т]-вибір типу + picker.
        baseColumn.refTypeIds = unionTypes;
        // idOf дає композит "<typeId>:<uuid>", щоб applyFilter звіряв і тип, і id.
        // Якщо DTO віддає лише id — звіряється лише UUID-частина.
        const typeKey = `${f.name}TypeId`;
        baseColumn.idOf = (row) => {
          const id = row[f.name] ?? (row as Record<string, unknown>)[`${f.name}Id`];
          if (id == null || id === "") return "";
          const t = (row as Record<string, unknown>)[typeKey];
          return t == null ? String(id) : `${t}:${id}`;
        };
      } else {
        baseColumn.idOf = (row) => {
          const v = row[f.name] ?? (row as Record<string, unknown>)[`${f.name}Id`];
          return v == null ? "" : String(v);
        };
      }
      // Якщо є resolver — показуємо читабельний display замість UUID (і для
      // пошуку/фільтра по тексту). SYSTEM-маркер показуємо як «SYSTEM».
      if (resolveRef && !customTextOf[f.name]) {
        baseColumn.textOf = (row) => {
          const v = row[f.name] ?? (row as Record<string, unknown>)[`${f.name}Id`];
          if (v == null || v === "") return "";
          const raw = String(v);
          if (raw === "__SYSTEM__") return "SYSTEM";
          // Для union — резолвимо по конкретному типу з рядка (якщо є).
          const rtid = unionTypes
            ? Number((row as Record<string, unknown>)[`${f.name}TypeId`] ?? refTid)
            : refTid;
          return resolveRef(rtid, raw) ?? raw;
        };
      }
    }

    const customized = customizeColumn ? customizeColumn(baseColumn, f) : baseColumn;
    if (customized) result.push(customized);
  }

  // Додаткові пейдж-специфічні колонки (наприклад, «Ролі» в UsersPage).
  result.push(...extraColumns);

  return result;
}

// helpers

/**
 * Витяг значення поля з рядка для відображення.
 * Спеціальна обробка: booleans → «так/ні», dates → локаль, null → порожньо.
 */
function textForField<T extends Record<string, unknown>>(row: T, f: FieldDescriptor): string {
  const v = row[f.name];
  if (v == null) return "";
  if (f.kind === "BOOLEAN") return v ? "yes" : "no";
  if (f.kind === "DATE" || f.kind === "DATETIME") {
    if (typeof v === "string") {
      // Backend віддає ISO; форматуємо в локаль для відображення.
      const d = new Date(v);
      if (!Number.isNaN(d.getTime())) {
        return f.kind === "DATE"
          ? d.toLocaleDateString()
          : d.toLocaleString();
      }
    }
  }
  return String(v);
}

/**
 * Мапінг {@code FieldKind} (backend metadata) на {@code ColumnFilterType}
 * (ListView filter UI).
 */
function filterTypeForFieldKind(kind: FieldKind): ColumnFilterType {
  switch (kind) {
    case "NUMBER":   return "number";
    case "BOOLEAN":  return "boolean";
    case "DATE":     return "date";
    case "DATETIME": return "date";
    case "REF":      return "reference";
    default:         return "string";
  }
}

/** Рекомендована ширина колонки за типом поля. */
function widthForFieldKind(kind: FieldKind): string | undefined {
  switch (kind) {
    case "CODE":     return "140px";
    case "BOOLEAN":  return "110px";
    case "NUMBER":   return "120px";
    case "DATE":     return "110px";
    case "DATETIME": return "150px";
    case "EMAIL":    return "200px";
    default:         return undefined;  // auto
  }
}

/** Вирівнювання текста в клітинці за типом. */
function alignForFieldKind(kind: FieldKind): Column<unknown>["align"] {
  switch (kind) {
    case "NUMBER":  return "right";
    case "BOOLEAN": return "center";
    case "DATE":
    case "DATETIME": return "center";
    default:        return "left";
  }
}
