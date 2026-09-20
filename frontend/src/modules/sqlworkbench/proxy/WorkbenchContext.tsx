import { createContext, useContext } from "react";
import type { WorkbenchClient } from "../api/client";
import type { Capabilities, WorkbenchActionName } from "../types";

/** Клиентская политика доступа: host может дополнительно скрывать действия поверх серверных capabilities. */
export type ClientAccessPolicy = Partial<Record<WorkbenchActionName, boolean>>;

export interface WorkbenchContextValue {
  client: WorkbenchClient;
  /** Эффективные возможности: серверные capabilities ∩ клиентская политика host'а. */
  can: (action: WorkbenchActionName) => boolean;
  capabilities: Capabilities;
  loading: boolean;
}

export const WorkbenchContext = createContext<WorkbenchContextValue | null>(null);

export function useSpringBootCrm(): WorkbenchContextValue {
  const ctx = useContext(WorkbenchContext);
  if (!ctx) throw new Error("useSpringBootCrm must be used inside <WorkbenchModule>");
  return ctx;
}
