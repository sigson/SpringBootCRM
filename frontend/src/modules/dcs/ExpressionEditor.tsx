import { useEffect, useRef, useState } from "react";
import type { DcsClient } from "./api";
import type { AvailableField } from "./types";
import { Banner, Btn, Spacer, Toolbar } from "./ui";

/**
 * <h2>Редактор выражения языка компоновки.</h2>
 *
 * <p>Проверка идёт на сервере, а не в браузере: грамматика и набор функций живут в
 * движке, и вторая их реализация на фронтенде неизбежно разошлась бы с первой. Запрос
 * отправляется с задержкой после остановки набора, поэтому подсветка ошибки появляется
 * почти сразу, но не на каждую букву.
 *
 * <p>Палитра снизу вставляет поля и функции в позицию курсора — набирать
 * {@code Сумма(Продажи.Сумма)} руками, помня точные имена полей, неудобно и чревато
 * опечатками, которые всплывут только при формировании.
 */
export function ExpressionEditor({
  value, onChange, client, fields, rows = 4, placeholder, showPalette = true,
}: {
  value: string;
  onChange: (v: string) => void;
  client: DcsClient;
  fields: AvailableField[];
  rows?: number;
  placeholder?: string;
  showPalette?: boolean;
}) {
  const [check, setCheck] = useState<{ valid: boolean; message?: string | null } | null>(null);
  const ref = useRef<HTMLTextAreaElement | null>(null);

  useEffect(() => {
    if (!value.trim()) { setCheck(null); return; }
    let alive = true;
    const timer = setTimeout(() => {
      client.validateExpression(value)
        .then((r) => { if (alive) setCheck({ valid: r.valid, message: r.message }); })
        .catch(() => { if (alive) setCheck(null); });
    }, 400);
    return () => { alive = false; clearTimeout(timer); };
  }, [value, client]);

  function insert(text: string) {
    const el = ref.current;
    if (!el) { onChange(value + text); return; }
    const start = el.selectionStart ?? value.length;
    const end = el.selectionEnd ?? value.length;
    const next = value.slice(0, start) + text + value.slice(end);
    onChange(next);
    // Возвращаем курсор за вставленный фрагмент, чтобы можно было продолжать набор.
    requestAnimationFrame(() => {
      el.focus();
      const pos = start + text.length;
      el.setSelectionRange(pos, pos);
    });
  }

  return (
    <div>
      <textarea
        ref={ref}
        className={`dcs-textarea dcs-textarea--mono${check && !check.valid ? " dcs-input--invalid" : ""}`}
        rows={rows}
        value={value}
        placeholder={placeholder ?? "Сумма(Продажи.Сумма) / Количество(Продажи.Код)"}
        onChange={(e) => onChange(e.target.value)}
      />
      {check && !check.valid && <Banner kind="error">{check.message}</Banner>}
      {check && check.valid && (
        <div className="dcs-muted" style={{ fontSize: 10, marginTop: 2 }}>The expression is valid</div>
      )}

      {showPalette && (
        <Toolbar>
          <select
            className="dcs-input"
            style={{ maxWidth: 220 }}
            value=""
            onChange={(e) => { if (e.target.value) insert(e.target.value); }}
          >
            <option value="">Insert a field…</option>
            {fields.map((f) => (
              <option key={f.id} value={f.id}>{f.title} ({f.id})</option>
            ))}
          </select>
          <select
            className="dcs-input"
            style={{ maxWidth: 220 }}
            value=""
            onChange={(e) => { if (e.target.value) insert(e.target.value); }}
          >
            <option value="">Insert a function…</option>
            {FUNCTIONS.map((g) => (
              <optgroup key={g.group} label={g.group}>
                {g.items.map((fn) => (
                  <option key={fn.snippet} value={fn.snippet}>{fn.label}</option>
                ))}
              </optgroup>
            ))}
          </select>
          <Spacer />
          <Btn small kind="ghost" onClick={() => onChange("")}>Clear</Btn>
        </Toolbar>
      )}
    </div>
  );
}

/** Палитра функций языка. Имена принимаются и по-русски, и по-английски. */
const FUNCTIONS = [
  {
    group: "Aggregates",
    items: [
      { label: "Сумма(…)", snippet: "Сумма()" },
      { label: "Количество(…)", snippet: "Количество()" },
      { label: "КоличествоРазличных(…)", snippet: "КоличествоРазличных()" },
      { label: "Минимум(…)", snippet: "Минимум()" },
      { label: "Максимум(…)", snippet: "Максимум()" },
      { label: "Среднее(…)", snippet: "Среднее()" },
    ],
  },
  {
    group: "Grouping level",
    items: [
      { label: "ВычислитьВыражение(«…», «ОбщийИтог»)",
        snippet: "ВычислитьВыражение(\"\", \"ОбщийИтог\")" },
      { label: "ВычислитьВыражение(«…», «Иерархия»)",
        snippet: "ВычислитьВыражение(\"\", \"Иерархия\")" },
      { label: "Уровень()", snippet: "Уровень()" },
      { label: "НомерПоПорядку()", snippet: "НомерПоПорядку()" },
    ],
  },
  {
    group: "Conditions",
    items: [
      { label: "ВЫБОР КОГДА … ТОГДА … ИНАЧЕ … КОНЕЦ",
        snippet: "ВЫБОР КОГДА  ТОГДА  ИНАЧЕ  КОНЕЦ" },
      { label: "ЕстьNULL(…, 0)", snippet: "ЕстьNULL(, 0)" },
    ],
  },
  {
    group: "Strings and dates",
    items: [
      { label: "Представление(…)", snippet: "Представление()" },
      { label: "Подстрока(…, 1, 10)", snippet: "Подстрока(, 1, 10)" },
      { label: "СокрЛП(…)", snippet: "СокрЛП()" },
      { label: "Год(…)", snippet: "Год()" },
      { label: "Квартал(…)", snippet: "Квартал()" },
      { label: "Месяц(…)", snippet: "Месяц()" },
      { label: "НачалоПериода(…, «месяц»)", snippet: "НачалоПериода(, \"месяц\")" },
      { label: "Округл(…, 2)", snippet: "Округл(, 2)" },
    ],
  },
];
