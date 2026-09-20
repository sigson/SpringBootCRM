import { useMemo } from "react";

/**
 * Real-time валідація форм для ТЗ1 §5.1 та ТЗ2 §3.1.
 *
 * <p>Hook повертає першу помилку зі списку правил (або null, якщо все ок).
 * Тексти помилок збігаються з ТЗ — поведінка повинна точно відтворювати
 * валідацію бекенду, щоб користувач не побачив розбіжностей після `Зберегти`.
 *
 * <p>Перевірка обов'язковості зазвичай винесена окремо — щоб не показувати
 * «Поле обов'язкове» одразу при відкритті порожньої форми (це психологічно
 * агресивно). Рекомендований патерн:
 *   <pre>
 *     const codeError = useFieldValidation(code, [
 *       validators.numberInRange(1, 100),
 *     ]);
 *     // обов'язковість перевіряється у submit-хендлері або через `touched`
 *   </pre>
 */

export type Validator = (value: string) => string | null;

export function useFieldValidation(value: string, rules: Validator[]): string | null {
  return useMemo(() => {
    for (const r of rules) {
      const err = r(value);
      if (err) return err;
    }
    return null;
  }, [value, ...rules]);
}

/** Регулярка ТЗ2 §3.1 — українські + латинські літери, цифри + . , ' _ - + * ( ) / №. */
export const ALLOWED_CHARS_RE =
  /^[A-Za-z\u0400-\u04FF\u0500-\u052F0-9 .,'_+*()/№\-]+$/;

export const ALLOWED_CHARS_MSG =
  "May contain only letters, digits and the symbols: . , ' _ - + * ( ) / №";

export const validators = {
  required:
    (msg = "This field is required"): Validator =>
    (v) => v.trim() === "" ? msg : null,

  numberInRange:
    (min: number, max: number, msg?: string): Validator =>
    (v) => {
      if (v.trim() === "") return null;   // required is checked separately
      const n = Number(v.replace(",", "."));
      if (Number.isNaN(n)) return msg ?? `Choose a number in the range ${min}-${max}`;
      if (!Number.isFinite(n)) return msg ?? `Choose a number in the range ${min}-${max}`;
      if (n < min || n > max) return msg ?? `Choose a number in the range ${min}-${max}`;
      return null;
    },

  /** Must be numeric (for NUMERIC-spec fields1 §4). */
  numericOnly:
    (msg = "May contain digits only"): Validator =>
    (v) => {
      if (v.trim() === "") return null;
      const n = Number(v.replace(",", "."));
      return Number.isNaN(n) ? msg : null;
    },

  /** A non-negative number (amounts, rates, discounts). */
  nonNegative:
    (msg = "The value cannot be negative"): Validator =>
    (v) => {
      if (v.trim() === "") return null;
      const n = Number(v.replace(",", "."));
      if (Number.isNaN(n)) return null;   // numericOnly catches it separately
      return n < 0 ? msg : null;
    },

  allowedChars:
    (re = ALLOWED_CHARS_RE, msg = ALLOWED_CHARS_MSG): Validator =>
    (v) => (v.trim() === "" || re.test(v)) ? null : msg,

  length:
    (min: number, max: number, msg?: string): Validator =>
    (v) => {
      if (v.trim() === "") return null;
      if (v.length < min || v.length > max) {
        return msg ?? `The field must not contain more than ${max} characters, and no fewer than ${min}-`;
      }
      return null;
    },

  integer:
    (msg = "Enter an integer"): Validator =>
    (v) => {
      if (v.trim() === "") return null;
      return /^-?\d+$/.test(v.trim()) ? null : msg;
    },

  /**
   * Email-format. Matches backend' ({@code @Email} / RFC-a similar check).
   * Empty values are skipped (required is checked separately).
   */
  email:
    (msg = "Invalid format email"): Validator =>
    (v) => {
      if (v.trim() === "") return null;
      // Simple but sufficient RFC5322-lite: local@domain.tld
      const re = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
      return re.test(v.trim()) ? null : msg;
    },

  /**
   * Username: 3–100 characters, Latin letters/digits/dot/underscore/hyphen only
   * (consistent with backend {@code @Size(min=3,max=100)} + the usual username policy).
   */
  username:
    (msg?: string): Validator =>
    (v) => {
      if (v.trim() === "") return null;   // required — separately
      const t = v.trim();
      if (t.length < 3 || t.length > 100) {
        return msg ?? "Username: from 3 to 100 characters";
      }
      if (!/^[A-Za-z0-9._-]+$/.test(t)) {
        return "Username may contain only Latin letters, digits and . _ -";
      }
      return null;
    },

  /**
   * Password: minimum 6, maximum 200 characters (consistent with backend
   * {@code @Size(min=6,max=200)}). Empty - skipped (for edit forms,
   * where an empty password = «keep unchanged»; the required check is separate).
   */
  password:
    (min = 6, max = 200, msg?: string): Validator =>
    (v) => {
      if (v === "") return null;
      if (v.length < min) return msg ?? `Password: at least ${min} characters`;
      if (v.length > max) return msg ?? `Password: at most ${max} characters`;
      return null;
    },

  /**
   * Catalog code: non-empty, up to 50 characters, letters/digits only
   * and the usual code symbols ({@code . _ - /}). Consistent with backend-validation
   * (length ≤ 50) and the format produced by {@code CodeGenerator} (prefix+digits).
   */
  referenceCode:
    (msg?: string): Validator =>
    (v) => {
      if (v.trim() === "") return null;   // required — separately
      const t = v.trim();
      if (t.length > 50) {
        return msg ?? "Code must not be longer than 50 characters";
      }
      if (!/^[A-Za-z\u0400-\u04FF0-9._/-]+$/.test(t)) {
        return "Code may contain only letters, digits and . _ - /";
      }
      return null;
    },
};
