import { useAuth } from "../auth/AuthProvider";
import { FLAG, expandFlags, isAdmin } from "./accessFlags";
import type { UserDto } from "../types/api";

/**
 * Хелпери для перевірки доступу поточного користувача на клієнті.
 *
 * <p><b>Це лише UI-фільтрація.</b> Реальний enforcement — на backend'і через
 * {@code AccessResolver}. Клієнтська перевірка потрібна щоб не показувати
 * в дашборді чи меню модулі, до яких користувач явно не має доступу.
 *
 * <p><b>Це навмисна апроксимація.</b> Тут рахуємо лише по
 * {@code globalFlags ∪ typeFlags[T]}. Клієнт НЕ знає про:
 * <ul>
 *   <li>per-instance ACL (доступ до конкретного запису, Hibernate-фільтри);</li>
 *   <li>автопроброс прав на табличні частини від власника-агрегата.</li>
 * </ul>
 * Тому кнопка може з'явитися/сховатися неточно — це очікувано. <b>Фінальне
 * рішення завжди за сервером</b> (так заявлено контрактом): навіть якщо клієнт
 * показав дію, бекенд відхилить її при відсутності реального дозволу
 * (UI-шар лише не показує явно недоступне). Тому ці хелпери НЕ слід
 * використовувати як єдину гарантію — лише для прибирання UI-шуму.
 *
 * <p>Бітові константи та логіка {@code expand} — у спільному модулі
 * {@link ./accessFlags} (єдине дзеркало backend's {@code AccessFlags}).
 * Реекспортуємо їх тут для зворотної сумісності зі старими імпортами.
 */

export { FLAG, expandFlags };

/** Підсумкові прапори користувача для типу T (globalFlags ∪ typeFlags[T]). */
function effectiveFlagsFor(user: UserDto | null, typeId: number): number {
  if (!user) return 0;
  const g = user.accessGlobalFlags ?? 0;
  const t = user.accessTypeFlags?.[String(typeId)] ?? 0;
  return expandFlags(g | t);
}

/** Чи має поточний користувач хоча б READ-доступ до типу. Admin завжди true. */
export function useHasReadAccess(): (typeId: number) => boolean {
  const { user } = useAuth();
  return (typeId: number) => {
    if (!user) return false;
    if (isAdmin(user)) return true;
    return (effectiveFlagsFor(user, typeId) & FLAG.READ) !== 0;
  };
}

/**
 * Чи має поточний користувач WRITE-доступ до типу.
 * «WRITE» тут — операційний ярлик: хоча б один з WRITE_INSERT / WRITE_UPDATE достатній
 * для показу edit-кнопок у UI (детальніше різрізнення — на backend'і).
 */
export function useHasWriteAccess(): (typeId: number) => boolean {
  const { user } = useAuth();
  return (typeId: number) => {
    if (!user) return false;
    if (isAdmin(user)) return true;
    const f = effectiveFlagsFor(user, typeId);
    return (f & (FLAG.WRITE_INSERT | FLAG.WRITE_UPDATE)) !== 0;
  };
}

/** Точкова перевірка: можна створювати записи цього типу? */
export function useHasInsertAccess(): (typeId: number) => boolean {
  const { user } = useAuth();
  return (typeId: number) => {
    if (!user) return false;
    if (isAdmin(user)) return true;
    return (effectiveFlagsFor(user, typeId) & FLAG.WRITE_INSERT) !== 0;
  };
}

/** Точкова перевірка: можна редагувати/видаляти записи цього типу? */
export function useHasUpdateAccess(): (typeId: number) => boolean {
  const { user } = useAuth();
  return (typeId: number) => {
    if (!user) return false;
    if (isAdmin(user)) return true;
    return (effectiveFlagsFor(user, typeId) & FLAG.WRITE_UPDATE) !== 0;
  };
}
