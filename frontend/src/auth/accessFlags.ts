import type { UserDto } from "../types/api";

/**
 * <h2>Єдине дзеркало backend-логіки {@code domain.core.access.AccessFlags}.</h2>
 *
 * <p>Один модуль-дзеркало бітової логіки доступу. Узгодження з backend'ом
 * (домен-файл {@code AccessFlags.java}) робиться рівно тут, в одному місці.
 *
 * <p><b>Це лише UI-логіка.</b> Реальний enforcement — на backend'і
 * ({@code AccessResolver}); див. примітку про апроксимацію в {@code permissions.ts}.
 */

/**
 * Бітова шкала прав — синхронна з backend's {@code AccessFlags}.
 * <ul>
 *   <li>READ          = 0x01 — звичайне читання</li>
 *   <li>WRITE_INSERT  = 0x02 — створення нових записів (insert)</li>
 *   <li>ADMIN_READ    = 0x04 — обхід instance-level при читанні</li>
 *   <li>ADMIN_WRITE   = 0x08 — обхід instance-level при записі</li>
 *   <li>ROOT_READ     = 0x10 — додатково обходить field-level HIDDEN/WRITE_ONLY</li>
 *   <li>ROOT_WRITE    = 0x20 — обходить геть усе</li>
 *   <li>WRITE_UPDATE  = 0x40 — зміна/видалення наявних записів (update/delete)</li>
 *   <li>WRITE = WRITE_INSERT | WRITE_UPDATE = 0x42 (комбінований ярлик)</li>
 * </ul>
 */
export const FLAG = {
  NONE: 0x00,
  READ: 0x01,
  WRITE_INSERT: 0x02,
  ADMIN_READ: 0x04,
  ADMIN_WRITE: 0x08,
  ROOT_READ: 0x10,
  ROOT_WRITE: 0x20,
  WRITE_UPDATE: 0x40,
  /** Комбінований «WRITE як операція» — WRITE_INSERT | WRITE_UPDATE. */
  WRITE: 0x02 | 0x40,
} as const;

/**
 * Розгортає прапори за правилом імплікації — дзеркало {@code AccessFlags.expand}.
 * <pre>
 *   ROOT_WRITE  ⇒ ROOT_READ | ADMIN_WRITE | ADMIN_READ | WRITE | READ   (WRITE = 0x42)
 *   ROOT_READ   ⇒ ADMIN_READ | READ
 *   ADMIN_WRITE ⇒ ADMIN_READ | WRITE | READ                             (WRITE = 0x42)
 *   ADMIN_READ  ⇒ READ
 * </pre>
 *
 * <p><b>Важливо:</b> {@code WRITE_INSERT} і {@code WRITE_UPDATE} НЕ імплікують
 * один одного — це навмисно (видача «лише INSERT» не означає «UPDATE»). Лише
 * {@code ADMIN_WRITE}/{@code ROOT_WRITE} включають обидва. Ідемпотентна:
 * {@code expandFlags(expandFlags(x)) === expandFlags(x)}.
 */
export function expandFlags(flags: number): number {
  let r = flags;
  if (r & FLAG.ROOT_WRITE)  r |= FLAG.ROOT_READ | FLAG.ADMIN_WRITE | FLAG.ADMIN_READ | FLAG.WRITE | FLAG.READ;
  if (r & FLAG.ROOT_READ)   r |= FLAG.ADMIN_READ | FLAG.READ;
  if (r & FLAG.ADMIN_WRITE) r |= FLAG.ADMIN_READ | FLAG.WRITE | FLAG.READ;
  if (r & FLAG.ADMIN_READ)  r |= FLAG.READ;
  return r;
}

/** Істина, якщо у flags присутні ВСІ біти required (з урахуванням імплікації).
 *  Дзеркало {@code AccessFlags.has}. */
export function hasFlags(flags: number, required: number): boolean {
  return (expandFlags(flags) & required) === required;
}

/**
 * Проста перевірка admin-прав за глобальними прапорами користувача.
 * Збігається з backend-логікою: admin = присутній біт {@code ADMIN_READ}
 * (з урахуванням імплікації від ROOT-/ADMIN_WRITE через {@link expandFlags}).
 */
export function isAdmin(user: UserDto | null): boolean {
  if (!user) return false;
  return (expandFlags(user.accessGlobalFlags ?? 0) & FLAG.ADMIN_READ) !== 0;
}
