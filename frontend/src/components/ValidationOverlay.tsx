import { useEffect, useRef, useState } from "react";
import { validationApi, type FieldValidationResult } from "../api/endpoints";

/**
 * <h2>Інтерактивна валідація реквізиту через бекенд (з дебаунсом).</h2>
 *
 * <p>Поведінка за ТЗ: коли користувач вводить значення, інтерфейс чекає
 * завершення вводу (≈ {@code delay} мс без змін) і одразу валідує поле —
 * звертаючись до {@code POST /api/validation/{slug}/field}. Бекенд є єдиним
 * джерелом правди (ті самі бін-обмеження, що й при збереженні), тож користувач
 * не побачить розбіжностей між «живою» перевіркою і фінальним {@code Зберегти}.
 *
 * <p>Результат несе {@code message} + {@code meta} (min/max/maxLength…), які
 * показуються в оверлей-панелі над полем.
 */

export type LiveStatus = "idle" | "pending" | "valid" | "invalid";

export interface LiveValidation {
  status: LiveStatus;
  message: string | null;
  meta: Record<string, unknown> | null;
}

export interface UseLiveFieldValidationOpts {
  slug: string;
  field: string;
  value: unknown;
  /** Вмикати запит лише коли поле «торкнуте» (щоб не лаяти порожнє при відкритті). */
  enabled: boolean;
  /** Затримка тиші перед валідацією, мс. */
  delay?: number;
}

export function useLiveFieldValidation(
  { slug, field, value, enabled, delay = 700 }: UseLiveFieldValidationOpts,
): LiveValidation {
  const [state, setState] = useState<LiveValidation>(
    { status: "idle", message: null, meta: null });

  // Монотонний лічильник запитів — гасить застарілі відповіді (race guard).
  const seqRef = useRef(0);

  useEffect(() => {
    if (!enabled || !slug || !field) {
      setState({ status: "idle", message: null, meta: null });
      return;
    }
    setState(s => ({ ...s, status: "pending" }));
    const mySeq = ++seqRef.current;
    const timer = window.setTimeout(() => {
      validationApi.field(slug, field, normalize(value))
        .then((r: FieldValidationResult) => {
          if (mySeq !== seqRef.current) return;   // прийшла застаріла відповідь
          setState({
            status: r.valid ? "valid" : "invalid",
            message: r.valid ? null : (r.message ?? "Invalid value"),
            meta: r.meta ?? null,
          });
        })
        .catch(() => {
          if (mySeq !== seqRef.current) return;
          // М'яка деградація: не блокуємо ввід через мережну помилку валідації.
          setState({ status: "idle", message: null, meta: null });
        });
    }, delay);

    return () => window.clearTimeout(timer);
  }, [slug, field, value, enabled, delay]);

  return state;
}

/** Нормалізація значення форми перед відправкою на бекенд. */
function normalize(v: unknown): unknown {
  if (v == null) return null;
  if (typeof v === "string") return v;
  return v;
}

/**
 * Оверлей-панель результату валідації — рівна ширині елемента вводу, з'являється
 * НАД полем і повідомляє причину непроходження валідації. Рендериться лише при
 * status==="invalid"; контейнер елемента вводу має бути {@code position:relative}.
 */
export function ValidationOverlay({ v }: { v: LiveValidation }) {
  if (v.status !== "invalid" || !v.message) return null;
  return (
    <div className="validation-overlay" role="alert">
      <span className="validation-overlay__msg">{v.message}</span>
    </div>
  );
}
