# HorecaOS frontends

Three Angular applications plus the canonical design tokens. The Flutter customer app
lives in [../mobile](../mobile). Imported 2026-08-30 from the Qoida workspace, where they
were four sibling git repositories — three of them with no remote. Their pre-import
history remains in `../Qoida/qoida-platform/frontend/*` on the founding machine.

| App | Stack | State at import |
|---|---|---|
| `control-plane/` | Angular 22, angular-oauth2-oidc | Foundations + 2 thin screens (~15%) |
| `operations/` | Angular 22, angular-auth-oidc-client | Foundations + 2 real routes, 11 placeholders (~20%) |
| `storefront/` | Angular 21, @ngrx/signals | Real product, ported to the platform API (~70%), 1 spec file |

## design-tokens/

`design-tokens/tokens.css` is **the** token file. At import there were four copies with
four different checksums; this one is taken from the copy the drift-check scripts treated
as source of record. Re-pointing each app (and `../mobile`) at this file — and deleting
the vendored copies — is an open item on ADR 0052's checklist.

## Known debts (from the founding review)

- Two different OIDC libraries against the same Keycloak realm (control-plane vs
  operations) — converge on one.
- No generated OpenAPI clients anywhere; every API layer is hand-written. The generator
  machinery ADR 0035 requires still does not exist. The backend's generated TypeScript
  baseline lives at `../platform/api/generated/`.
- Angular 21 vs 22 split; align when the storefront next takes dependency work.
- Workspace toolchain (pnpm workspace vs per-app npm) is deliberately undecided —
  each app builds independently today with its own lockfile.
