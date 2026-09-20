import { useEffect, useMemo, useState } from "react";
import { WorkbenchClient, type WorkbenchClientConfig, ApiException } from "./api/client";
import { WorkbenchContext } from "./proxy/WorkbenchContext";
import type { ClientAccessPolicy } from "./proxy/WorkbenchContext";
import type { Capabilities, WorkbenchActionName } from "./types";
import { Workbench } from "./components/Workbench";
import "./styles.css";

export interface WorkbenchModuleProps {
  /** Базовый путь API. Standalone: "/api/sqlworkbench". Встраивание через прокси host'а: напр. "/host/sqlworkbench". */
  baseUrl?: string;
  /** Провайдер Bearer-токена host-приложения. */
  getToken?: WorkbenchClientConfig["getToken"];
  /**
   * Клиентская политика доступа host'а: ДОПОЛНИТЕЛЬНО к серверным capabilities
   * скрывает/блокирует действия (false = запрещено). Серверная проверка остаётся
   * источником истины — это лишь UX-слой.
   */
  accessPolicy?: ClientAccessPolicy;
  /** Глобальный обработчик ошибок (для тостов/логирования host'а). */
  onError?: (err: ApiException) => void;
  /** Доступные вкладки (по умолчанию все). */
  tabs?: Array<"connections" | "explorer" | "editor" | "content" | "builder">;
}

/**
 * PROXY-ПРЕДСТАВЛЕНИЕ — единый встраиваемый компонент модуля SpringBootCRM.
 *
 * Host-приложение монтирует его в любой свой маршрут:
 *   <WorkbenchModule baseUrl="/host/sqlworkbench" getToken={() => auth.token}
 *                accessPolicy={{ DELETE_DATA: false, MANAGE_DATASOURCES: false }} />
 *
 * Компонент сам подтягивает серверные capabilities и пересекает их с клиентской
 * политикой, управляя видимостью вкладок и кнопок. Так программист host'а
 * полностью контролирует ограничение доступа к API и UI.
 */
export function WorkbenchModule(props: WorkbenchModuleProps) {
  const baseUrl = props.baseUrl ?? "/api/sqlworkbench";

  const client = useMemo(
    () => new WorkbenchClient({ baseUrl, getToken: props.getToken, onError: props.onError }),
    [baseUrl, props.getToken, props.onError],
  );

  const [capabilities, setCapabilities] = useState<Capabilities>({});
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let alive = true;
    setLoading(true);
    client.capabilities()
      .then((c) => { if (alive) setCapabilities(c); })
      .catch(() => { if (alive) setCapabilities({}); })
      .finally(() => { if (alive) setLoading(false); });
    return () => { alive = false; };
  }, [client]);

  const can = useMemo(() => {
    const policy = props.accessPolicy ?? {};
    return (action: WorkbenchActionName): boolean => {
      const serverAllows = capabilities[action] ?? true; // нет данных → не блокируем на клиенте
      const hostAllows = policy[action] ?? true;
      return serverAllows && hostAllows;
    };
  }, [capabilities, props.accessPolicy]);

  return (
    <WorkbenchContext.Provider value={{ client, can, capabilities, loading }}>
      <Workbench tabs={props.tabs} />
    </WorkbenchContext.Provider>
  );
}

export default WorkbenchModule;
export { ApiException } from "./api/client";
export type { ClientAccessPolicy } from "./proxy/WorkbenchContext";
