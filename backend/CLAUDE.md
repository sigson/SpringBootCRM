# CLAUDE.md — backend

Spring Boot 3.4 / Java 21. See the root `CLAUDE.md` for cross-cutting rules (database
portability, module isolation, deployment) and `README.md` here for the domain model and
API reference.

## The two layers

```
domain/core/**          a reusable DDD kernel. Knows nothing about CRM.
app/springbootcrm/**    the application. Declares aggregates; contains almost no
                        infrastructure code.
app/modules/**          optional, deletable features (sqlworkbench, dcs).
```

`domain.core` is the part that makes the application small. It provides aggregate
lifecycle, access control, reference prefetching, validation and the metadata bootstrap.
The application layer mostly declares entities and lets the kernel do the rest.

**When a change could go in either layer, prefer the application layer.** Moving
behaviour into `domain.core` makes it apply to everything, including code that has not
been written yet — which is the point when it is genuinely generic, and a trap when it is
not.

## How an aggregate is declared

```java
@Entity @Table(name = "customers")
@TypeId(value = 4001, defaultRepoAccess = DefaultAccess.READ_ONLY)
@Reference(prefix = "CUS", codeWidth = 9, singularName = "customer")
@AccessChecked(strict = true)
@UiAggregate(slug = "customers", pluralLabel = "Customers",
             displayPattern = "{code} — {name}", apiBase = "/api/customers")
public class Customer extends AbstractReferenceAggregate {
    @FieldId(4001_10L, defaultAccess = DefaultAccess.READ_WRITE)
    @UiField(label = "Email", order = 30, maxLength = 160)
    @Column(name = "email", length = 160)
    private String email;
}
```

Adding an aggregate means: the entity class, a Flyway migration for its table, and
usually nothing else. `MetadataBootstrapper` scans (ClassGraph, packages `domain` and
`app`) at startup and builds a `MetadataSnapshot` of `AggregateDescriptor` /
`FieldDescriptor`. `/api/metadata/types` serves it, filtered by the caller's rights, and
the frontend renders from it.

### typeId and fieldId are identifiers, not decoration

They are the keys access grants are stored against, so they are effectively permanent:
changing one silently reassigns whatever permissions referenced it. The convention is
`fieldId = typeId * 100 + n`, written `4001_10L`. Blocks in use: 4xxx catalogs and
documents, 5xxx documents, 9xxx system and registers. Check `backend/README.md` for the
allocated ones before picking a new one.

## Access control

Services contain no access checks. Do not add any — the enforcement points are:

| Scope | Declared with | Enforced by |
|---|---|---|
| Type | `@TypeId(defaultRepoAccess)`, grants on `AccessRole` | AOP around the repository |
| Field | `@FieldId(defaultAccess)` | access-aware serialization; write rejected |
| Row | `@AccessFiltered(userClaim = …)` | a Hibernate filter, applied to every query |
| Instance | `own_access` column | `AccessResolver`, on the access-system subjects only |

Implementation lives in `domain/core/access/` (28 classes) and
`domain/core/persistence/` — pre-insert, pre-update, pre-delete and post-load Hibernate
listeners. Because it sits at the persistence boundary, no query bypasses it, including
ones added later by someone unaware of the rule.

`AccessContextHolder` is a ThreadLocal bound by `AccessContextFilter`. Background work,
`@Async` and anything off the request thread must establish a context explicitly —
`SystemAccessContexts` has the system-level ones. A missing context is a denial, not a
bypass.

`@AccessChecked(strict = true)` makes an unmapped or unknown field a failure rather than
a silent allow. Prefer it.

## Security filter chain

`app/springbootcrm/auth/SecurityConfig.java` has a long comment explaining a Spring
Security 6 trap: `SecurityContextHolderFilter` clears the `SecurityContextHolder` at the
start of the chain, so a filter registered as a plain servlet filter *before* the chain
has its `Authentication` wiped. Both `JwtAuthenticationFilter` and `AccessContextFilter`
are therefore inserted *inside* the chain, and their Boot auto-registration is disabled
with `FilterRegistrationBean.setEnabled(false)`.

If you add a filter that needs the authenticated principal, do the same. Registering it
normally will appear to work in a unit test and fail at runtime.

## Reference codes

`@Reference(prefix = "CUS", codeWidth = 9)` yields `CUS000001`. Allocation goes through
`CodeAllocationService` against the `reference_sequences` table, which is what keeps it
correct under concurrent inserts. Do not generate codes in application code.

A migration that seeds rows into a catalog must also seed that type's counter, or the
first record created through the UI collides with a seeded code. `V1` and `V6` both do
this at the end — follow the pattern.

## Migrations

`src/main/resources/db/migration/`, `hibernate.ddl-auto=validate`.

- New file `V{n}__description.sql`. Never edit an applied migration.
- Entity mapping changes and their migration land in the **same** commit — `validate`
  fails the context otherwise, which is the intended behaviour.
- Write PostgreSQL-compatible SQL, not H2 SQL. Tests run on H2 only, so this is not
  caught for you. See the table in the root `CLAUDE.md`.

## Tests

```bash
mvn test      # 75 unit, *Test, surefire
mvn verify    # + 57 integration, *IT, failsafe
```

Integration tests are named `*IT` so surefire's default includes skip them; failsafe is
bound to `integration-test`/`verify`. They boot a Spring context on in-memory H2 under
the `test` profile and need nothing external.

The `test` profile keeps the optional modules **disabled**, which is deliberate: the rest
of the suite passing with them off is what proves the host does not depend on them.
Module tests live in a mirror tree under `src/test/java/app/modules/**` and are deleted
along with their module.

Access-control behaviour is only meaningfully covered by the integration tests, because
it lives in Hibernate listeners. A unit test that mocks the repository proves nothing
about it.

## Configuration

`application.yml` holds development defaults; `application-prod.yml` overrides what must
differ in production (H2 console off, `SameSite=None` cookie, quieter logging).

| Variable | Purpose |
|---|---|
| `PORT` / `SERVER_PORT` | listen port (`PORT` is what Render injects) |
| `DATABASE_URL` | PaaS-style URL, split into JDBC properties by `docker-entrypoint.sh` |
| `SPRING_DATASOURCE_URL` / `_DRIVER` / `_USERNAME` / `_PASSWORD` | explicit datasource; wins over `DATABASE_URL` |
| `SPRINGBOOTCRM_JWT_SECRET` | HMAC signing key. The default is committed and therefore public |
| `SPRINGBOOTCRM_CORS_ALLOWED_ORIGINS` | comma-separated; fed to `setAllowedOriginPatterns`, so `*` works |
| `SQLWORKBENCH_ENABLED` / `DCS_ENABLED` | `false` removes the module entirely |

## The optional modules

`app/modules/sqlworkbench` and `app/modules/dcs` must each remain deletable as one
folder, with no host reference to either.

- A single `@ConditionalOnProperty` switch per module gates a `@ComponentScan` of it.
  `enabled=false` creates no beans at all.
- Everything the module needs from the host goes through its `bridge/` package. That is
  the only place that knows both sides.
- `@RestControllerAdvice` inside a module must be scoped to the module's package, or it
  starts handling the host's exceptions.
- `dcs` depends on `sqlworkbench` (report datasets are a Workbench query pack).
  `sqlworkbench` depends only on the host. Do not add an edge back.
