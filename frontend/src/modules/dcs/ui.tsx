import { useState, type CSSProperties, type ReactNode } from "react";

/**
 * Мелкие общие контролы конструктора.
 *
 * <p>Модуль не импортирует компоненты хоста сознательно: он должен собираться и
 * работать сам по себе (как и Workbench), а вид берёт из общих CSS-переменных темы.
 * Поэтому здесь свои Field/Select/Check — на десяток строк каждый, зато без связи
 * с хостом.
 */

export function Field({
  label, hint, children, span,
}: { label: string; hint?: string; children: ReactNode; span?: number }) {
  return (
    <label className="dcs-field" style={span ? { gridColumn: `span ${span}` } : undefined}>
      <span className="dcs-field__label">{label}</span>
      {children}
      {hint && <span className="dcs-field__hint">{hint}</span>}
    </label>
  );
}

export function TextInput({
  value, onChange, placeholder, mono, readOnly, invalid,
}: {
  value: string; onChange: (v: string) => void;
  placeholder?: string; mono?: boolean; readOnly?: boolean; invalid?: boolean;
}) {
  return (
    <input
      className={`dcs-input${mono ? " dcs-input--mono" : ""}${invalid ? " dcs-input--invalid" : ""}`}
      value={value}
      placeholder={placeholder}
      readOnly={readOnly}
      onChange={(e) => onChange(e.target.value)}
    />
  );
}

export function NumberInput({
  value, onChange, placeholder,
}: { value: number | null | undefined; onChange: (v: number | null) => void; placeholder?: string }) {
  return (
    <input
      className="dcs-input"
      type="number"
      value={value ?? ""}
      placeholder={placeholder}
      onChange={(e) => onChange(e.target.value === "" ? null : Number(e.target.value))}
    />
  );
}

export function TextArea({
  value, onChange, rows = 6, placeholder, mono,
}: {
  value: string; onChange: (v: string) => void;
  rows?: number; placeholder?: string; mono?: boolean;
}) {
  return (
    <textarea
      className={`dcs-textarea${mono ? " dcs-textarea--mono" : ""}`}
      rows={rows}
      value={value}
      placeholder={placeholder}
      onChange={(e) => onChange(e.target.value)}
    />
  );
}

export interface Option { value: string; label: string; }

