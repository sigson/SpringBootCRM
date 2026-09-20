// Локальні знання санкціонованого override-у «Ролі доступу» (typeId=9100).
//
// Це єдиний модуль фронтенду, якому дозволено знати форму бізнес-об'єкта
// AccessRole та його REST-контракт. Спільні шари (types/api.ts, endpoints.ts)
// про нього не знають: override прив'язується до ядра виключно через typeId
// (registerEditor(9100, AccessRoleEditor)).

import { api } from "../api/client";
import type { UUID } from "../types/api";

/** Шаблон доступу ролі: глобальні прапорці + перевизначення по типах. */
export interface AccessTemplateDto {
  globalFlags: number;
  typeFlags: Record<string, number>;
}

export interface AccessRoleDto {
  id: UUID;
  code: string;
  name: string;
  description: string | null;
  accessTemplate: AccessTemplateDto;
  enabled: boolean;
  createdBy?: string | null;
  updatedBy?: string | null;
}

/** REST-клієнт довідника ролей. Живе разом із override-ом, а не в спільному endpoints.ts. */
export const accessRolesApi = {
  list: () => api.get<AccessRoleDto[]>("/api/access-roles"),
  get: (id: UUID) => api.get<AccessRoleDto>(`/api/access-roles/${id}`),
  create: (req: { code: string; name: string; description?: string;
                   accessTemplate: AccessTemplateDto;
                   enabled: boolean }) =>
    api.post<AccessRoleDto>("/api/access-roles", req),
  update: (id: UUID, req: { name?: string; description?: string;
                              accessTemplate?: AccessTemplateDto;
                              enabled?: boolean }) =>
    api.put<AccessRoleDto>(`/api/access-roles/${id}`, req),
  delete: (id: UUID) => api.delete<void>(`/api/access-roles/${id}`),
};
