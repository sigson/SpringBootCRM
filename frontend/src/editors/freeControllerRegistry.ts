import type { ComponentType } from "react";

/**
 * <h2>Реєстр представлень «вільних контролерів» (по typeId).</h2>
 *
 * <p>Симетрично до {@code registerEditor} (вікно агрегата) та
 * {@code registerListView} (список агрегата), але для типів з
 * {@code representation === "NONSTANDARD"} — SQL Workbench, генерація даних тощо.
 * Такі типи <b>не мають</b> generic-представлення (немає реквізитів/apiBase), тому
 * їхнє представлення зобов'язаний дати зареєстрований тут хук.
 *
 * <p><b>Контракт 404.</b> Якщо для NONSTANDARD-типу хук не зареєстровано,
 * відкриття ({@code FreeControllerPage}) дає модалку 404.
 *
 * <p>Реєстр статичний (як і інші reg-механізми): хуки реєструються один раз на
 * старті через {@code initFreeControllers()} (див. {@code editors/freeControllers}).
 */

/** Повноекранний компонент-представлення вільного контролера (без обов'язкових props). */
type FreeControllerComponent = ComponentType;

const registry = new Map<number, FreeControllerComponent>();

/** Зареєструвати представлення для NONSTANDARD-типу (за його typeId). */
export function registerFreeController(typeId: number, component: FreeControllerComponent): void {
  registry.set(typeId, component);
}

/** Отримати представлення (або null, якщо хука немає — тоді викликач показує 404). */
export function getFreeController(typeId: number): FreeControllerComponent | null {
  return registry.get(typeId) ?? null;
}
