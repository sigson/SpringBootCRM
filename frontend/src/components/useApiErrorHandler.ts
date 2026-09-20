import { useCallback } from "react";
import { useErrorDialog } from "../components/ErrorDialog";
import { isErrorEnvelope } from "../api/client";
import type { ErrorEnvelope, FieldErrorDto } from "../types/api";

/**
 * Уніфікований хук для обробки помилок з API.
 *
 * <p><b>Семантика.</b> Кожна помилка з backend'у тепер приходить як
 * {@link ErrorEnvelope} з полем {@code kind}. Цей хук маршрутизує помилки таким чином:
 *
 * <ul>
 *   <li><b>ACCESS_DENIED</b> → завжди центральна модалка (req 4 — єдиний формат);</li>
 *   <li><b>VALIDATION</b> → центральна модалка + повертає мапу {@code field → message}
 *       для inline-рендеру помилок поряд з input'ами (req 5 — клієнт знає,
 *       яке саме поле провалилось, паралельно з модалкою);</li>
 *   <li><b>UNAUTHORIZED</b> → центральна модалка (обично уже redirect на /login);</li>
 *   <li><b>NOT_FOUND / CONFLICT / BAD_REQUEST / INTERNAL</b> → центральна модалка,
 *       якщо явно не сказано {@code preferInline} (тоді повертаємо текст для
 *       inline-показу — це залежить від pages-specific UX).</li>
 * </ul>
 *
 * <p><b>Як використовувати:</b>
 * <pre>{@code
 *   const handleApiError = useApiErrorHandler();
 *
 *   try {
 *     await api.create(...);
 *   } catch (err) {
 *     const { fieldErrors, submitError } = handleApiError(err);
 *     setFieldErrors(fieldErrors);     // inline-помилки
 *     setSubmitError(submitError);     // загальна (опційно)
 *   }
 * }</pre>
 *
 * <p>Hook не повертає Promise і не кидає — він суто side-effect (показати модалку
 * якщо треба) + повертає structured info для locale UI.
 */
export interface ApiErrorHandlerResult {
  /** Карта {@code field → message} для inline-рендеру (тільки VALIDATION). */
  fieldErrors: Record<string, string>;
  /** Загальний текст помилки для inline-показу (опціонально; пейдж сам вирішує, показувати чи ні). */
  submitError: string;
  /** Оригінальний envelope (для додаткової логіки на місцях). */
  envelope: ErrorEnvelope | null;
}

export function useApiErrorHandler(): (err: unknown, opts?: {
  /** Не показувати центральну модалку для цих kind'ів. */
  silentKinds?: ErrorEnvelope["kind"][];
  /** Для VALIDATION: не показувати модалку, тільки повернути fieldErrors. */
  silentForValidation?: boolean;
}) => ApiErrorHandlerResult {
  const errorDialog = useErrorDialog();

  return useCallback((err: unknown, opts) => {
    if (!isErrorEnvelope(err)) {
      // Нечекана JS-помилка (network drop, exception у коді). Показуємо як INTERNAL.
      const message = err instanceof Error ? err.message : String(err);
      const envelope: ErrorEnvelope = {
        kind: "INTERNAL",
        status: 0,
        message,
      };
      if (!opts?.silentKinds?.includes("INTERNAL")) {
        errorDialog.show(envelope);
      }
      return { fieldErrors: {}, submitError: message, envelope };
    }

    const envelope = err;
    const silent = opts?.silentKinds?.includes(envelope.kind) ?? false;
    const silentValidation = envelope.kind === "VALIDATION" && opts?.silentForValidation;

    if (!silent && !silentValidation) {
      errorDialog.show(envelope);
    }

    const fieldErrors: Record<string, string> = {};
    if (envelope.fieldErrors) {
      for (const fe of envelope.fieldErrors) {
        if (fe.field) {
          // Знімаємо префікс DTO-record'у (createRequest.code → code).
          const tail = fe.field.includes(".") ? fe.field.substring(fe.field.lastIndexOf(".") + 1) : fe.field;
          fieldErrors[tail] = fe.message;
        }
      }
    }

    return {
      fieldErrors,
      submitError: envelope.message,
      envelope,
    };
  }, [errorDialog]);
}

/**
 * Допоміжна функція: чи є серед field-errors помилка з конкретним ім'ям поля.
 * Зручно у формах: {@code if (hasFieldError(fieldErrors, "password")) ...}
 */
export function hasFieldError(map: Record<string, string>, field: string): boolean {
  return Object.prototype.hasOwnProperty.call(map, field);
}
