# SpringBootCRM

### ▶ [Try the live demo](https://springbootcrmfrontend.onrender.com)

Sign in as **`admin`** / **`admin`** — the login screen fills it in for you.

> **First load takes up to a minute.** The API runs on Render's free tier and sleeps
> after 15 minutes idle, so the first request has to wake a JVM. The page itself appears
> at once; it is the first API call that waits. Reload if it seems stuck.

The demo database is shared and public — treat anything you type into it as visible to
everyone, and expect it to be reset.

---

A CRM where the user interface is not written by hand. The backend declares what an
aggregate *is*; the lists, forms, filters, navigation tree and permission checks that
surround it are derived from that declaration at runtime, and the React client builds
its screens from the result.

Adding a new business entity is a backend-only change. There is no matching frontend
commit, because there is no frontend code that knows about any particular entity.

```
backend/    Spring Boot 3.4 · Java 21 · Hibernate 6 · Flyway · H2 or PostgreSQL   (~27k LOC)
frontend/   React 18 · TypeScript 5 · Vite 5                                      (~23k LOC)
docs/       architecture, build, module and deployment notes
tools/      local build and run scripts
```

---

## The idea

Most CRUD applications repeat themselves. Every entity gets an entity class, a DTO, a
repository, a service, a controller, a list screen, a form screen, a filter panel, a
navigation entry and a scattering of permission checks. Nine of those ten artefacts are
mechanical restatements of the first one, and each is a place for the ten to drift apart.

SpringBootCRM keeps one source of truth — the annotated aggregate — and derives the rest.

```java
@Entity
@Table(name = "customers")
@TypeId(value = 4001, defaultRepoAccess = DefaultAccess.READ_ONLY)   // who may read/write
@Reference(prefix = "CUS", codeWidth = 9)                            // CUS000001, CUS000002…
@AccessChecked(strict = true)                                        // enforce on every path
@UiAggregate(slug = "customers", pluralLabel = "Customers",
             displayPattern = "{code} — {name}", iconHint = "🏢")    // how it appears
public class Customer extends AbstractReferenceAggregate {

    @FieldId(4001_10L, defaultAccess = DefaultAccess.READ_WRITE)
    @UiField(label = "Email", order = 30, maxLength = 160)
    @Column(name = "email", length = 160)
    private String email;
    …
}
```

That single class, plus a Flyway migration for its table, produces:

| Derived from | What appears |
|---|---|
| `@UiAggregate` / `@UiField` | REST CRUD at `/api/customers`, a list with sorting and paging, a form, filters, a reference picker other entities can point at, a navigation entry |
| `@TypeId` / `@FieldId` | type-level and field-level access rules, enforced in Hibernate listeners and AOP |
| `@Reference` | a generated human-readable code (`CUS000001`) from a per-type counter that stays correct under concurrency |
| `displayPattern` | how the record is rendered wherever it is referenced, including inside other entities' forms |

The frontend never learns the word "Customer". It reads `/api/metadata/types`, gets field
descriptors and access flags, and renders. A user without write rights is handed a
read-only screen rather than a button that fails.

## How a screen is actually produced

```
  sign in
     │
     ├─ MetadataProvider  ──▶ GET /api/metadata/types   aggregate + field descriptors,
     │                                                  including this user's access flags
     ├─ NavProvider       ──▶ GET /api/navigation       the tree the backend built for
     │                                                  this user's rights
     ▼
  route /customers
     │
     ├─ ObjectListPage resolves the slug to a type descriptor
     ├─ editors/registry offers a hand-written editor for this type…
     └─ …or, for almost every type, the generic form is built from field descriptors
```

Two escape hatches keep the generic path from becoming a straitjacket: `editors/registry`
substitutes a bespoke editor for any type that has earned one, and `InterfaceLayout`
stores a per-user navigation tree edited in the UI with a visual builder.

## Access control is declared, not scattered

This is the part worth reading the source for. Permissions are not `if (user.isAdmin())`
checks sprinkled through services — services contain no access code at all.

- **Type level.** `@TypeId(defaultRepoAccess = …)` sets the baseline; grants on an
  `AccessRole` override it. Checked by AOP around the repository.
- **Field level.** `@FieldId(defaultAccess = …)` — a hidden field is stripped on
  serialization and rejected on write. `User.passwordHash` is readable only by the auth
  flow.
- **Row level.** `@AccessFiltered(userClaim = SELF_IDS)` on `Activity` means a user sees
  only their own records. The predicate is applied by a Hibernate filter, so it holds for
  every query — including ones written later by someone who has never heard of the rule.

Enforcement lives in `domain.core.access` (28 classes) and `domain.core.persistence`
(Hibernate pre-insert/update/delete and post-load listeners). Because it sits at the
persistence boundary rather than the controller boundary, there is no code path that
quietly bypasses it.

