import { registerRowAction } from "./registry";
import { ReportRunnerWindow } from "./ReportRunnerWindow";

/**
 * Дія «Сформувати» в рядку списку звітів.
 *
 * <p>Головна дія над звітом — не правка схеми, а формування; у generic-списку
 * первинна кнопка «Відкрити» веде в конструктор, тож користувацький шлях треба
 * додати окремо. Вікно рендерить форму звіту з модуля компоновки; якщо модуль
 * видалено, вікно чесно показує порожній стан, а довідник лишається робочим.
 */
export const REPORT_TYPE_ID = 9500;

export function registerReportActions(): void {
  registerRowAction(REPORT_TYPE_ID, {
    label: "Compose",
    icon: "📊",
    onClick: (id, row, ctx) => {
      const name = typeof row.name === "string" ? row.name : "Report";
      ctx.openWindow({
        title: name,
        width: "full",
        content: (close) => (
          <ReportRunnerWindow reportId={id} title={name} onClose={close} />
        ),
      });
    },
  });
}
