import { useCallback } from "react";
import { useMetadata } from "../metadata/MetadataProvider";
import { useWindowStack, nextWindowId } from "../windows/WindowStack";
import { useHasReadAccess, useHasWriteAccess } from "../auth/permissions";
import { useToast } from "../components/Toast";
import { useErrorDialog } from "../components/ErrorDialog";
import { useDisplayResolver } from "../metadata/DisplayResolver";
import { invalidateRefAutocompleteCache } from "../components/RefAutocompleteInput";
import { referencesApi } from "../api/endpoints";
import { getEditor, getEditorWidth } from "./registry";
import { GenericAggregateEditor } from "../components/AggregateWindow";

/**
 * <h2>Хук відкриття редактора елемента справочника <i>модально</i>.</h2>
 *
 * <p>Центральна точка drill-down по ссилках. Виклик не змінює URL — редактор
 * живе у стеку модальних вікон ({@code WindowStackProvider}).
 *
 * <h3>Логіка диспатчу:</h3>
 * <ol>
 *   <li>Якщо для {@code typeId} зареєстровано користувацький редактор у
 *       {@link registerEditor} (User, AccessRole, CalendarEvent тощо) — рендеримо
 *       його.</li>
 *   <li>Інакше — використовуємо generic {@link GenericAggregateEditor}.</li>
 * </ol>
 *
 * <h3>Перевірка прав:</h3>
 * Без READ-прав на {@code typeId} показуємо центральну модалку помилок із
 * вказівкою потрібного біта.
 *
 * <h3>Prefetch коду (уникає double-increment):</h3>
 * Якщо це створення нового елемента довідника, ми <b>заздалегідь</b> запитуємо
 * {@code GET /api/references/{slug}/next-code} <i>до</i> відкриття вікна, а
 * отриманий код передаємо у редактор як {@code prefetchedCode}-prop. Це усуває
 * проблему, коли useEffect усередині редактора виконувався двічі
 * (React StrictMode dev mount/unmount/mount) і двічі інкрементував лічильник.
 */
export function useOpenTypeEditor() {
  const { byTypeId } = useMetadata();
  const { open, closeById } = useWindowStack();
  const hasRead = useHasReadAccess();
  const hasWrite = useHasWriteAccess();
  const toast = useToast();
  const errorDialog = useErrorDialog();
  const resolver = useDisplayResolver();

  return useCallback(function openTypeEditor(opts: {
    typeId: number;
    id: string | null;
    /** Зворотний виклик після успішного збереження (наприклад, reload каталога). */
    onSaved?: (createdId?: string) => void;
    /**
     * Викликається, коли вікно редактора фактично закрилося (будь-яким способом:
     * ✕/Esc/save/скасування). Викликач списку використовує це для синхронізації
     * адресного рядка. Дрилл-даун через пікери {@code onClosed} не передає — і
     * тому в URL не потрапляє.
     */
    onClosed?: () => void;
  }): void {
    const { typeId, id, onSaved, onClosed } = opts;

    const td = byTypeId(typeId);
    if (!td) {
      toast.error(`Type typeId=${typeId} is not registered in the system`);
      return;
    }

    // Жорстка перевірка доступу — централізована модалка.
    if (!hasRead(typeId)) {
      errorDialog.show({
        kind: "ACCESS_DENIED",
        status: 403,
        message: `Not enough rights to view «${td.pluralLabel}»`,
        requirements: [{
          typeId,
          typeLabel: td.singularLabel,
          requiredFlags: 0x01,
          scope: "REPO",
          fieldName: null,
        }],
      });
      return;
    }

    // Prefetch коду — ДО відкриття вікна. Це не race-prone, бо AsyncIIFE
    // викликається до open(), а open() — тільки після того, як код отримано.
    // Якщо prefetch провалився, відкриваємо з порожнім кодом (graceful degradation).

    /**
     * Сповіщення про успішний flush агрегата в БД.
     *
     * <p>Показуємо невелику плашку справа знизу з ім'ям збереженого об'єкта та
     * його <b>кликабельним ссилковим представленням</b> — клік відкриває форму
     * редагування цього ж об'єкта (через рекурсивний {@code openTypeEditor}).
     *
     * <p>Ссилкове представлення тягнемо через {@code POST /api/references/resolve}
     * (бекенд-підтримка «ссилкового представлення збереженого об'єкта»), попередньо
     * інвалідуючи кеш резолвера, щоб показати свіжий display. Якщо id невідомий
     * (рідкісний випадок) — показуємо плашку без посилання.
     */
    const announceSaved = (savedId?: string) => {
      if (!savedId) {
        toast.success(`Saved: ${td.singularLabel}`);
        return;
      }
      // Свіжий display: інвалідуємо кеші, потім резолвимо ссилку.
      resolver.invalidate(typeId, savedId);
      referencesApi.resolve([{ typeId, id: savedId }])
        .then((refs) => {
          const ref = refs[0];
          const display = ref && ref.accessible !== false && ref.display
            ? ref.display
            : td.singularLabel;
          toast.success(`Saved: ${td.singularLabel}`, {
            label: display,
            onClick: () => openTypeEditor({ typeId, id: savedId }),
          });
        })
        .catch(() => {
          // Резолв не вдався — все одно даємо посилання (хоч і з родовою назвою).
          toast.success(`Saved: ${td.singularLabel}`, {
            label: td.singularLabel,
            onClick: () => openTypeEditor({ typeId, id: savedId }),
          });
        });
    };

    const proceed = (prefetchedCode: string | null) => {
      const Editor = getEditor(typeId);
      const winId = nextWindowId();

      if (Editor) {
        open({
          id: winId,
          title: id == null
            ? `Create: ${td.singularLabel}`
            : `Edit: ${td.singularLabel}`,
          width: getEditorWidth(typeId),
          onClosed,
          content: (
            <Editor
              id={id}
              prefetchedCode={prefetchedCode}
              onClose={() => closeById(winId)}
              onSaved={(createdId) => {
                invalidateRefAutocompleteCache(`ref-${td.slug}`);
                announceSaved(createdId ?? (id ?? undefined));
                onSaved?.(createdId);
                closeById(winId);
              }}
            />
          ),
        });
        return;
      }

      // Fallback: generic полевий редактор з метаданих.
      const writable = hasWrite(typeId);
      open({
        id: winId,
        title: id == null
          ? `Create: ${td.singularLabel}`
          : `${td.singularLabel}`,
        width: "default",
        onClosed,
        content: (
          <GenericAggregateEditor
            typeId={typeId}
            id={id}
            prefetchedCode={prefetchedCode}
            mode={writable ? "edit" : "view"}
            onClose={() => closeById(winId)}
            onSaved={(savedId) => {
              invalidateRefAutocompleteCache(`ref-${td.slug}`);
              announceSaved(savedId ?? (id ?? undefined));
              onSaved?.(savedId);
              closeById(winId);
            }}
          />
        ),
      });
    };

    // Для нових елементів довідника — prefetch коду. Для редагування / не-довідників —
    // одразу відкриваємо вікно.
    if (id == null && td.isReference) {
      // Не блокуємо UI до запиту; навіть якщо запит висить ~200ms — це нормально.
      // Користувач бачить миттєвий feedback на кнопці (можна додати лоадер, якщо
      // хочеться); вікно відкривається з уже отриманим кодом.
      referencesApi.nextCode(td.slug)
        .then(r => proceed(r.code))
        .catch(() => proceed(null));  // graceful: відкриваємо з порожнім кодом
    } else {
      proceed(null);
    }
  }, [byTypeId, hasRead, hasWrite, open, closeById, toast, errorDialog, resolver]);
}
