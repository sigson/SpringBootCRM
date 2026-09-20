import { useCallback, useEffect, useLayoutEffect, useRef, useState } from "react";
import type { KeyboardEvent as ReactKeyboardEvent } from "react";
import type { PagedController } from "./rowSource";

export interface ScrollWindow {
  /** Перший видимий індекс рядка (з невеликим overscan'ом). */
  startIndex: number;
  /** Останній видимий індекс (включно). */
  endIndex: number;
  /** Висота spacer'а зверху (px, у стиснутому просторі контейнера). */
  topSpacer: number;
  /** Висота spacer'а знизу (px, у стиснутому просторі контейнера). */
  bottomSpacer: number;
}

interface Options {
  rowHeight: number;
  /** Скільки рядків домальовувати поза видимою областю (зверху й знизу). */
  overscan?: number;
  /** Затримка (мс) після зупинки прокрутки перед довантаженням чанків. */
  settleMs?: number;
  /**
   * Чи активний скрол-режим зараз. Коли стає {@code true} (напр., перемкнули
   * назад зі «сторінок» на «прокрутку») — повторно замовляємо початкове вікно,
   * щоб список не лишався порожнім до руху.
   */
  enabled?: boolean;
}

/**
 * Максимальна висота області прокрутки (px), яку ми дозволяємо контейнеру.
 *
 * <p><b>Навіщо.</b> Браузери не вміють коректно рендерити елементи, вищі за
 * певну межу (≈17.8M px у Firefox, ≈33.5M у Chrome/Safari). При наївному
 * віртуальному скролі висота = {@code total × rowHeight}: уже на ~500k рядків
 * (×38px ≈ 19M px) Firefox/Chrome починають «складати» область прокрутки —
 * {@code scrollTop} перестає лінійно відповідати індексу рядка, рядки злипаються,
 * а композитор GPU дає артефакти.
 *
 * <p><b>Рішення.</b> Обмежуємо фактичну висоту контейнера цією константою.
 * Доки {@code total × rowHeight ≤ MAX_CONTENT_PX} стискання немає (scale = 1) і
 * поведінка піксель-у-піксель збігається зі звичайним віртуальним списком.
 */
const MAX_CONTENT_PX = 6_000_000;

/**
 * Віртуальний скрол з chunked-довантаженням і захистом від браузерної межі висоти.
 *
 * <h3>Чому позиція тримається окремо від нативного scrollTop</h3>
 *
 * На великих наборах висота контейнера стискається до {@code MAX_CONTENT_PX}, а
 * «реальна» висота = {@code total × rowHeight} може бути в рази більшою. Раніше
 * джерелом істини був нативний {@code scrollTop}, а реальна позиція рахувалась як
 * {@code scrollTop × scale}. Проблема: на висоті ≈6M px браузер квантує
 * {@code scrollTop} (втрачає точність), і ця похибка множиться на {@code scale}
 * (≈6.3 на 1M рядків). Наслідки — «пружинення» колеса (мала дельта не змінює
 * квантований scrollTop) і стрибки на сотні рядків, інколи назад (кнопки
 * скролбара).
 *
 * <h3>Рішення: розділяємо два види прокрутки</h3>
 *
 * Джерелом істини стає <b>власна позиція у реальних px</b> ({@code realTopRef}):
 * <ul>
 *   <li><b>Колесо / клавіші (PageUp/PageDown/стрілки/Home/End)</b> рухають
 *       {@code realTopRef} напряму, у реальних піксельних координатах. Це дає
 *       точність нижче «мінімального кроку» ползунка: одна нотка колеса = рівно
 *       свої px незалежно від {@code scale}, без квантування й стрибків. Нативний
 *       {@code scrollTop} ми лише <i>підлаштовуємо</i> під нову позицію (щоб
 *       ползунок візуально їхав за вмістом).</li>
 *   <li><b>Перетягування ползунка скролбара</b> (будь-який нативний scroll-евент,
 *       який ми не ініціювали самі) — навпаки, <i>читаємо</i> нативний
 *       {@code scrollTop} і приймаємо {@code realTop = scrollTop × scale}. Тут
 *       працює мінімальний крок ползунка — жодної «чорної магії», як і просив
 *       сценарій: тягнемо туди, куди дозволяє нативний крок.</li>
 * </ul>
 *
 * Синхронізація двостороння: пройшовши колесом/клавішами достатньо, щоб ползунок
 * зрушив на свій піксель, ми оновлюємо нативний {@code scrollTop}; а схопивши
 * ползунок — переймаємо його позицію назад у {@code realTopRef}.
 *
 * <p>Позиція <b>зберігається</b> у реальних координатах і відновлюється після
 * перезавантаження даних (редагування елемента не «викидає» список нагору), а при
 * зменшенні набору (фільтр) — обрізається до нового діапазону.
 *
 * @returns поточне вікно рендеру + ref і обробники для scroll-контейнера
 */
