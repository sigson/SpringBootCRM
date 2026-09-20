# Інтеграція модуля «SQL Workbench» у SpringBootCRM

Додано **незалежний, відключуваний, видаляємий-однією-папкою** адмін-розділ
поряд із «Генерацією даних». Усередині — портована клієнт-серверна версія
**SQLeo Visual Query Builder**: робота напряму з SQL та сирими таблицями БД у
браузері (візуальний конструктор, SQL-редактор, перегляд/CRUD рядків).

## Виконані вимоги

| Вимога | Як виконано |
|---|---|
| Розділ лише для адмінів, поряд із генерацією даних | Плитка на дашборді поряд із «Генерація даних»; маршрут `/admin/sql-workbench`; guard `isAdmin` на фронті + admin-only-політика на бекенді |
| Робота з SQL та сирими таблицями прямо в браузері | Перенесений UI SQLeo (вкладки: Соединения / Обозреватель / SQL-редактор / Данные (CRUD) / Конструктор) |
| Модуль відключуваний-незалежний | Один прапор `sqlworkbench.enabled` (бекенд) + ліниве підвантаження (фронт) |
| Не впливає на основну кодову базу | `app.springbootcrm.*` не імпортує нічого з модуля; зв'язок лише модуль→хост |
| Якщо фронт не підвантажив модуль — розділу немає | `React.lazy` + error-boundary + проба-хук `useSqlWorkbenchAvailable` |
| Бекенд — окрема видаляєма папка модулів, що не аффектить `app.springbootcrm` | Уся логіка в `app/modules/sqlworkbench`; видалення папки безпечне |

## Бекенд

Папка `backend/src/main/java/app/modules/sqlworkbench/` (видаляється цілком):

* `config/SqlWorkbenchModule` — **єдиний вмикач**: `@ConditionalOnProperty("sqlworkbench.enabled")`
  + `@ComponentScan` модуля. `enabled=false` → жодного біна.
* `bridge/AdminOnlyAccessPolicyConfig` — **єдина** точка зв'язку з хостом:
  делегує доступ у `app.springbootcrm.auth.AdminCheck` (читання=admin, запис=root).
* `bridge/HostDataSourceRegistrar` — авто-реєструє основну БД хоста як датасорс
  `main` (read-only), щоб адмін одразу бачив реальні таблиці SpringBootCRM.
* `web/*` — REST під `/api/sqlworkbench/**` (обслуговує security-цепочка хоста).
* `web/SqlWorkbenchExceptionHandler` — advice, обмежений пакетом модуля.
* `service/*`, `querymodel/*`, `security/*`, `dto/*` — ядро SQLeo (datasources,
  метадані, SELECT-пагінація, CRUD, модель запиту + формувач SQL, ACL-каркас).

Єдина правка в хості — `SpringBootCrmApplication`: `@ComponentScan` з рядковим
`excludeFilters`, що виключає бінові підпакети `app.modules.*` (крім `config`).
Рядок коректно «виключає» навіть видалений пакет.

**Конфіг** (`application.yml`):
```yaml
sqlworkbench:
  enabled: ${SQLWORKBENCH_ENABLED:true}   # головний вмикач
  base-path: /api/sqlworkbench
  register-host-datasource: true          # основна БД як read-only датасорс
  host-datasource-id: main
```

## Фронтенд

* `frontend/src/modules/sqlworkbench/` — перенесений вбудовуваний модуль
  (`<WorkbenchModule>`); усі CSS-селектори обмежені префіксом `.springbootcrm-root`, тож
  стилі не протікають у хост і навпаки.
* `frontend/src/modules/useSqlWorkbenchAvailable.ts` — пробний `import()`:
  плитка показується лише якщо модуль реально підвантажується.
* `frontend/src/pages/SqlWorkbenchPage.tsx` — ліниве підвантаження за
  error-boundary: помилка завантаження → редірект на дашборд (розділ зникає).
* Правки: `App.tsx` (маршрут), `DashboardPage.tsx` (плитка поряд із генерацією).

## Як вимкнути

* **Тимчасово:** `SQLWORKBENCH_ENABLED=false` (або `sqlworkbench.enabled: false`).
  Бекенд-біна не піднімаються; фронт отримує 404 на capabilities; розділ зникає.

## Як видалити повністю

1. Видалити теку `backend/src/main/java/app/modules/sqlworkbench`.
2. Видалити теку `frontend/src/modules/sqlworkbench`, файли
   `frontend/src/pages/SqlWorkbenchPage.tsx`,
   `frontend/src/modules/useSqlWorkbenchAvailable.ts`, і прибрати 3 пов'язані
   рядки в `App.tsx` / `DashboardPage.tsx`.

Бекенд після кроку 1 компілюється і працює без змін у `app.springbootcrm` — рядковий
`excludeFilters` коректно посилається на неіснуючий пакет. (За бажання можна
прибрати й сам `@ComponentScan` з `SpringBootCrmApplication`, повернувши
`@SpringBootApplication(scanBasePackages={"domain","app"})`.)

## Походження та ліцензія

Похідна робота від SQLeo Visual Query Builder (GPL). Зберігає ліцензію оригіналу.
