// Локальні знання санкціонованого override-у «Інтерфейси» (typeId=9300).
//
// Єдиний модуль фронтенду, якому дозволено знати форму бізнес-об'єкта
// InterfaceLayout та його REST-контракт, а також декларативну мову дерева
// layout'у ({@link LayoutNode}), якою оперує лише конструктор інтерфейсу.
// Спільні шари (types/navigation.ts, api/navigation.ts) лишають у себе тільки
// протокол навігації ({@code NavNode}/{@code navigationApi}/{@code ToolDescriptor}),
// яким користується ядро (SideNav/NavProvider). Override прив'язується до ядра
// через typeId — registerEditor(9300, InterfaceLayoutEditor).

import { api } from "../api/client";
import type { UUID } from "../types/api";
import type { NavKind } from "../types/navigation";

/** Вузол дерева layout'у (вхідна декларація; дзеркало app.springbootcrm.interfaces.LayoutNode). */
export interface LayoutNode {
  kind: NavKind;
  title?: string | null;
  icon?: string | null;
  typeId?: number | null;
  tool?: string | null;
  children?: LayoutNode[];
}

export interface InterfaceLayoutDto {
  id: UUID;
  code: string;
  name: string;
  layout: LayoutNode[];
  enabled: boolean;
  createdBy?: string | null;
  updatedBy?: string | null;
}

/** CRUD довідника InterfaceLayout (typeId=9300). Запис — лише admin (enforce на backend'і). */
export const interfaceLayoutsApi = {
  list: () => api.get<InterfaceLayoutDto[]>("/api/interface-layouts"),
  get: (id: UUID) => api.get<InterfaceLayoutDto>(`/api/interface-layouts/${id}`),
  create: (req: { code?: string; name: string; layout: LayoutNode[]; enabled: boolean }) =>
    api.post<InterfaceLayoutDto>("/api/interface-layouts", req),
  update: (id: UUID, req: { name?: string; layout?: LayoutNode[]; enabled?: boolean }) =>
    api.put<InterfaceLayoutDto>(`/api/interface-layouts/${id}`, req),
  delete: (id: UUID) => api.delete<void>(`/api/interface-layouts/${id}`),
};
