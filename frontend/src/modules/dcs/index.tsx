/**
 * Публичная точка входа модуля компоновки данных.
 *
 * <p>Хост подключает модуль одним ленивым импортом этого файла и ничего не знает про
 * его внутренности — как и в случае Workbench'а. Если папку удалить, импорт упадёт в
 * runtime, error-boundary хоста это поймает, и раздел отчётов просто исчезнет.
 */
export { ReportDesigner } from "./ReportDesigner";
export type { ReportDesignerProps } from "./ReportDesigner";
export { ReportRunner } from "./ReportRunner";
export type { ReportRunnerProps } from "./ReportRunner";
export { DcsClient, DcsApiError } from "./api";
