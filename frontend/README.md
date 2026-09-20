# SpringBootCRM — frontend

React + Vite + TypeScript client for the SpringBootCRM backend. The UI is
metadata-driven: lists, forms, filters and the navigation tree are built at runtime
from `/api/metadata/types` and `/api/navigation`, so adding an aggregate on the
backend makes it appear here without any frontend change.

## Stack

- React 18, TypeScript 5, Vite 5
- React Router 6
- No state library — React context and hooks, `fetch` for transport

## Running

```bash
npm install
npm run dev        # http://localhost:5173
```

The client talks to the backend directly at an absolute URL; there is no Vite proxy, and
CORS is configured on the backend. `src/api/config.ts` resolves the origin in this order:

1. `window.__API_BASE__` — a commented-out `<script>` in `index.html`. Uncomment it to
   repoint a **built** `dist/index.html` at another backend with no rebuild.
2. `VITE_API_BASE` — a build-time variable (`.env.local`, or the service's environment).
   This is what the Render static site uses; see `.env.example`.
3. `http://<this host>:8080` — the default, which matches what `./build.sh up` starts.

For the standard local setup you need to configure nothing.

## Production build

```bash
npm run build      # -> dist/
npm run preview    # serve the build locally
```

`dist/` is static and can be served by any web server. Two things it needs from whatever
serves it:

- **An SPA rewrite** — every unmatched path to `/index.html`, or React Router works while
  navigating and returns 404 on reload and deep links.
- **`VITE_API_BASE` at build time**, unless the backend happens to be on `:8080` of the
  same host.

`render.yaml` in the repository root does both for a Render Static Site; see
[../docs/DEPLOY.md](../docs/DEPLOY.md).

## Layout

```
src/
├── api/            fetch wrapper, JWT handling, typed endpoint calls
├── auth/           AuthContext, ProtectedRoute, permission helpers
├── components/     generic list, filters, reference pickers, dialogs
├── editors/        per-type editor overrides and the registry that resolves them
├── metadata/       metadata provider and display-pattern resolution
├── modules/        optional SQL Workbench module
├── navigation/     navigation provider and side navigation
├── pages/          login, registration, dashboard, object list, profile
├── styles/         design tokens and component styles (light and dark)
├── types/          DTO types mirroring the backend
└── windows/        window stack for nested object windows
```

## How a screen is produced

1. `MetadataProvider` loads the aggregate descriptors once after sign-in.
2. `NavProvider` loads the navigation tree the backend built for this user's rights.
3. `ObjectListPage` resolves a slug to a type descriptor and renders the generic list.
4. `editors/registry` may substitute a hand-written editor for a specific type; every
   other type falls back to the generic form built from field descriptors.

Access flags travel with the metadata, so a user without write rights gets a read-only
screen rather than a failing request.
