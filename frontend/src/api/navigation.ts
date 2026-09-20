// API навігації (протокол). Знає тільки дерево навігації та перелік
// інструментів — те, чим оперує ядро (SideNav/NavProvider). Знання про сам
// довідник InterfaceLayout (його DTO, CRUD-клієнт, мову layout'у) винесено в
// editors/interfaceLayout.api.ts разом із його override-редактором.

import { api } from "./client";
import type { NavNode, ToolDescriptor } from "../types/navigation";

/** Навігація для поточного користувача + перелік інструментів (для конструктора). */
export const navigationApi = {
  tree: () => api.get<NavNode[]>("/api/navigation"),
  tools: () => api.get<ToolDescriptor[]>("/api/navigation/tools"),
};
