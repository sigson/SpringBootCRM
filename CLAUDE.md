# CLAUDE.md

Guidance for Claude Code working in this repository. `backend/CLAUDE.md` and
`frontend/CLAUDE.md` cover each half in detail; this file covers the whole and the
seams between the two.

## What this is

A metadata-driven CRM. The backend declares aggregates with annotations; lists, forms,
filters, navigation and permissions are derived from those declarations at runtime, and
the React client renders whatever the metadata describes.

**The central invariant: adding or changing an aggregate is a backend-only change.** If
you find yourself editing `frontend/` to make a new entity appear, stop — either the
backend declaration is incomplete, or you are about to hard-code a special case into a
generic renderer. Both are worth a second look.

```
backend/    Spring Boot 3.4, Java 21, Hibernate 6, Flyway, H2 or PostgreSQL
frontend/   React 18, TypeScript 5, Vite 5
docs/       architecture, build, module and deployment notes
tools/      build and run scripts, driven by ./build.sh
```

## Commands

```bash
./build.sh                  # build both halves
./build.sh up               # backend :8080 + vite dev :5173
./build.sh up --prod        # backend :8080 + built dist on :4173
./build.sh test             # 75 unit (surefire) + 57 integration (*IT, failsafe)
./build.sh test --unit      # unit only, fast
./build.sh status           # what is running, with ports and pids
./build.sh logs backend -f  # tail
./build.sh down             # stop everything
```

The scripts expect a portable toolchain at `~/.local/toolchain` and activate it
themselves (see `docs/BUILD.md`). If it is missing, use a system JDK 21 / Maven 3.9 /
Node 20+ and run `mvn` and `npm` directly.

Sign in as `admin` / `admin`. The database is an H2 file in `backend/data/`; delete the
directory for a clean slate.

**Run the full suite before claiming a backend change works.** Integration tests boot a
real Spring context on in-memory H2 and exercise the access-control listeners, which is
exactly the machinery that unit tests miss.

## The seam between the halves

The frontend calls the backend at an **absolute URL**. There is no Vite proxy. The origin
is resolved in `frontend/src/api/config.ts`, in this order:

1. `window.__API_BASE__` — set in `index.html`, editable in a built `dist/index.html`
   without rebuilding. Commented out by default, deliberately: uncommenting it overrides
   the deploy configuration.
2. `VITE_API_BASE` — build-time, used by the Render static site.
3. `http://<this host>:8080` — the local development default.

Two consequences that have bitten before:

- **Changing `BACKEND_PORT` alone does not work.** The frontend default hard-codes 8080.
  Either rebuild with `VITE_API_BASE`, or edit `window.__API_BASE__`.
- **CORS is a real constraint in deployment**, not just locally. The backend allows any
  origin by default (`SPRINGBOOTCRM_CORS_ALLOWED_ORIGINS`), which is a development
  convenience; production sets it explicitly.

Auth travels both as a cookie and as an `Authorization: Bearer` header from
`localStorage`. The header is what makes cross-origin deployment work at all, since a
`SameSite` cookie is third-party in that setup.

## Database portability

The app targets **H2 in PostgreSQL mode** locally and **PostgreSQL** in production, from
one set of Flyway migrations. Tests only ever run on H2, so H2-only SQL passes CI and
fails on deploy.

Constructs that do **not** survive the move, and have already caused this:

| Do not write | Write instead |
|---|---|
| `DATEADD('DAY', n, d)` | `CAST(d + INTERVAL 'n' DAY AS DATE)` |
| `MERGE INTO t (…) KEY(c)` | a plain `INSERT` where the table is known empty |
| `CREATE OR REPLACE VIEW` with drifting column types | cast every column explicitly — PostgreSQL requires the types to match the previous definition |

`hibernate.ddl-auto` is `validate`. The schema is versioned SQL in
`backend/src/main/resources/db/migration/`; Hibernate's job is to fail loudly when the
entities and the schema disagree. Never switch it to `update` or `create`.

Adding a migration means a new `V{n}__name.sql` — never edit an applied one.

## Conventions

- Existing comments are in Russian, Ukrainian and English, by file. **Match the file you
  are editing** rather than converting it. New top-level documentation is English.
- Comments here explain *why*, and several document a non-obvious failure that was
  actually hit. They are load-bearing; do not tidy them away.
- The two optional modules under `backend/src/main/java/app/modules/` must stay deletable
  as a single folder. Dependencies point one way: `dcs → sqlworkbench → host`. The host
  (`app.springbootcrm.*`) imports nothing from either. If you need a module to reach the
  host, add it to that module's `bridge/` package, which is the only place allowed to
  know about both.

## Deployment

`render.yaml` declares the whole stack: backend as a Docker Web Service, frontend as a
Static Site, plus managed PostgreSQL. `docs/DEPLOY.md` is the walkthrough, including the
free-tier limits and the compatibility fixes that deployment required.

When changing anything that affects deployment, the files that matter are
`backend/Dockerfile`, `backend/docker-entrypoint.sh` (translates Render's `DATABASE_URL`
into JDBC properties), `backend/src/main/resources/application-prod.yml`, and
`render.yaml`.

## Security defaults are development defaults

Do not treat these as settings that are fine because they are committed:

- `admin` / `admin` is the bootstrapped account.
- The JWT secret in `application.yml` is public. Production must set
  `SPRINGBOOTCRM_JWT_SECRET`.
- The H2 console at `/h2` is anonymously reachable and is a full SQL shell. The `prod`
  profile disables it.
- SQL Workbench is admin-only and read-only, but it is still a SQL console on the live
  database.
