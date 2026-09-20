import { useEffect } from "react";
import { Navigate, useParams } from "react-router-dom";
import { useMetadata } from "../metadata/MetadataProvider";
import { useErrorDialog } from "../components/ErrorDialog";
import { getFreeController } from "../editors/freeControllerRegistry";

/**
 * <h2>Єдиний диспетчер «вільних контролерів».</h2>
 *
 * <p>Маршрут {@code /tool/:key} більше не має захардкоджених прив'язок до
 * конкретних сторінок. Замість цього він:
 * <ol>
 *   <li>знаходить тип у метаданих за slug'ом ({@code key});</li>
 *   <li>переконується, що це NONSTANDARD-тип (вільний контролер);</li>
 *   <li>шукає зареєстроване представлення за його {@code typeId} у
 *       {@code freeControllerRegistry};</li>
 *   <li>рендерить його — або показує <b>модалку 404</b>, якщо типу немає,
 *       він не NONSTANDARD, або для нього не зареєстровано хук.</li>
 * </ol>
 *
 * <p>Так додавання/видалення вільного контролера не потребує правок роутингу:
 * бекенд оголошує тип (typeId + NONSTANDARD), фронтенд або має хук — або чесно
 * віддає 404.
 */
export function FreeControllerPage() {
  const { key } = useParams();
  const { bySlug, ready } = useMetadata();
  const errorDialog = useErrorDialog();

  const td = ready && key ? bySlug(key) : null;
  const Component = td ? getFreeController(td.typeId) : null;

  // 404, якщо: метадані готові, але типу немає / він не NONSTANDARD / немає хука.
  const notFound = ready && (!td || td.representation !== "NONSTANDARD" || !Component);

  useEffect(() => {
    if (notFound) {
      errorDialog.show({
        kind: "NOT_FOUND",
        status: 404,
        message: `Section «${key}» unavailable or has no display.`,
      });
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [notFound, key]);

  if (!ready) return <div className="empty-state">Loading…</div>;
  if (notFound || !Component) return <Navigate to="/" replace />;

  return <Component />;
}