export function Select({
  value, onChange, options, empty, disabled,
}: {
  value: string | null | undefined; onChange: (v: string) => void;
  options: Option[]; empty?: string; disabled?: boolean;
}) {
  return (
    <select
      className="dcs-input"
      value={value ?? ""}
      disabled={disabled}
      onChange={(e) => onChange(e.target.value)}
    >
      {empty !== undefined && <option value="">{empty}</option>}
      {options.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
    </select>
  );
}

export function Check({
  label, value, onChange, title,
}: { label: string; value: boolean; onChange: (v: boolean) => void; title?: string }) {
  return (
    <label className="dcs-check" title={title}>
      <input type="checkbox" checked={value} onChange={(e) => onChange(e.target.checked)} />
      <span>{label}</span>
    </label>
  );
}

export function Btn({
  children, onClick, kind, disabled, title, small,
}: {
  children: ReactNode; onClick?: () => void;
  kind?: "primary" | "danger" | "ghost"; disabled?: boolean; title?: string; small?: boolean;
}) {
  return (
    <button
      type="button"
      className={`dcs-btn${kind ? ` dcs-btn--${kind}` : ""}${small ? " dcs-btn--small" : ""}`}
      onClick={onClick}
      disabled={disabled}
      title={title}
    >
      {children}
    </button>
  );
}

export function Toolbar({ children }: { children: ReactNode }) {
  return <div className="dcs-toolbar">{children}</div>;
}

export function Spacer() { return <span style={{ flex: 1 }} />; }

export function Panel({
  title, actions, children, scroll, style,
}: {
  title?: ReactNode; actions?: ReactNode; children: ReactNode;
  scroll?: boolean; style?: CSSProperties;
}) {
  return (
    <section className="dcs-panel" style={style}>
      {(title || actions) && (
        <header className="dcs-panel__head">
          <span className="dcs-panel__title">{title}</span>
          {actions}
        </header>
      )}
      <div className={`dcs-panel__body${scroll ? " dcs-panel__body--scroll" : ""}`}>{children}</div>
    </section>
  );
}

export function Empty({ children }: { children: ReactNode }) {
  return <div className="dcs-empty">{children}</div>;
}

export function Grid({ cols = 2, children }: { cols?: number; children: ReactNode }) {
  return (
    <div className="dcs-grid" style={{ gridTemplateColumns: `repeat(${cols}, minmax(0, 1fr))` }}>
      {children}
    </div>
  );
}

/**
 * Список «мастер» с тулбаром добавления/удаления/перемещения. Используется всеми
 * вкладками, где редактируется коллекция (наборы, связи, ресурсы, параметры,
 * макеты, формы) — иначе каждая вкладка переписывала бы одну и ту же механику.
 */
export function MasterList<T>({
  items, selected, onSelect, label, onAdd, onRemove, onMove, addLabel, emptyText,
}: {
  items: T[];
  selected: number;
  onSelect: (i: number) => void;
  label: (item: T, i: number) => ReactNode;
  onAdd?: () => void;
  onRemove?: (i: number) => void;
  onMove?: (from: number, to: number) => void;
  addLabel?: string;
  emptyText?: string;
}) {
  return (
    <div className="dcs-master">
      <Toolbar>
        {onAdd && <Btn small onClick={onAdd}>＋ {addLabel ?? "Add"}</Btn>}
        <Spacer />
        {onMove && (
          <>
            <Btn small title="Up" disabled={selected <= 0}
                 onClick={() => onMove(selected, selected - 1)}>↑</Btn>
            <Btn small title="Down" disabled={selected < 0 || selected >= items.length - 1}
                 onClick={() => onMove(selected, selected + 1)}>↓</Btn>
          </>
        )}
        {onRemove && (
          <Btn small kind="danger" title="Delete" disabled={selected < 0}
               onClick={() => onRemove(selected)}>🗑</Btn>
        )}
      </Toolbar>
      <div className="dcs-master__list">
        {items.length === 0 && <Empty>{emptyText ?? "Empty"}</Empty>}
        {items.map((item, i) => (
          <div
            key={i}
            className={`dcs-master__row${i === selected ? " is-selected" : ""}`}
            onClick={() => onSelect(i)}
          >
            {label(item, i)}
          </div>
        ))}
      </div>
    </div>
  );
}

/** Горизонтальные вкладки с сохранением выбранной вкладки в состоянии вызывающего. */
export function TabBar({
  tabs, active, onChange,
}: { tabs: { id: string; label: string; badge?: number }[]; active: string; onChange: (id: string) => void }) {
  return (
    <nav className="dcs-tabs">
      {tabs.map((t) => (
        <button
          key={t.id}
          type="button"
          className={`dcs-tab${active === t.id ? " is-active" : ""}`}
          onClick={() => onChange(t.id)}
        >
          {t.label}
          {t.badge != null && t.badge > 0 && <span className="dcs-tab__badge">{t.badge}</span>}
        </button>
      ))}
    </nav>
  );
}

/** Сообщение-баннер: предупреждения компоновки и ошибки запроса. */
export function Banner({
  kind = "info", children, onClose,
}: { kind?: "info" | "warn" | "error" | "ok"; children: ReactNode; onClose?: () => void }) {
  return (
    <div className={`dcs-banner dcs-banner--${kind}`}>
      <div style={{ flex: 1 }}>{children}</div>
      {onClose && <Btn small kind="ghost" onClick={onClose}>✕</Btn>}
    </div>
  );
}

/** Свёртываемая секция — используется в длинных вкладках настроек. */
export function Collapsible({
  title, children, defaultOpen = true, actions,
}: { title: ReactNode; children: ReactNode; defaultOpen?: boolean; actions?: ReactNode }) {
  const [open, setOpen] = useState(defaultOpen);
  return (
    <div className="dcs-collapsible">
      <header className="dcs-collapsible__head">
        <button type="button" className="dcs-collapsible__toggle" onClick={() => setOpen(!open)}>
          <span className="dcs-collapsible__caret">{open ? "▾" : "▸"}</span>
          {title}
        </button>
        {actions}
      </header>
      {open && <div className="dcs-collapsible__body">{children}</div>}
    </div>
  );
}

/** Устойчивый идентификатор для вновь созданных узлов настроек/форм/макетов. */
export function newId(prefix: string): string {
  return `${prefix}-${Math.random().toString(36).slice(2, 9)}`;
}

/** Глубокая копия структуры настроек — редактирование всегда идёт по копии. */
export function clone<T>(v: T): T {
  return JSON.parse(JSON.stringify(v)) as T;
}

export function move<T>(list: T[], from: number, to: number): T[] {
  if (to < 0 || to >= list.length || from < 0 || from >= list.length) return list;
  const copy = list.slice();
  const [item] = copy.splice(from, 1);
  copy.splice(to, 0, item);
  return copy;
}
