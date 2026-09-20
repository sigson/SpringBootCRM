import { registerEditor } from "./registry";
import { InterfaceLayoutEditor } from "./InterfaceLayoutEditor";
import { ReportEditor } from "./ReportEditor";
import { registerReportActions } from "./reportActions";

/**
 * <h2>Переозначення подань — мінімальний набір після повної генералізації.</h2>
 *
 * <p><b>Editor-override (форма редагування об'єкта БД).</b> Дозволені лише два —
 * там, де потрібен спеціалізований конструктор:
 * <ul>
 *   <li>Ролі — конструктор ролей ({@code AccessRoleEditor}, реєструється в {@code editors/index});</li>
 *   <li>Інтерфейси — конструктор інтерфейсу ({@code InterfaceLayoutEditor}, нижче);</li>
 *   <li>Звіти — конструктор схеми компоновки ({@code ReportEditor} → ліниво
 *       підвантажуваний модуль {@code modules/dcs}).</li>
 * </ul>
 * Усі інші об'єкти БД (Користувачі, Технічні стани, Типи ремонту, Ставки,
 * Коефіцієнти, Календар, Курси валют, …) редагуються згенерованою field-формою
 * з метаданих ({@code GenericAggregateEditor}) — завдяки стандартизованим,
 * типізованим елементам керування (включно з union-ссилками — див.
 * {@code AggregateWindow} — та date-only контролом для LocalDate-полів).
 *
 * <p><b>List-override (вікно списку).</b> Жодного: усі списки — generic
 * {@code ObjectList}, побудований з метаданих.
 */

const INTERFACE_LAYOUT_TYPE_ID = 9300;
const REPORT_TYPE_ID = 9500;

let initialized = false;

export function initListViews(): void {
  if (initialized) return;
  initialized = true;

  registerEditor(INTERFACE_LAYOUT_TYPE_ID, InterfaceLayoutEditor);
  // Конструктор схеми компоновки — майстер-деталь у три панелі; 900px замало.
  registerEditor(REPORT_TYPE_ID, ReportEditor, "full");
  // Плюс дія «Сформувати» прямо зі списку — основний шлях звичайного користувача.
  registerReportActions();
}