export function useScrollPaging<T>(
    controller: PagedController<T>,
    opts: Options,
) {
  const { rowHeight, overscan = 6, settleMs = 150, enabled = true } = opts;
  const total = controller.total ?? 0;
  const scrollRef = useRef<HTMLDivElement | null>(null);
  const [viewportH, setViewportH] = useState(0);
  const settleTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  const realHeight = total * rowHeight;
  const contentHeight = Math.min(realHeight, MAX_CONTENT_PX);
  const maxScroll = Math.max(0, contentHeight - viewportH);
  const realMaxScroll = Math.max(0, realHeight - viewportH);
  // scale ≥ 1: скільки реальних px відповідає одному px стиснутого ползунка.
  // scale === 1 ⇒ стискання немає (звичайний віртуальний список без регресу).
  const scale = maxScroll > 0 ? Math.max(1, realMaxScroll / maxScroll) : 1;

  // --- Стабільні дзеркала у ref'ах (щоб обробники не перестворювались і не
  //     ловили stale-closure). ---
  const scaleRef = useRef(1);
  scaleRef.current = scale;
  const realMaxScrollRef = useRef(0);
  realMaxScrollRef.current = realMaxScroll;
  const viewportHRef = useRef(0);
  viewportHRef.current = viewportH;

  // Джерело істини: позиція верху viewport'а у РЕАЛЬНИХ px. Переживає reset кешу
  // (reload/зміна фільтра), бо хук не розмонтовується.
  const realTopRef = useRef(0);
  // Стан для ре-рендеру вікна (значення дублює realTopRef).
  const [realTop, setRealTopState] = useState(0);

  // Допуск (px стиснутого простору) для розрізнення «наш програмний запис
  // scrollTop» від «користувач тягне нативний ползунок». Наш запис лишає
  // el.scrollTop ≈ realTop/scale (з точністю до квантування браузера, ≤~2px) —
  // такі scroll-евенти ІГНОРУЄМО. Зсув більший за допуск означає перетягування
  // ползунка (або клік по доріжці/кнопці) — приймаємо його позицію.
  // Це замінює крихкий механізм programmaticRef+rAF, який давав гонку
  // (наш scroll-евент інколи прилітав після зняття прапора → realTop «зривало»
  // нагору або стрибало великими кроками).
  const SCROLL_TOLERANCE = 3;

  // Записати нову реальну позицію. {@code syncNative} = підлаштувати нативний
  // scrollTop під неї (для колеса/клавіш — щоб ползунок поїхав за вмістом, і щоб
  // вікно рядків опинилось у viewport'і контейнера, що скролиться нативно).
  const applyRealTop = useCallback((next: number, syncNative: boolean) => {
    const clamped = Math.max(0, Math.min(next, realMaxScrollRef.current));
    realTopRef.current = clamped;
    if (syncNative) {
      const el = scrollRef.current;
      if (el) {
        // Ставимо scrollTop рівно у наше «очікуване» значення. Згенерований цим
        // scroll-евент onScroll розпізнає як власний (comp ≈ realTop/scale) і
        // проігнорує — без жодних прапорів і rAF.
        el.scrollTop = scaleRef.current > 0 ? clamped / scaleRef.current : clamped;
      }
    }
    setRealTopState(clamped);
  }, []);

  // Довантаження сторінок навколо поточної реальної позиції (debounce).
  const scheduleSettle = useCallback(() => {
    if (settleTimer.current) clearTimeout(settleTimer.current);
    settleTimer.current = setTimeout(() => {
      const vh = viewportHRef.current;
      const pageSize = controller.pageSize;
      const realTopNow = realTopRef.current;
      const centerIndex = Math.floor((realTopNow + vh / 2) / rowHeight);
      const nearestPage = Math.round(centerIndex / pageSize);
      const lowerPage = Math.max(0, nearestPage - 1);
      const from = lowerPage * pageSize;
      const to = (nearestPage + 1) * pageSize - 1;
      controller.ensureRange(from, Math.max(from, to));
    }, settleMs);
  }, [controller, rowHeight, settleMs]);

  // Вимірюємо висоту viewport'а. Перезапускається при зміні {@code enabled}:
  // контейнер монтується заново, спостерігач треба переприв'язати.
  useEffect(() => {
    const el = scrollRef.current;
    if (!el) return;
    const measure = () => setViewportH(el.clientHeight);
    measure();
    const ro = new ResizeObserver(measure);
    ro.observe(el);
    return () => ro.disconnect();
  }, [enabled]);

  useEffect(() => {
    if (!enabled) return;
    const el = scrollRef.current;
    if (!el) return;
    setViewportH(el.clientHeight);
  }, [enabled]);

  // ── ПРОКРУТКА 1: нативний scroll-евент. Це або наш власний програмний запис
  //    scrollTop (колесо/клавіші/відновлення — comp ≈ realTop/scale, ІГНОРУЄМО),
  //    або перетягування нативного ползунка / клік по доріжці (зсув > допуску —
  //    приймаємо позицію ползунка «куди дозволяє його крок», без додаткової
  //    математики). Розрізняємо порівнянням з очікуваним comp — без прапорів.
  const onScroll = useCallback(() => {
    const el = scrollRef.current;
    if (!el) return;
    const comp = el.scrollTop;
    const scaleNow = scaleRef.current;
    const expected = scaleNow > 0 ? realTopRef.current / scaleNow : realTopRef.current;
    if (Math.abs(comp - expected) <= SCROLL_TOLERANCE) {
      // Наш власний запис або квантування браузера — realTop не чіпаємо.
      return;
    }
    // Справжня взаємодія з нативним ползунком: приймаємо його позицію (coarse).
    realTopRef.current = Math.max(0, Math.min(comp * scaleNow, realMaxScrollRef.current));
    setRealTopState(realTopRef.current);
    scheduleSettle();
  }, [scheduleSettle]);

  // ── ПРОКРУТКА 2: колесо. Рухаємо реальну позицію напряму (точність нижче
  //    кроку ползунка), нативний scrollTop підлаштовуємо для синхронізації.
  //
  //    Важливо: у React 18 синтетичний onWheel реєструється як PASSIVE, тож
  //    e.preventDefault() там — no-op. А нам конче треба придушити нативний
  //    скрол (інакше він зрушить квантований scrollTop і перезапише позицію).
  //    Тому вішаємо НАТИВНИЙ wheel-listener з {passive:false} (нижче, в ефекті).
  //    Логіку тримаємо в ref, щоб не перевішувати слухача на кожен рендер.
  const wheelHandlerRef = useRef<(e: WheelEvent) => void>(() => {});
  wheelHandlerRef.current = (e: WheelEvent) => {
    if (!enabled) return;
    // На малих наборах (scale=1) — рідна поведінка з імпульсом трекпада.
    if (scaleRef.current <= 1) return;
    // Горизонтальний намір лишаємо нативному скролу.
    if (Math.abs(e.deltaX) > Math.abs(e.deltaY)) return;
    e.preventDefault();
    let dyPx = e.deltaY;
    if (e.deltaMode === 1) dyPx = e.deltaY * rowHeight;          // рядки
    else if (e.deltaMode === 2) dyPx = e.deltaY * viewportHRef.current; // сторінки
    applyRealTop(realTopRef.current + dyPx, true);
    scheduleSettle();
  };

  // Прив'язка нативного non-passive wheel-listener'а до контейнера. Перезапуск
  // при зміні enabled (контейнер монтується заново при перемиканні режиму).
  useEffect(() => {
    if (!enabled) return;
    const el = scrollRef.current;
    if (!el) return;
    const handler = (e: WheelEvent) => wheelHandlerRef.current(e);
    el.addEventListener("wheel", handler, { passive: false });
    return () => el.removeEventListener("wheel", handler);
  }, [enabled]);

  // ── ПРОКРУТКА 3: клавіатура (PageUp/PageDown/стрілки/Home/End/Space).
  //    Як і колесо — точний рух реальної позиції нижче кроку ползунка.
  const onKeyDown = useCallback((e: ReactKeyboardEvent<HTMLDivElement>) => {
    if (!enabled || scaleRef.current <= 1) return;
    // Не перехоплюємо клавіші, коли фокус у полі вводу (фільтри в шапці тощо).
    const t = e.target as HTMLElement | null;
    if (t) {
      const tag = t.tagName;
      if (tag === "INPUT" || tag === "TEXTAREA" || tag === "SELECT" || t.isContentEditable) return;
    }
    const vh = viewportHRef.current;
    const pageDelta = Math.max(rowHeight, vh - rowHeight);
    let next: number | null = null;
    switch (e.key) {
      case "ArrowDown": next = realTopRef.current + rowHeight; break;
      case "ArrowUp":   next = realTopRef.current - rowHeight; break;
      case "PageDown":  next = realTopRef.current + pageDelta; break;
      case "PageUp":    next = realTopRef.current - pageDelta; break;
      case " ":         next = realTopRef.current + (e.shiftKey ? -pageDelta : pageDelta); break;
      case "Home":      next = 0; break;
      case "End":       next = realMaxScrollRef.current; break;
      default: return;
    }
    e.preventDefault();
    applyRealTop(next, true);
    scheduleSettle();
  }, [enabled, rowHeight, applyRealTop, scheduleSettle]);

  useEffect(() => () => {
    if (settleTimer.current) clearTimeout(settleTimer.current);
  }, []);

  // Початкове замовлення першого вікна (одразу, без чекання прокрутки).
  // Перезапускається при зміні enabled і total (нові дані після reset кешу).
  useEffect(() => {
    if (!enabled) return;
    const el = scrollRef.current;
    const vh = (el?.clientHeight || viewportH) || 0;
    if (vh > 0) {
      const visibleCount = Math.ceil(vh / rowHeight) + overscan;
      const startIdx = Math.max(0, Math.floor(realTopRef.current / rowHeight) - overscan);
      controller.ensureRange(startIdx, startIdx + visibleCount);
    } else {
      controller.ensureRange(0, controller.pageSize - 1);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [enabled, viewportH, rowHeight, controller.total]);

  // Реагує на зміну total (reload після редагування, зміна фільтра) і розмірів:
  //   • reload із тим самим набором → realTop у межах → відновлюємо точно (без
  //     стрибка нагору);
  //   • фільтр зменшив набір → realTop за межами → клампимо до realMaxScroll.
  // Після клампу синхронізуємо нативний scrollTop (programmatic).
  useLayoutEffect(() => {
    if (!enabled) return;
    const el = scrollRef.current;
    if (!el) return;
    const target = Math.max(0, Math.min(realTopRef.current, realMaxScroll));
    realTopRef.current = target;
    const comp = scaleRef.current > 0 ? target / scaleRef.current : target;
    // Синхронізуємо нативний scrollTop із позицією (onScroll розпізнає це як
    // власний запис за comp ≈ realTop/scale і не зреагує).
    if (Math.abs(el.scrollTop - comp) > 1) el.scrollTop = comp;
    setRealTopState(target);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [enabled, total, viewportH, maxScroll]);

  // ── Геометрія видимого вікна (у реальних координатах realTop). ──
  // compTop — НАМІРЕНА позиція нативного scrollTop (realTop/scale), а не зчитана
  // назад. Браузер тримає el.scrollTop саме тут (з точністю до квантування ≤~2px,
  // що дає субпіксельний зсув і НЕ накопичується). Так геометрія — чиста функція
  // від realTop, без гонок зі scroll-евентами/підстройками браузера.
  const compTop = scale > 0 ? realTop / scale : realTop;
  const visibleCount = Math.max(1, Math.ceil(viewportH / rowHeight));
  const firstIndex = Math.floor(realTop / rowHeight);

  // Видимі рядки мають РЕАЛЬНУ висоту (rowHeight), а контейнер стиснутий: над
  // viewport'ом у стиснутому просторі є лише compTop px. Тому реальна відстань
  // від першого відмальованого рядка до верху viewport'а
  // (realTop − startIndex×rowHeight) НЕ може перевищувати compTop — інакше
  // topSpacer вийшов би від'ємним, його кламп до 0 «складав» би рядки, і видиме
  // рухалося б у scale разів повільніше за realTop (ефект «проковзування» на
  // початку списку та різкий стрибок при виході з нього). Звідси нижня межа:
  //   startIndex ≥ (realTop − compTop) / rowHeight.
  // Прагнемо overscan зверху, але не нижче цієї межі і не вище firstIndex (інакше
  // не відмалюємо перший видимий рядок). Поза стиснутою зоною (scale=1, compTop=
  // realTop) межа = 0 ⇒ поведінка точно як у звичайного віртуального списку.
  const startMin = Math.ceil((realTop - compTop) / rowHeight);
  const startIndex = Math.min(
      firstIndex,
      Math.max(0, startMin, firstIndex - overscan),
  );
  const endIndex = total === 0
      ? -1
      : Math.min(total - 1, firstIndex + visibleCount + overscan);

  const winRealH = endIndex >= startIndex ? (endIndex - startIndex + 1) * rowHeight : 0;

  // Зсув верху вікна у СТИСНУТОМУ просторі контейнера так, щоб видимі рядки
  // збіглися з viewport'ом. За рахунок startMin topSpacer лишається ≥ 0 (без
  // «складання»), окрім субпіксельного залишку (<rowHeight) у перших кількох
  // рядках, де compTop фізично менший за зсув у рядку — там кламп невидимий.
  let topSpacer = compTop - (realTop - startIndex * rowHeight);
  const maxTop = Math.max(0, contentHeight - winRealH);
  topSpacer = Math.max(0, Math.min(topSpacer, maxTop));
  const bottomSpacer = Math.max(0, contentHeight - topSpacer - winRealH);

  const viewWindow: ScrollWindow = {
    startIndex,
    endIndex,
    topSpacer,
    bottomSpacer,
  };

  return { scrollRef, onScroll, onKeyDown, window: viewWindow, total, rowHeight };
}