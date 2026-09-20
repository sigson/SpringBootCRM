import { registerFreeController } from "./freeControllerRegistry";
import { SqlWorkbenchPage } from "../pages/SqlWorkbenchPage";
import { DataGenPage } from "../pages/DataGenPage";

/**
 * <h2>Реєстрація представлень вільних контролерів.</h2>
 *
 * <p>typeId мусять збігатися із зарезервованим діапазоном бекенду
 * ({@code ToolRegistry.FREE_CONTROLLER_TYPE_BASE = 9400}): SQL Workbench = 9401,
 * генерація даних = 9402. Бекенд публікує ці типи як {@code representation:NONSTANDARD};
 * тут ми надаємо їм фронтове представлення. Якщо для якогось NONSTANDARD-типу
 * хука тут не буде — {@code FreeControllerPage} віддасть 404.
 */

export const SQL_WORKBENCH_TYPE_ID = 9401;
export const DATAGEN_TYPE_ID = 9402;

let initialized = false;

export function initFreeControllers(): void {
  if (initialized) return;
  initialized = true;

  registerFreeController(SQL_WORKBENCH_TYPE_ID, SqlWorkbenchPage);
  registerFreeController(DATAGEN_TYPE_ID, DataGenPage);
}