## What is in the demo

| Group | Aggregates |
|---|---|
| Catalogs | Customers, Products, Lead sources, Deal stages, Discounts, Users, Access roles, Interfaces, Reports |
| Documents | Deals, Activities |
| Registers | Exchange rates, User discounts (a tabular part of a user) |
| Tools | SQL Workbench, Report designer, Data generation |

Demo data is seeded by the first migration, so every list has content on first start.

## Two optional modules

Both live under `app.modules.*`, both are deletable as a single folder, and the host
(`app.springbootcrm.*`) imports nothing from either. Dependencies point one way only:
`dcs → sqlworkbench → host`.

**SQL Workbench** (`SQLWORKBENCH_ENABLED=false` to remove) — a schema explorer, SQL
editor and visual query builder over the host's own database, read-only and admin-only.

**Report generator** (`DCS_ENABLED=false` to remove) — a report engine in the spirit of
1C's Data Composition System. A report stores a composition schema, default settings,
templates and forms; the engine composes them into a tree of totals with drill-down and
XLSX/CSV export. Its datasets are a *query pack*: several queries packed into one string
that the Workbench assembles into a single SQL statement with CTEs and joins. See
[docs/REPORTS.md](docs/REPORTS.md).

Turning a module off removes every bean and the corresponding UI section — the frontend
probes for the module and hides the section when it is absent.

---

## Quick start

Requires JDK 21, Maven 3.9 and Node 20+. ([docs/BUILD.md](docs/BUILD.md) describes the
portable toolchain the scripts expect.)

```bash
./build.sh          # build backend + frontend
./build.sh up       # backend on :8080, frontend on :5173
```

Open <http://localhost:5173> and sign in as `admin` / `admin`.

```bash
./build.sh status           # what is running
./build.sh logs backend -f  # tail a log
./build.sh test             # 75 unit + 57 integration tests
./build.sh down             # stop
```

Or run the two halves directly:

```bash
cd backend  && mvn spring-boot:run     # :8080
cd frontend && npm install && npm run dev   # :5173
```

The database defaults to an H2 file in `backend/data/`; delete it for a clean slate.

## Deploying

[docs/DEPLOY.md](docs/DEPLOY.md) walks through a free deployment on Render: the backend as
a Docker Web Service, the frontend as a Static Site, and a managed PostgreSQL instance.
[`render.yaml`](render.yaml) declares all three, so the dashboard route is
New → Blueprint → pick the repository.

PostgreSQL is configured entirely through the environment:

```bash
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/springbootcrm \
SPRING_DATASOURCE_USERNAME=crm SPRING_DATASOURCE_PASSWORD=secret \
SPRING_PROFILES_ACTIVE=prod \
java -jar backend/target/springbootcrm-backend-0.1.0-SNAPSHOT.jar
```

### Before exposing this anywhere real

The defaults are development defaults, deliberately:

- **Change the admin password.** The first start creates `admin` / `admin`.
- **Set `SPRINGBOOTCRM_JWT_SECRET`.** The key in `application.yml` is in this repository
  and therefore public. `render.yaml` generates one instead.
- **Run with `SPRING_PROFILES_ACTIVE=prod`,** which disables the H2 console — a full SQL
  shell that `SecurityConfig` permits anonymously.
- **Set `SPRINGBOOTCRM_CORS_ALLOWED_ORIGINS`** to your frontend's origin. The default
  allows any origin, which is convenient on a laptop and wrong on the internet.
- **Consider `SQLWORKBENCH_ENABLED=false`.** It is admin-only and read-only, but it is
  still a SQL console pointed at your production database.

## Documentation

| Document | What it covers |
|---|---|
| [docs/DEPLOY.md](docs/DEPLOY.md) | deploying to Render, free-tier limits, PostgreSQL notes |
| [docs/BUILD.md](docs/BUILD.md) | the local toolchain and every `build.sh` subcommand (ru) |
| [docs/REPORTS.md](docs/REPORTS.md) | the report generator and its query-pack format (ru) |
| [docs/SQLWORKBENCH_INTEGRATION.md](docs/SQLWORKBENCH_INTEGRATION.md) | how the Workbench module is isolated from the host (uk) |
| [docs/ddd-design-v9.md](docs/ddd-design-v9.md) | the full design of the `domain.core` DDD kernel (ru) |
| [backend/README.md](backend/README.md) | domain model, API reference, aggregate table |
| [frontend/README.md](frontend/README.md) | client layout and the metadata-to-screen pipeline |

Some documents predate the decision to publish and are still in Russian or Ukrainian;
they are kept verbatim rather than paraphrased into English badly.

## Status and licence

A working demo and an architecture study, not a product. The domain is deliberately
ordinary — the interesting part is the machinery underneath it.

MIT, see [LICENSE](LICENSE).
