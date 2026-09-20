# Deploying to Render

This follows [Philip Athanasopoulos' guide to free hosting for a full-stack Spring Boot
application][guide], with three deliberate departures noted at the end.

[guide]: https://dev.to/philipathanasopoulos/guide-to-free-hosting-for-your-full-stack-spring-boot-application-4fak

The result is three services, all on free plans:

```
  springbootcrm-web          springbootcrm-api           springbootcrm-db
  Static Site                Web Service (Docker)        PostgreSQL
  Vite build of frontend/    the jar, from backend/      managed, free plan
  always on                  sleeps after 15 min idle    expires after 30 days
        │                            │                          │
        └──── HTTPS, CORS ───────────┴──── internal network ─────┘
```

---

## The short way: Blueprint

[`render.yaml`](../render.yaml) in the repository root declares all three services.

1. Push this repository to GitHub (or GitLab / Bitbucket).
2. Render Dashboard → **New** → **Blueprint** → select the repository.
3. Render reads `render.yaml`, shows you the three resources, and creates them.
4. Wait for the first deploy. The backend build takes roughly 4–8 minutes on the free
   tier: it compiles the jar inside the Docker image.
5. Open the static site's URL and sign in as `admin` / `admin`.

**Immediately after the first deploy**, do the two things the blueprint cannot do for
you:

- **Change the admin password** from the profile screen. `admin`/`admin` is created by
  the bootstrap on an empty database and your instance is on the public internet.
- **Check `SPRINGBOOTCRM_CORS_ALLOWED_ORIGINS`** on `springbootcrm-api`. The blueprint
  sets it to `https://springbootcrm-web.onrender.com`. If Render had to rename your
  static site — because someone already took that name — the value is wrong and the
  browser will block every API call with a CORS error. Correct it in the dashboard and
  restart the service; it is read at startup, so no rebuild is needed.

That second one is the single most likely thing to go wrong. Symptom: the site loads,
the login form appears, and signing in fails with a network error while the browser
console shows `No 'Access-Control-Allow-Origin' header`.

---

## The long way: three services by hand

Useful if you want to understand what the blueprint is doing, or if you are deploying
somewhere that is not Render.

### 1. PostgreSQL

**New → Postgres**, free plan. Pick a region and keep every other service in it —
cross-region database traffic on the free tier is slow enough to notice.

From the **Connections** tab you need the **Internal Database URL**, which looks like:

```
postgresql://springbootcrm:PASSWORD@dpg-xxxxx-a/springbootcrm
```

### 2. Backend — Web Service

**New → Web Service** → connect the repository → **Runtime: Docker**.

| Setting | Value |
|---|---|
| Dockerfile path | `./backend/Dockerfile` |
| Docker context | `./backend` |
| Health check path | `/actuator/health` |
| Plan | Free |

Environment variables:

| Key | Value |
|---|---|
| `SPRING_PROFILES_ACTIVE` | `prod` |
| `DATABASE_URL` | the internal database URL from step 1 |
| `SPRINGBOOTCRM_JWT_SECRET` | a random base64 string, at least 32 bytes |
| `SPRINGBOOTCRM_CORS_ALLOWED_ORIGINS` | the static site's URL, from step 3 |

Generate the JWT secret with:

```bash
openssl rand -base64 64
```

Do not reuse the default from `application.yml` — it is committed to this repository, so
anyone could mint themselves an admin token with it.

### 3. Frontend — Static Site

**New → Static Site** → same repository.

| Setting | Value |
|---|---|
| Root directory | `frontend` |
| Build command | `npm ci && npm run build` |
| Publish directory | `dist` |

`dist`, not `frontend/dist`: the publish path resolves *inside* the root directory.
Getting this wrong is the most common way this service fails, and it fails late — the
build succeeds, Vite reports the bundle it wrote, and only then Render says
`Publish directory <name> does not exist!`. The build log's relative paths
(`dist/index.html`) tell you what the publish directory is being compared against.

Environment variable: `VITE_API_BASE` = the backend service's URL
(`https://springbootcrm-api.onrender.com`). This is read at **build** time, so changing
it later requires a redeploy of the static site, not just a restart.

Add a rewrite rule under **Redirects/Rewrites**:

| Source | Destination | Action |
|---|---|---|
| `/*` | `/index.html` | Rewrite |

Without it, React Router works while navigating but any page reload or deep link returns
404 from the static file server.

### If you created the services by hand, render.yaml is not in play

A service created through **New → Static Site** or **New → Web Service** takes its
settings from the dashboard, and Render keeps preferring those on later syncs. It will
not pick up `render.yaml` just because the file is in the repository — so a publish
directory, a `VITE_API_BASE` or a rewrite rule that is correct in the blueprint can still
be missing or wrong on the running service.

Either set each field in the dashboard, as above, or delete the hand-made services and
recreate them with **New → Blueprint**, which reads the file.

---

## How the configuration actually connects

Three seams are worth understanding, because they are where a deploy breaks.

**`DATABASE_URL` is not a JDBC URL.** Render (like Heroku and Fly) issues
`postgresql://user:pass@host/db`. The JDBC driver cannot parse that — it needs
`jdbc:postgresql://host/db` with the credentials supplied separately.
[`backend/docker-entrypoint.sh`](../backend/docker-entrypoint.sh) does the split before
starting the jar. It splits on the *last* `@`, because Render's generated passwords can
contain one. Setting `SPRING_DATASOURCE_URL` directly bypasses the whole thing.

