# Сборка проекта локально

Тулчейн установлен портативно в `~/.local/toolchain` — **без root, без apt**,
системные пакеты не затронуты.

| Компонент | Версия            | Путь                            |
|-----------|-------------------|---------------------------------|
| JDK       | Temurin 21.0.12.1 | `~/.local/toolchain/jdk21`      |
| Maven     | 3.9.9             | `~/.local/toolchain/maven`      |
| Node/npm  | 24.21.0 / 11.19.0 | `~/.local/toolchain/node`       |

## Активация

```bash
source ~/.local/toolchain/env.sh   # ставит JAVA_HOME, MAVEN_HOME, PATH
```

Чтобы включалось в каждом терминале — добавить эту строку в `~/.bashrc`.

## Тулчейн скриптов

```
build.sh              единая точка входа (тонкий диспетчер)
tools/env.sh          общая база: активация тулчейна, пути, pid/log-помощники
tools/build/          БИЛДЕРЫ                tools/start/       СТАРТЕРЫ
  backend.sh            jar                    backend.sh         jar на :8080
  frontend.sh           dist/                  frontend.sh        vite dev :5173
  all.sh                всё                    preview.sh         dist на :4173
  test.sh               тесты                  all.sh             backend+frontend
  clean.sh              снести артефакты       stop.sh            остановить
                                               status.sh          что поднято
                                               logs.sh            хвост логов
```

Скрипты `tools/**` самодостаточны: сами активируют тулчейн, запускать можно
напрямую и из любого каталога.

### Билдеры

```bash
./build.sh                 # backend (без тестов) + frontend
./build.sh backend         # -> backend/target/springbootcrm-backend-0.1.0-SNAPSHOT.jar
./build.sh backend --clean # с mvn clean
./build.sh frontend        # -> frontend/dist/  (npm ci, если нет node_modules)
./build.sh test            # юнит + интеграционные (mvn verify)
./build.sh test --it       # только интеграционные
./build.sh test --unit     # только юнит
./build.sh clean           # снести target/ и dist/
```

### Стартеры

```bash
./build.sh up              # backend :8080 + vite dev :5173
./build.sh up --prod       # backend :8080 + preview собранного dist :4173
./build.sh status          # состояние, порты, pid
./build.sh logs backend -f # следить за логом
./build.sh down            # остановить всё
./build.sh down frontend   # остановить выборочно
```

Сервисы уходят в фон в собственные сессии; pid и логи — в `.run/`.
Флаг `--fg` у `tools/start/backend.sh` и `frontend.sh` запускает на переднем
плане (удобно для отладки, Ctrl+C останавливает).

Порты переопределяются переменными окружения:

```bash
BACKEND_PORT=9090 DEV_PORT=3000 ./build.sh up
```

Но учтите: `frontend/src/api/config.ts` зашивает бэкенд на `:8080`, поэтому
смена `BACKEND_PORT` требует либо пересборки фронта, либо правки
`window.__API_BASE__` в `dist/index.html`.

## Замечания

* БД по умолчанию — H2 в файле `backend/data/springbootcrm` в режиме PostgreSQL,
  схема накатывается Flyway. PostgreSQL включается через переменные
  `SPRING_DATASOURCE_URL` / `_DRIVER` / `_USERNAME` / `_PASSWORD`.
* Фронтенд ходит на бэкенд по абсолютному URL (`http://<hostname>:8080`),
  vite-proxy не используется; порт можно переопределить в собранном
  `dist/index.html` через `window.__API_BASE__` без пересборки.
* `backend/mvnw` не работает: каталог `.mvn/wrapper/` в репозитории
  отсутствует. Используется установленный Maven.
* Тесты: 75 юнит (`*Test`, surefire) + 57 интеграционных (`*IT`, failsafe).
  `mvn verify` гоняет и те, и другие; всё зелёное. Тесты опциональных модулей
  лежат в зеркальном дереве `src/test/java/app/modules/**` и удаляются вместе с
  модулем; профиль `test` держит модули выключенными, поэтому остальные тесты
  подтверждают независимость хоста от них.
* В `AuthController` есть только `/login`, `/register`, `/logout` —
  `GET /api/auth/me` не существует. Профиль отдаёт `/api/users/me`.
