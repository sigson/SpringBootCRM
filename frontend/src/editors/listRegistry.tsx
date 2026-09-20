import type { ComponentType } from "react";
import type { TypeDescriptor } from "../types/api";

/**
 * <h2>Реєстр переозначень <i>списку</i> об'єкта БД (по typeId).</h2>
 *
 * <p>Симетрично до {@code registerEditor} (переозначення вікна редагування), цей
 * реєстр дозволяє переозначити <b>цілу сторінку списку</b> для типу — для випадків,
 * коли подання списку принципово не табличне (матриця норм ремонту, календар тощо).
 *
 * <p>Без переозначення {@code ObjectListPage} рендерить generic {@code ObjectList},
 * побудований з метаданих.
 */
export interface ObjectListProps {
  td: TypeDescriptor;
}

type ListComponent = ComponentType<ObjectListProps>;

const listRegistry = new Map<number, ListComponent>();

export function registerListView(typeId: number, component: ListComponent): void {
  listRegistry.set(typeId, component);
}

export function getListView(typeId: number): ListComponent | null {
  return listRegistry.get(typeId) ?? null;
}
