import {
  createContext, useCallback, useContext, useEffect, useState, type ReactNode,
} from "react";
import { isErrorEnvelope } from "../api/client";
import {
  type ErrorEnvelope,
  type PermissionRequirement,
  type FieldErrorDto,
  humanizeFlags,
  flagLabel,
} from "../types/api";

/**
 * Центральне модальне вікно помилок — єдина точка для ACCESS_DENIED / VALIDATION
 * та інших категорій (замість розрізнених Toast/Alert по сторінках).
 *
 * Обробляє: {@code ACCESS_DENIED} — список {@link PermissionRequirement} (біти/типи/поля);
 * {@code VALIDATION} — список {@link FieldErrorDto} з привʼязкою до полів; решта
 * ({@code NOT_FOUND}/{@code CONFLICT}/{@code BAD_REQUEST}/{@code INTERNAL}) — текст message.
 *
 * Модалка не закриває WindowStack — показується поверх субокон через найвищий
 * z-index, тож після закриття користувач повертається у форму.
 */

interface ErrorDialogContextValue {
  /** Показати модалку з готовим envelope'ом. */
  show(envelope: ErrorEnvelope): void;
  /**
   * Зручний wrapper: приймає {@code unknown}, перевіряє, чи це {@link ErrorEnvelope},
   * якщо так — показує. Інакше — конвертує в generic INTERNAL і показує.
   * Не показує нічого, якщо {@code skipForKinds} містить kind помилки —
   * наприклад, не хочемо центральну модалку для тривіальних 404, які
   * вже обробляє локальний UI.
   */
  showFromError(err: unknown, opts?: { skipForKinds?: ErrorEnvelope["kind"][] }): void;
  /** Закрити поточну модалку (для тестів / explicit close). */
  dismiss(): void;
}

const ErrorDialogCtx = createContext<ErrorDialogContextValue | undefined>(undefined);

export function ErrorDialogProvider({ children }: { children: ReactNode }) {
  const [envelope, setEnvelope] = useState<ErrorEnvelope | null>(null);

  const show = useCallback((e: ErrorEnvelope) => {
    setEnvelope(e);
  }, []);

  const dismiss = useCallback(() => setEnvelope(null), []);

  const showFromError = useCallback((err: unknown, opts?: { skipForKinds?: ErrorEnvelope["kind"][] }) => {
    let env: ErrorEnvelope;
    if (isErrorEnvelope(err)) {
      env = err;
    } else if (err instanceof Error) {
      env = { kind: "INTERNAL", status: 0, message: err.message };
    } else {
      env = { kind: "INTERNAL", status: 0, message: String(err) };
    }
    if (opts?.skipForKinds?.includes(env.kind)) return;
    setEnvelope(env);
  }, []);

  // Закриваємо по ESC. Слухач ставиться тільки коли модалка відкрита,
  // не сpawn'ить зайвих listener'ів.
  useEffect(() => {
    if (!envelope) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") {
        e.stopPropagation();
        e.preventDefault();
        setEnvelope(null);
      }
    };
    document.addEventListener("keydown", onKey,  true);
    return () => document.removeEventListener("keydown", onKey,  true);
  }, [envelope]);

  return (
    <ErrorDialogCtx.Provider value={{ show, showFromError, dismiss }}>
      {children}
      {envelope && <ErrorDialogModal envelope={envelope} onClose={dismiss} />}
    </ErrorDialogCtx.Provider>
  );
}

export function useErrorDialog(): ErrorDialogContextValue {
  const ctx = useContext(ErrorDialogCtx);
  if (!ctx) throw new Error("useErrorDialog must be used inside ErrorDialogProvider");
  return ctx;
}

function ErrorDialogModal({ envelope, onClose }: { envelope: ErrorEnvelope; onClose: () => void }) {
  return (
    <div className="error-dialog__backdrop" onClick={onClose} role="dialog" aria-modal="true">
      <div
        className={`error-dialog error-dialog--${envelope.kind.toLowerCase()}`}
        onClick={(e) => e.stopPropagation()}
      >
        <div className="error-dialog__header">
          <span className="error-dialog__icon" aria-hidden="true">
            {iconFor(envelope.kind)}
          </span>
          <h2 className="error-dialog__title">{titleFor(envelope.kind)}</h2>
          <button
            type="button"
            className="error-dialog__close"
            onClick={onClose}
            aria-label="Close"
            title="Close (ESC)"
          >✕</button>
        </div>

        <div className="error-dialog__body">
          <p className="error-dialog__message">{envelope.message}</p>

          {envelope.kind === "ACCESS_DENIED" && envelope.requirements
              && envelope.requirements.length > 0 && (
            <RequirementsList requirements={envelope.requirements} />
          )}

          {envelope.kind === "VALIDATION" && envelope.fieldErrors
              && envelope.fieldErrors.length > 0 && (
            <FieldErrorsList errors={envelope.fieldErrors} />
          )}

          {envelope.path && (
            <div className="error-dialog__path muted">
              <small>{envelope.status} · {envelope.path}</small>
            </div>
          )}
        </div>

        <div className="error-dialog__footer">
          <button className="btn btn--primary" onClick={onClose} autoFocus>
            Got it
          </button>
        </div>
      </div>
    </div>
  );
}

