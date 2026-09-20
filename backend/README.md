# SpringBootCRM — backend

A metadata-driven CRM backend built on a reusable DDD core (`domain.core.*`) that
provides access control, aggregate lifecycle and persistence. The application layer
(`app.springbootcrm.*`) only declares aggregates; lists, forms, navigation and
permissions are derived from annotations at runtime.

## Stack

* **Java 21**, **Spring Boot 3.4.8**
* **Hibernate 6.6** / JPA 3, **Flyway** migrations (`db/migration/`)
* **H2 in PostgreSQL mode** by default, **PostgreSQL** in production
* **JWT** (jjwt 0.12.x) — token in a cookie and in the `Authorization` header
* **BCrypt** password hashing
* AOP plus Hibernate listeners for type-, field- and row-level access control

## Domain model

Aggregates are grouped by the role they play, and the package layout follows that
grouping one to one.

| Group | Package | Aggregate | typeId | Notable fields |
|-------|---------|-----------|--------|----------------|
| Catalog | `catalogs/customer` | `Customer` | 4001 | email, phone, city, notes |
| Catalog | `catalogs/product` | `Product` | 4002 | sku, unitPrice |
| Catalog | `catalogs/leadsource` | `LeadSource` | 4101 | code, name |
| Catalog | `catalogs/dealstage` | `DealStage` | 4102 | probability |
| Catalog | `catalogs/discount` | `Discount` | 4103 | percent |
| Document | `documents/deal` | `Deal` | 4104 | leadSource, dealStage, customer, amount, expectedCloseDate |
| Document | `documents/activity` | `Activity` | 5001 | title, starts/ends, owner, polymorphic subject |
| Register | `registers/exchangerate` | `ExchangeRate` | 9202 | rateDate, currencyCode, rate |
| Register | `registers/userdiscount` | `UserDiscount` | 9201 | tabular part of `User`; discount, limitPercent |
| System | `user` | `User` | 9001 | account, one access role, assigned interface |
| System | `access` | `AccessRole` | 9100 | permission preset |
| System | `interfaces` | `InterfaceLayout` | 9300 | customizable navigation tree |

Catalog codes are generated from the `@Reference` prefix (`CUS000001`, `DEA000004`, …)
through a per-type counter in `reference_sequences`.

## Running

```bash
mvn spring-boot:run        # http://localhost:8080
```

The first start creates an `admin` / `admin` account with full rights, applies the
Flyway migrations and seeds demo data. The database is an H2 file under
`backend/data/`; delete it to get a clean slate.

Switch to PostgreSQL through the environment:

```bash
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/springbootcrm \
SPRING_DATASOURCE_DRIVER=org.postgresql.Driver \
SPRING_DATASOURCE_USERNAME=crm SPRING_DATASOURCE_PASSWORD=secret \
mvn spring-boot:run
```

## API

Every catalog, document and register exposes the same CRUD shape:

| Method | Path | Access |
|--------|------|--------|
| `GET` | `/api/{slug}` | authenticated |
| `GET` | `/api/{slug}/{id}` | authenticated |
| `POST` | `/api/{slug}` | WRITE grant on the type, or admin |
| `PUT` | `/api/{slug}/{id}` | WRITE grant on the type, or admin |
| `DELETE` | `/api/{slug}/{id}` | WRITE grant on the type, or admin |

Slugs: `customers`, `products`, `lead-sources`, `deal-stages`, `discounts`, `deals`,
`activities`, `exchange-rates`, `users`, `access-roles`, `interface-layouts`.

Cross-cutting endpoints:

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/auth/login` | `{username,password}` → JWT and cookie |
| `POST` | `/api/auth/register` | self-registration |
| `POST` | `/api/auth/logout` | clears the cookie |
| `GET` | `/api/users/me` | current profile |
| `GET` | `/api/metadata/types` | aggregate metadata that drives the UI |
| `GET` | `/api/navigation` | navigation tree for the current user |
| `GET` | `/api/references/{slug}` | generic reference lookup and paging |
| `POST` | `/api/admin/datagen/generate` | fill catalogs with test rows (admin) |

`Activity` is row-filtered: a user sees only their own records, an administrator sees
all of them. The filter is declared with `@AccessFiltered(userClaim = SELF_IDS)` and
applied by Hibernate, not by the service.

## Optional modules

Both are self-contained, enabled by default, and deletable as a single folder. Turning
one off removes every bean it declares and the matching UI section.

`app.modules.sqlworkbench` (`SQLWORKBENCH_ENABLED=false`) — schema explorer, SQL editor
and visual query builder over the host database, read-only and admin-only.

`app.modules.dcs` (`DCS_ENABLED=false`) — the report generator, an analogue of 1C's Data
Composition System. Requires the Workbench: a report's datasets are a Workbench query
pack. See [../docs/REPORTS.md](../docs/REPORTS.md).

## Tests

```bash
mvn verify        # 75 unit tests (surefire) + 57 integration tests (failsafe)
mvn test          # unit tests only
```

Integration tests are named `*IT`, so surefire's default includes skip them; failsafe
is bound to `integration-test`/`verify` and runs them. They boot a Spring context on an
in-memory H2 database under the `test` profile and need nothing external.

## Deployment

A `Dockerfile` here builds the jar in a multi-stage image; `docker-entrypoint.sh`
translates a PaaS-style `DATABASE_URL` into the JDBC properties the driver needs.
`application-prod.yml` holds the overrides that must differ in production — the H2
console off, a `SameSite=None` auth cookie, quieter logging. Activate it with
`SPRING_PROFILES_ACTIVE=prod`.

See [../docs/DEPLOY.md](../docs/DEPLOY.md).
