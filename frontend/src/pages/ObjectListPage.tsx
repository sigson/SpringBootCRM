import { useParams } from "react-router-dom";
import { useMetadata } from "../metadata/MetadataProvider";
import { useHasReadAccess } from "../auth/permissions";
import { getListView } from "../editors/listRegistry";
import { ObjectList } from "../components/ObjectList";
import { Alert, PageHead } from "../components/Common";

/**
 * Єдиний маршрут перегляду списку об'єкта БД: {@code /o/{slug}} (1С-підхід —
 * мінімум маршрутів). Резолвить тип за slug, перевіряє READ-доступ і диспетчеризує:
 * <ul>
 *   <li>зареєстроване переозначення списку ({@code registerListView}) — якщо є;</li>
 *   <li>інакше — generic {@link ObjectList} з метаданих.</li>
 * </ul>
 *
 * <p>Дрилл-даун у конкретний запис відбувається модально (через {@code useOpenTypeEditor}),
 * без окремих {@code /:id}-маршрутів.
 */
export function ObjectListPage() {
  const { slug } = useParams<{ slug: string }>();
  const { bySlug, ready } = useMetadata();
  const hasRead = useHasReadAccess();

  if (!ready) {
    return <div className="page"><div className="empty-state">Loading…</div></div>;
  }

  const td = slug ? bySlug(slug) : null;
  if (!td) {
    return (
      <main className="page">
        <PageHead title="Unknown object" />
        <Alert kind="error">Type «{slug}» is not registered in the system.</Alert>
      </main>
    );
  }

  if (!hasRead(td.typeId)) {
    return (
      <main className="page">
        <PageHead title={td.pluralLabel} />
        <Alert kind="error">Not enough rights to view «{td.pluralLabel}».</Alert>
      </main>
    );
  }

  // Примусовий remount при зміні типу. Маршрут {@code /o/:slug}
  // переюзає той самий екземпляр {@code ObjectListPage}; без {@code key} React
  // зберігав попередній {@code ListView}/{@code usePagedRows} зі своїм кешем
  // сторінок. Оскільки нове джерело {@code clientPagedSource} стартує з тією ж
  // {@code version=0}, ефект скидання кешу (залежний від version/pageSize/query)
  // не спрацьовував — і на дані попереднього об'єкта (напр., Календаря)
  // «натягувались» колонки нового (напр., Користувачів). Ключ за typeId дає
  // повністю свіже піддерево на кожне перемикання.
  const Custom = getListView(td.typeId);
  if (Custom) return <Custom key={td.typeId} td={td} />;
  return <ObjectList key={td.typeId} td={td} />;
}
