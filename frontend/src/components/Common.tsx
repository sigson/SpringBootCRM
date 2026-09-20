import { type ReactNode, useEffect } from "react";
import { Link } from "react-router-dom";
import { useAuth, isAdmin } from "../auth/AuthProvider";

export function TopNav() {
  const { user, logout } = useAuth();
  const initials = user?.displayName
    ? user.displayName.trim().split(/\s+/).map(s => s[0]).slice(0, 2).join("").toUpperCase()
    : user?.username?.slice(0, 2).toUpperCase() ?? "??";

  return (
    <nav className="topnav">
      <div className="topnav__left">
        <Link to="/" className="brand-mark">
          <span className="brand-mark__logo" />
          <span>SpringBootCRM</span>
        </Link>
      </div>
      <div className="topnav__right">
        <span className="env-badge">DEV</span>
        {user && isAdmin(user) && <span className="tag tag--admin">Administrator</span>}
        <button className="icon-btn" onClick={toggleTheme} title="Toggle theme">
          <span style={{ fontSize: 14 }}>◐</span>
        </button>
        <Link to="/profile" className="avatar" title={user?.username ?? ""}>
          {initials}
        </Link>
        <button
          className="btn btn--small"
          onClick={() => void logout()}
          title="Sign out"
        >Sign out</button>
      </div>
    </nav>
  );
}

function toggleTheme() {
  const root = document.documentElement;
  const next = root.dataset.theme === "dark" ? "light" : "dark";
  root.dataset.theme = next;
  try { localStorage.setItem("springbootcrm_theme", next); } catch {  }
}

export function useThemeBootstrap() {
  useEffect(() => {
    try {
      const t = localStorage.getItem("springbootcrm_theme");
      if (t === "light" || t === "dark") document.documentElement.dataset.theme = t;
      else document.documentElement.dataset.theme = "light";  // 1С-стиль: світла за замовчуванням
    } catch {  }
  }, []);
}

export function PageHead({
  title, subtitle, actions,
}: { title: string; subtitle?: string; actions?: ReactNode }) {
  return (
    <header className="page__head">
      <div className="page__title-block">
        <h1>{title}</h1>
        {subtitle && <p>{subtitle}</p>}
      </div>
      {actions && <div className="hflex">{actions}</div>}
    </header>
  );
}

interface FormFieldProps {
  label: string;
  value: string;
  onChange: (v: string) => void;
  error?: string | null;
  type?: "text" | "email" | "password" | "number" | "datetime-local" | "date";
  placeholder?: string;
  textarea?: boolean;
  rows?: number;
  required?: boolean;
  autoFocus?: boolean;
  readOnly?: boolean;
  description?: string | null;
  /** Опціональна оверлей-панель (напр. результат «живої» валідації) над полем. */
  overlay?: ReactNode;
  /** Підсвітити поле як невалідне (рамка) — для інтерактивної валідації. */
  invalid?: boolean;
  /** Колбек втрати фокусу — використовується для «торкнутості» live-валідації. */
  onBlur?: () => void;
}

export function FormField({
  label, value, onChange, error, type = "text", placeholder,
  textarea, rows = 3, required, autoFocus, readOnly, description,
  overlay, invalid, onBlur,
}: FormFieldProps) {
  const controlClass = (base: string) =>
    invalid ? `${base} ${base}--invalid` : base;
  return (
    <label className="form-field">
      <span className="form-field__label">{label}{required && " *"}</span>
      <span className="form-field__control">
        {textarea ? (
          <textarea
            className={controlClass("form-field__textarea")}
            value={value}
            onChange={e => onChange(e.target.value)}
            onBlur={onBlur}
            rows={rows}
            placeholder={placeholder ?? undefined}
            readOnly={readOnly}
          />
        ) : (
          <input
            className={controlClass("form-field__input")}
            value={value}
            onChange={e => onChange(e.target.value)}
            onBlur={onBlur}
            type={type}
            placeholder={placeholder ?? undefined}
            autoFocus={autoFocus}
            readOnly={readOnly}
          />
        )}
        {overlay}
      </span>
      {description && <span className="form-field__hint">{description}</span>}
      {error && <span className="form-field__error">{error}</span>}
    </label>
  );
}

export function CheckboxField({
  label, value, onChange, description, readOnly,
}: {
  label: string; value: boolean; onChange: (v: boolean) => void;
  description?: string | null; readOnly?: boolean;
}) {
  return (
    <label className="form-field form-field--checkbox">
      <span className="hflex hflex--start">
        <input
          type="checkbox"
          checked={value}
          disabled={readOnly}
          onChange={e => onChange(e.target.checked)}
        />
        <span className="form-field__label form-field__label--inline">{label}</span>
      </span>
      {description && <span className="form-field__hint">{description}</span>}
    </label>
  );
}

export function Alert({
  kind = "error", children,
}: { kind?: "error" | "success" | "info"; children: ReactNode }) {
  return <div className={`alert alert--${kind}`}>{children}</div>;
}

export function EmptyState({ children }: { children: ReactNode }) {
  return <div className="empty-state">{children}</div>;
}

/**
 * @deprecated використовуйте {@code useApiErrorHandler} з ErrorDialog. Хелпер
 * читає {@code message} з ErrorEnvelope і {@code error} зі старого shape.
 */
export function extractError(err: unknown): { message: string; field?: string } {
  if (typeof err === "object" && err !== null) {
    const e = err as { error?: string; message?: string; field?: string;
                       fieldErrors?: Array<{ field?: string; message: string }>;
                       status?: number };
    const firstFieldError = e.fieldErrors?.[0];
    return {
      message: e.message || e.error || firstFieldError?.message || `Error ${e.status ?? ""}`,
      field: firstFieldError?.field || e.field,
    };
  }
  return { message: String(err) };
}
