# Модуль `app.modules.sqlworkbench` — SQL Workbench (порт SQLeo VQB)

Незалежний, **відключуваний** і **видаляється однією папкою** розділ для
адміністраторів: прямий доступ до SQL та сирих таблиць БД прямо в браузері —
візуальний конструктор запитів, SQL-редактор, перегляд/CRUD рядків.

Це клієнт-серверна переробка **SQLeo Visual Query Builder**, вбудована в SpringBootCRM
без впливу на основну кодову базу `app.springbootcrm`.

## Принципи ізоляції

1. **Односторонній зв'язок.** `app.springbootcrm.*` не імпортує нічого з
   `app.modules.*`. Єдина залежність — у зворотний бік: `bridge/` посилається на
   `app.springbootcrm.auth.AdminCheck`. Тож хост компілюється і працює без модуля.

2. **Вмикач.** Усе піднімає один клас — `config/SqlWorkbenchModule` —
   позначений `@ConditionalOnProperty("sqlworkbench.enabled")` (default `true`).
   `sqlworkbench.enabled=false` → жоден біна модуля не створюється.

3. **Власна security відсутня.** Ендпоінти `/api/sqlworkbench/**` обслуговує
   наявна security-цепочка хоста (автентифікація для `/api/**`). Тонке право
   (**admin-only**) забезпечує `bridge/AdminOnlyAccessPolicyConfig`, що делегує
   рішення в `AdminCheck`. Читання — admin; запис/керування датасорсами — root.

4. **Обробник помилок обмежений** контролерами модуля
   (`@RestControllerAdvice(basePackages="app.modules.sqlworkbench.web")`), тож не
   перехоплює помилки хоста.

5. **Основна БД — read-only.** `bridge/HostDataSourceRegistrar` автоматично
   реєструє пул хоста як датасорс `main` у режимі read-only (лише перегляд та
   SELECT). Для повного CRUD адмін реєструє окремий датасорс через UI.

## Як хост дізнається про модуль

`app.springbootcrm.SpringBootCrmApplication` сканує `domain` + `app`, але **виключає** бінові
підпакети `app.modules.*.(security|service|web|dto|querymodel|bridge)` регулярним
`excludeFilters`. Модуль активується лише через `config/SqlWorkbenchModule`
(пакет `…config` навмисно НЕ виключено), який сам робить `@ComponentScan` своїх
бінів — і тільки за `sqlworkbench.enabled=true`.

## Видалення

Видаліть теку `app/modules/sqlworkbench` цілком. Більше нічого робити не треба:

* у `app.springbootcrm` немає посилань на модуль → компіляція не ламається;
* `excludeFilters` у `SpringBootCrmApplication` заданий **рядком** → коректно «виключає»
  навіть неіснуючий пакет;
* фронтенд-розділ зникає сам (див. `frontend/src/modules/useSqlWorkbenchAvailable`).

## Пакет запитів: кілька запитів в одному рядку + зв'язки

Підпакет `querypack/` розв'язує задачу, що виникає поза конструктором одного запиту:
**джерел даних кілька, і їх треба з'єднати за налаштуванням зв'язків**.

* `QueryPack` — модель: іменовані запити (`PackedQuery`) + зв'язки (`PackLink`) +
  частини підсумкового запиту (select/where/group/having/order/limit).
* `QueryPackCodec` — текстова упаковка: уся структура живе **одним рядком**, службова
  розмітка схована в SQL-коментарі `--#`, тож рядок читається будь-яким SQL-редактором
  і переноситься копіпастою:

  ```sql
  --#query Sales
  SELECT d.id, d.currency, d.amount FROM deals d
  --#query Rates
  SELECT r.currency, r.rate FROM exchange_rates r
  --#link Sales -> Rates LEFT ON currency = currency
  --#select Sales.amount * Rates.rate AS base
  ```

  Звичайний одиночний SELECT без розмітки — теж валідний пакет (один набір).

* `QueryPackAssembler` — збирає пакет в один SQL: кожен набір стає CTE, зв'язки —
  ланцюжком JOIN'ів. Тексти наборів потрапляють у запит **без розбору** (повноцінний
  SQL-парсер модулю не потрібен). Ім'я CTE отримує префікс `qp_`, а до імені набору
  повертається аліасом (`FROM qp_Sales AS Sales`): інакше набір, названий як реальна
  таблиця, мовчки читав би таблицю замість виразу. Набір, не згаданий у жодному
  зв'язку, підключається `CROSS JOIN` — із попередженням, бо декартів добуток майже
  завжди означає забуту зв'язку.

Це той самий механізм, яким користується генератор звітів
(`app.modules.dcs`): «набори даних» схеми компоновки — це і є пакет запитів.

## API (база `/api/sqlworkbench`)

```
GET    /capabilities
GET    /health
GET    /datasources              POST /datasources   DELETE /datasources/{id}
POST   /datasources/{id}/test
GET    /datasources/{id}/metadata/schemas|tables|tables/{table}
POST   /datasources/{id}/query                 (тільки SELECT)
POST   /query-builder/sql?pretty=true          QueryModel → SQL
POST   /datasources/{id}/query-builder/run
POST   /query-pack/parse                       рядок → структура пакета
POST   /query-pack/encode                      структура → рядок
POST   /query-pack/sql                         пакет → один SQL (CTE + JOIN)
POST   /datasources/{id}/query-pack/run
GET/POST/PUT/DELETE /datasources/{id}/tables/{table}/rows
```

Усі ендпоінти проходять `AccessGuard.check(action, …)`; ідентифікатори
таблиць/колонок валідуються по метаданих і екрануються, значення — лише через
`PreparedStatement`.

## Походження та ліцензія

Похідна робота від SQLeo Visual Query Builder (GPL). Зберігає ліцензію оригіналу.
