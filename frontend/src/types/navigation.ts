// Протокол навігації (backend: app.springbootcrm.navigation). Лишає у спільному шарі
// тільки те, чим оперує ядро (SideNav/NavProvider): розв'язане дерево навігації
// та перелік інструментів. Знання про довідник InterfaceLayout (його DTO та
// мову layout'у) винесено в editors/interfaceLayout.api.ts.

export type NavKind = "GROUP" | "OBJECT" | "TOOL";

/**
 * Розв'язаний (відфільтрований за правами на сервері) вузол навігації.
 * Фронт рендерить меню напряму з цього, не приймаючи рішень про видимість.
 */
export interface NavNode {
  /** Стабільний id: slug (OBJECT), ключ (TOOL), "grp:n" (GROUP). */
  id: string;
  kind: NavKind;
  label: string;
  icon: string | null;
  /** Маршрут фронта: "/o/{slug}" (OBJECT), "/tool/{key}" (TOOL), null (GROUP). */
  route: string | null;
  typeId: number | null;
  slug: string | null;
  tool: string | null;
  children: NavNode[];
}

/** Інструментальний контролер (GET /api/navigation/tools). */
export interface ToolDescriptor {
  key: string;
  label: string;
  icon: string;
  route: string;
  adminOnly: boolean;
}
