# CLAUDE.md — frontend

React 18 / TypeScript 5 / Vite 5. See the root `CLAUDE.md` for cross-cutting rules and
`README.md` here for the directory layout.

## The rule that shapes everything here

**This client contains no per-entity code.** It does not know what a Customer is. It
reads `/api/metadata/types`, receives field descriptors and the current user's access
flags, and renders from them.

So: **a new backend aggregate must require no change here.** If adding one seems to need
a frontend edit, the cause is almost always an incomplete `@UiAggregate` / `@UiField`
declaration on the backend rather than a missing component. Fix it there.

The two sanctioned escape hatches, for when a type genuinely needs more than the generic
form:

- `editors/registry.tsx` — a `typeId`-keyed registry of bespoke editors and picker
  tables. Registration is static, through the `editors/index.tsx` bundle, with no
  provider and no mount side effects, so every registration is in place before the first
  lookup. A type with no entry falls through to the generic form.
- `editors/listRegistry.tsx` / `freeControllerRegistry.ts` — the same idea for list views
  and for screens that are not an aggregate list at all.

Adding a special case anywhere *else* — a `typeId === 4001` branch inside `ObjectList`,
say — defeats the architecture. Put it in a registry.

## How a screen is produced

```
sign in
   ├─ AuthProvider      token in localStorage, 401 handler
   ├─ MetadataProvider  GET /api/metadata/types    once, after sign-in
   └─ NavProvider       GET /api/navigation        the tree for this user's rights
route /:slug
   ├─ ObjectListPage    slug → type descriptor
   ├─ editors/registry  a bespoke editor for this typeId…
   └─ …or ObjectList + buildColumnsFromMetadata → the generic list and form
```

`components/` holds the generic machinery: `ObjectList`, `ListView`,
`buildColumnsFromMetadata`, filters, reference pickers, the tabular-part editor. These
are the files where a careless change affects every screen at once.

`windows/WindowStack.tsx` backs nested object windows — opening a referenced record from
inside a form, modally, without a URL change.

## Access flags come with the metadata

Field and type descriptors carry the current user's rights (`auth/accessFlags.ts`,
`auth/permissions.ts`). Use them to render, rather than letting the user attempt
something the backend will reject: no write grant means a read-only screen, not a save
button that returns 403.

This is presentation only. The backend enforces independently, so a flag missed here is a
UX bug, never a security hole — and a check added *only* here is worth nothing.

## Talking to the backend

`api/client.ts` wraps `fetch`: JSON headers, `Authorization: Bearer` from `localStorage`,
`credentials: "include"`, automatic redirect to `/login` on 401, and normalisation of
error bodies into a typed `ErrorEnvelope`. Use `api.get` / `post` / `put` / `delete`;
do not call `fetch` directly.

Errors arrive as `ErrorEnvelope` with a `kind` (`VALIDATION`, `ACCESS_DENIED`,
`CONFLICT`, …). Not every error should open a modal — form-level validation is handled in
place; `useErrorDialog().showFromError(err)` is for the rest.

`api/config.ts` resolves the backend origin: `window.__API_BASE__`, then `VITE_API_BASE`,
then `http://<this host>:8080`. There is **no Vite proxy** — requests are cross-origin
and CORS is configured on the backend. Do not add a proxy to "simplify" local
development; it would diverge from how the app is actually deployed.

## Optional modules

`src/modules/sqlworkbench` and `src/modules/dcs` mirror backend modules that can be
switched off. Each is `React.lazy`-loaded behind an error boundary, and
`useSqlWorkbenchAvailable` probes the backend before the section is shown. If the module
is gone server-side, the section must simply not appear — never a broken link or a
console error.

`modules/dcs` mounts the Workbench's own `StatementEditor` for dataset editing rather
than a copy of it. Keep it that way: a fork would have to re-implement reference mode,
joins, unions and subqueries, and would drift.

## Conventions

- Comments in this tree are largely Ukrainian, some Russian. **Match the file you are
  editing.** New top-level documentation is English.
- No state library. React context and hooks, `fetch` for transport. Do not add Redux,
  Zustand or React Query for a single screen's convenience.
- `styles/` holds design tokens and component styles for both light and dark themes.
  Theme is a `data-theme` attribute on `<html>`. Add tokens rather than literal colours.
- `npm run build` runs `tsc` before `vite build`, so type errors fail the build. Run it
  before claiming a change works — the dev server does not typecheck.
