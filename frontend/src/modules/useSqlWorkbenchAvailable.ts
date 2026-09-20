import { useEffect, useState } from "react";

/**
 * Перевіряє, чи можна підвантажити фронтенд-модуль «SQL Workbench».
 *
 * <p>Робить пробний динамічний {@code import()} чанку модуля. Якщо імпорт
 * вдався — модуль присутній і працездатний → повертає {@code true} (плитка/
 * розділ показуються). Якщо ні (папку видалено, чанк відсутній, помилка) →
 * {@code false}, і розділ ніде не з'являється.
 *
 * <p>Якщо чанк не підвантажився — модуля немає, і розділ не показується вже на
 * рівні навігації, ще до переходу на сторінку.
 *
 * <p>Зверніни увагу: динамічний шлях у {@code import()} нижче навмисно
 * статичний-літерал, щоб Vite зміг створити окремий чанк. Якщо файли модуля
 * видалити, збірка це переживе (import просто впаде в runtime → catch).
 */
export function useSqlWorkbenchAvailable(): boolean {
  const [available, setAvailable] = useState(false);

  useEffect(() => {
    let alive = true;
    import("../modules/sqlworkbench/WorkbenchModule")
      .then((m) => { if (alive) setAvailable(typeof m?.WorkbenchModule === "function"); })
      .catch(() => { if (alive) setAvailable(false); });
    return () => { alive = false; };
  }, []);

  return available;
}
