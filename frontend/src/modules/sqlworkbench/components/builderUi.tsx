import type { ReactNode } from "react";
import type { SourceKind } from "../querymodel/builderModel";
import type { SourceSchema } from "../querymodel/referenceModel";

/** Окружение редактора: доступные источники и резолвер колонок (через замыкание сверху). */
export interface EditorEnv {
  candidates: { schema?: string; name: string; kind: "table" | "temp" }[];
  columnsOf: (name: string, kind: SourceKind) => string[];
  ensureMeta: (name: string, schema?: string) => void;
  /** Производная ссылочная схема ВТ (по полям её createTemp-оператора): скаляры +
   * ссылочные реквизиты — чтобы ссылочный пайплайн работал и над ВТ. null — не ВТ. */
  tempSchema?: (name: string) => SourceSchema | null;
}

/** Универсальное модальное окно поверх всего (использует host-стили .subwindow). */
export function Modal({
  title, width = "wide", onClose, footer, children, zIndex = 1000,
}: {
  title: string;
  width?: "narrow" | "default" | "wide" | "full";
  onClose: () => void;
  footer?: ReactNode;
  children: ReactNode;
  /**
   * z-index подложки. По умолчанию 1000 (поверх app-модалок). Для окон, внутри
   * которых открываются штатные ссылочные пикеры (WindowStack, z-index 100..199),
   * нужно значение НИЖЕ стека пикеров — иначе picker откроется ПОД модалкой.
   */
  zIndex?: number;
}) {
  return (
    <div className="subwindow-backdrop" style={{ zIndex }} onClick={onClose}>
      <div className={`subwindow subwindow--${width}`} onClick={(e) => e.stopPropagation()}>
        <div className="subwindow__header">
          <div className="subwindow__title-wrap">
            <h2 className="subwindow__title">{title}</h2>
          </div>
          <button className="icon-btn icon-btn--small" onClick={onClose}>✕</button>
        </div>
        <div className="subwindow__body">{children}</div>
        {footer && <div className="subwindow__footer">{footer}</div>}
      </div>
    </div>
  );
}