**The frontend learns the API URL at build time.**
[`frontend/src/api/config.ts`](../frontend/src/api/config.ts) resolves, in order:
`window.__API_BASE__` (editable in a built `dist/index.html`, an escape hatch for
repointing a deployed bundle without rebuilding), then `VITE_API_BASE`, then
`<this host>:8080` for local development.

**The auth cookie is a third-party cookie here.** The UI and the API are on different
origins, so browsers drop a `SameSite=Lax` cookie. `application-prod.yml` sets
`SameSite=None; Secure`. Even if a browser refuses it, sign-in still works: the client
also sends the token as an `Authorization: Bearer` header from `localStorage`.

---

## Free-tier realities

**The backend sleeps.** After 15 minutes without traffic, Render stops the instance. The
next request starts it again, which means a JVM cold start — expect **50–90 seconds**
before the first response. The static site does not sleep, so what a visitor sees is a
UI that loads instantly and then appears to hang on the first API call. For a demo,
saying so on the login screen is kinder than letting people conclude it is broken.

**The free database expires after 30 days.** Render deletes it. Back up anything you
care about (`pg_dump` via the external connection string), or move to a paid instance.

**512 MB of RAM.** The JVM is configured for it in the Dockerfile:
`-XX:MaxRAMPercentage=70 -XX:+UseSerialGC -Xss512k`. Serial GC on purpose — the parallel
collectors' extra threads are overhead on a single shared vCPU. Override with
`JAVA_OPTS` if you move to a larger plan.

**Build minutes and bandwidth are capped.** Every push to the connected branch triggers a
rebuild. Turn off auto-deploy in the service settings if you push often.

---

## Verifying a deploy

```bash
# 1. the API is up (this is the request that pays the cold-start cost)
curl -i https://springbootcrm-api.onrender.com/actuator/health

# 2. sign in
curl -s -X POST https://springbootcrm-api.onrender.com/api/auth/login \
     -H 'Content-Type: application/json' \
     -d '{"username":"admin","password":"admin"}'

# 3. CORS is configured for the right origin — look for
#    access-control-allow-origin in the response headers
curl -i -X OPTIONS https://springbootcrm-api.onrender.com/api/customers \
     -H 'Origin: https://springbootcrm-web.onrender.com' \
     -H 'Access-Control-Request-Method: GET'
```

If the Flyway migrations failed, the service will be in a crash loop and the logs will
name the statement. If they succeeded, the log reports the applied versions and the
customer, product and deal lists have demo rows in them.

---

## A note on PostgreSQL compatibility

The application runs on H2 (in PostgreSQL mode) locally and on PostgreSQL in production.
Preparing this deployment turned up three places where the two had quietly diverged,
all now fixed:

- `DATEADD('DAY', 30, CURRENT_DATE)` in `V1__initial.sql` — an H2 function with no
  PostgreSQL equivalent. Now `CAST(CURRENT_DATE + INTERVAL '30' DAY AS DATE)`.
- `MERGE INTO … KEY(…)` in `V1` and `V6` — H2 syntax. These seed a table the same
  migration creates, so a plain `INSERT` is both portable and sufficient.
- `ReferenceLookupViewSynchronizer` regenerates `reference_lookup` with
  `CREATE OR REPLACE VIEW`. PostgreSQL permits that only if the column types match the
  previous definition exactly, and the skeleton view in `V1` declared `VARCHAR` where the
  tables have `varchar(50)`/`varchar(200)` and `||` yields `text`. Every column is now
  cast explicitly.

**These fixes were verified against H2** — the full suite (75 unit + 57 integration
tests, which run Flyway and boot the context) passes. They have **not** been run against
a real PostgreSQL server, because none was available in the environment where this was
prepared. The first Render deploy is therefore also the first live PostgreSQL migration;
watch the startup log. If something else turns out to be H2-flavoured, it will fail loudly
at Flyway time rather than corrupt anything.

To check locally before deploying, if you have Docker:

```bash
docker run --rm -e POSTGRES_PASSWORD=secret -p 5432:5432 -d postgres:16
cd backend && SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/postgres \
  SPRING_DATASOURCE_USERNAME=postgres SPRING_DATASOURCE_PASSWORD=secret \
  SPRING_PROFILES_ACTIVE=prod mvn spring-boot:run
```

---

## Where this departs from the article

The article is a good map of the territory; three of its steps do not survive contact
with this repository.

**It builds the jar on your laptop** (`mvn package`, then `COPY target/*.jar`). That
makes the image depend on whatever happened to be in `target/` — including nothing at
all, on a fresh clone. The Dockerfile here is multi-stage and compiles the jar inside the
build, so a deploy needs only the repository.

**It hard-codes credentials in `application.properties`.** Committing a production
database password is how they leak. Everything here comes from the environment, and the
JWT secret is generated by Render.

**It sets `ddl-auto=create`, then advises changing it to `update`.** This project uses
Flyway with `ddl-auto=validate`: the schema is versioned SQL in
`backend/src/main/resources/db/migration/`, and Hibernate's job is to complain if the
entities and the schema disagree. `create` would drop your data on every deploy and
`update` would silently drift.

The article also notes you cannot serve both apps from one Web Service. That is true, and
here it is an advantage rather than a workaround: a Render Static Site is free, has a CDN
in front of it, and never sleeps.