function RequirementsList({ requirements }: { requirements: PermissionRequirement[] }) {
  return (
    <div className="error-dialog__requirements">
      <p className="error-dialog__subtitle">Required rights:</p>
      <ul className="error-dialog__req-list">
        {requirements.map((req, idx) => (
          <li key={idx} className="error-dialog__req-item">
            <strong>{flagsToText(req.requiredFlags)}</strong>
            {req.typeLabel || req.typeId != null ? (
              <>
                {" "} per <em>«{req.typeLabel ?? `type #${req.typeId}`}»</em>
              </>
            ) : (
              <> {" "}(global permission)</>
            )}
            {req.scope === "FIELD" && req.fieldName && (
              <> {" "} (field <code>{req.fieldName}</code>)</>
            )}
          </li>
        ))}
      </ul>
      <p className="error-dialog__hint muted">
        Ask an administrator to grant you a suitable role or rights.
      </p>
    </div>
  );
}

function FieldErrorsList({ errors }: { errors: FieldErrorDto[] }) {
  // Якщо у нас лише ОДНА помилка і її message == top-level message — не дублюємо.
  // Інакше показуємо повний список.
  if (errors.length === 1 && errors[0]!.field == null) {
    return null;   // top-level message вже показано
  }
  return (
    <div className="error-dialog__field-errors">
      <p className="error-dialog__subtitle">Details:</p>
      <ul className="error-dialog__field-list">
        {errors.map((e, idx) => (
          <li key={idx} className="error-dialog__field-item">
            {e.field
              ? <><strong>Field «{humanFieldName(e.field)}»:</strong> {e.message}</>
              : <>{e.message}</>}
          </li>
        ))}
      </ul>
    </div>
  );
}

function iconFor(kind: ErrorEnvelope["kind"]): string {
  switch (kind) {
    case "ACCESS_DENIED": return "🔒";
    case "VALIDATION":    return "⚠";
    case "NOT_FOUND":     return "🔍";
    case "CONFLICT":      return "⚡";
    case "UNAUTHORIZED":  return "🔑";
    case "INTERNAL":      return "✕";
    case "BAD_REQUEST":   return "⚠";
    default:              return "ℹ";
  }
}

function titleFor(kind: ErrorEnvelope["kind"]): string {
  switch (kind) {
    case "ACCESS_DENIED": return "Not enough rights";
    case "VALIDATION":    return "Validation error";
    case "NOT_FOUND":     return "Not found";
    case "CONFLICT":      return "Data conflict";
    case "UNAUTHORIZED":  return "Authentication required";
    case "INTERNAL":      return "Server error";
    case "BAD_REQUEST":   return "Invalid request";
    default:              return "Error";
  }
}

function flagsToText(mask: number): string {
  const names = humanizeFlags(mask);
  if (names.length === 0) return "—";
  return names.map(flagLabel).join(" + ");
}

/** Просте мапування технічних імен полів на людинозрозумілі.  */
function humanFieldName(field: string): string {
  // Знімаємо префікс DTO-record'у ("createRequest.name" → "name").
  const tail = field.includes(".") ? field.substring(field.lastIndexOf(".") + 1) : field;
  switch (tail) {
    case "code":         return "Code";
    case "name":         return "Name";
    case "username":     return "Username";
    case "password":     return "Password";
    case "email":        return "Email";
    case "displayName":  return "Full name";
    case "description":  return "Description";
    case "title":        return "Title";
    case "startsAt":     return "Starts at";
    case "endsAt":       return "Ends at";
    case "hourlyRate":   return "Rate";
    case "value":        return "Value";
    case "norms":        return "Amount";
    case "enabled":      return "Active";
    case "ownerId":      return "Owner";
    default:             return field;
  }
}
