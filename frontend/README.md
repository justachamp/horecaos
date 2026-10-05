# HorecaOS frontends

Three Angular applications plus the canonical design tokens. The Flutter customer app
lives in [../mobile](../mobile) and is on hold for launch — the storefront below is the
customer surface ([ADR 0055](../platform/docs/adr/meta/0055-greenfield-launch-scope.md)).
Imported 2026-08-30 from the Qoida workspace, where they were four sibling git
repositories — three of them with no remote. Their pre-import history remains in
`../Qoida/qoida-platform/frontend/*` on the founding machine. Framework choice is
[ADR 0035](../platform/docs/adr/partial/0035-angular-frontend-platform-and-design-system-adoption.md).

Each app's own README is the detailed reference. What follows is oriented toward "what
does this app actually do today", not a percentage — the predecessor's import-time
estimates (~15%/~20%/~70%) are gone because all three have moved well past them and a
number here would only go stale the same way.

## storefront/

The customer-facing app: browse (home, category, product, search), cart, a
session-guarded checkout, order tracking (active/finished/cancelled/detail), and
profile/addresses. Sign-in is phone number plus SMS OTP against the platform's own
session endpoint — not Keycloak/OIDC; see `src/app/core/session/customer-otp.ts`. Tests
live beside the code as `*.spec.ts` (`ng test`, Vitest-backed); there is no separate
end-to-end suite. Angular 21.

## operations/

The application a restaurant runs on during service. `today` and `orders` (with an order
detail pane) are real, built screens; every other rail destination — kitchen, delivery,
couriers, customers, staff, statistics, catalog, places, settings — renders an explicit
"not built yet" placeholder pointing at its spec in
[`../platform/docs/operations-spec/`](../platform/docs/operations-spec/). Auth is
Authorization Code with PKCE against the HorecaOS Keycloak realm via
`angular-auth-oidc-client`; tokens are held in memory only, so a page refresh drops the
session by design, and the realm handshake itself has never been exercised against a
running Keycloak. Tests live beside the code as `*.spec.ts` (`ng test`). Angular 22.

## control-plane/

Platform-staff administration. Two routed screens exist, an overview and a
capability-guarded tenants list; six more sections (onboarding, subscriptions, payments,
statistics, platform configuration, staff) are declared with their required capability in
`src/app/layout/sections.ts` but have no route yet — not even a placeholder route.
Capability-based nav filtering is UX only; the server re-authorizes every call
([ADR 0025](../platform/docs/adr/built/0025-fine-grained-authorization-and-capability-model.md)).
Auth uses `angular-oauth2-oidc` against the same realm, tokens in memory. Tests live
beside the code as `*.spec.ts` (`ng test --watch=false`). Angular 22.

## design-tokens/

`design-tokens/tokens.css` is **the** canonical token file. Today only `control-plane`
and `operations` vendor a copy of it (`storefront` uses Tailwind and vendors no tokens
file at all); re-pointing every app at this one file directly is an open item on ADR
0052's checklist. Of the two that vendor a copy, only `control-plane` checks it against
the source of record: `control-plane/scripts/check-tokens.mjs` diffs
`src/design-system/tokens.css` against `../../design-tokens/tokens.css` (ignoring header
comments) and is wired into its `npm run verify`, but nothing calls that script in CI —
there is no CI configured for any of the three apps yet. `operations`'s vendored copy is
verified by eye, not by a script.

## Formatting and lint in CI

Every app has `npm run lint` and `npm run format:check`, and the CI job `Frontend builds`
runs them; what they cover differs by app.

- **`operations`** — `npm run lint` and `npm run lint:rules`: `eslint-plugin-horecaos` rejects
  a raw `font-size: 10px` anywhere under `src/` (use a `--q-type-*` token or a `.q-*` class from
  `tokens.css`), and `lint:rules` proves the rule itself still fails on a violation. Then
  **`npm run format:check`** on the whole `src/` tree (`ts`, `html`, `css`, `json`); the tree is
  prettier-clean and CI keeps it so. Fix a failure with `npm run format` in the app.
- **`control-plane`, `storefront`, `storefront-milliy`** — ESLint 8.57 with the
  `typescript-eslint` recommended rules, `@eslint/js` recommended, and `eqeqeq` (flat config,
  `eslint.config.mjs` in each app; underscore-prefixed arguments and variables are the way to say
  "deliberately unused"). `control-plane` also runs operations' `horecaos/no-raw-px-font-size`
  — imported from `frontend/operations/tools/eslint-plugin-horecaos`, not copied — because it
  vendors the closed type scale; the two storefronts are Tailwind/SCSS apps with no such scale,
  so the rule is not applied there. Angular templates are not linted (`angular-eslint` needs
  ESLint 9; operations is on 8.57, so the upgrade is one change across the apps).
  `npm run lint:rules` runs `tools/lint-config.test.mjs`, which feeds the configured linter code
  that is wrong and code that is fine, so a config that silently lost its rules fails instead of
  passing for ever.
- **Formatting** is the plain `npm run format:check` over the whole `src/` tree for `operations`,
  `storefront` and `storefront-milliy`; each was reformatted in one commit ("whole-tree prettier,
  no behaviour change") and CI keeps it clean. Fix a failure with `npm run format` in the app.
  `control-plane` is still a ratchet, not a tree gate: it has on the order of a hundred files that
  predate its prettier config, and a blanket reformat while other branches are open would conflict
  with every one of them. CI runs `tools/format_changed.py`, which checks only the `src/` files a
  change added or edited (against the merge base), so a file a change touches must be
  prettier-clean. When nothing is in flight, reformat it in a single commit (`npm run format`) and
  switch its CI step to `npm run format:check`, as the other three did.

  ```bash
  python3 frontend/tools/format_changed.py --app control-plane --base main --list   # what would be checked
  python3 frontend/tools/format_changed.py --app control-plane --base main          # check it (needs npm ci)
  python3 frontend/tools/test_format_changed.py                                     # the tooling tests, also run in CI
  ```

`control-plane` vendors `design-tokens/tokens.css`; `npm run check:tokens` diffs the copy
against the source of record. It is a CI step ("Design tokens drift check (control-plane)"),
and the lint and prettier ignores for the vendored sheet rest on it.

## Component styles and bundle budgets

Each app warns at 4 kB per component stylesheet (`anyComponentStyle`) and, by default, 500 kB for
the initial bundle (`operations` is tighter, see below); the numbers are what the build prints
(`ng build`), measured on minified output. When a page's stylesheet grows past 4 kB:

- **Rules several pages carry byte for byte** belong in a shared sheet, under a `q-` name the page
  opts into by using it in its template. `operations/src/app/shared/styles/` holds three
  (`modal.css`, `dialog.css`, `controls.css`), loaded from `styles.css`. Bare names such as
  `.dialog` or `.primary` cannot be made global: other pages mean something else by them.
- **A region with rules of its own** becomes a component that takes what it shows as inputs and
  raises what the user asks for (`host: display contents` keeps the box tree unchanged). The page keeps
  every read, write and decision; the order queue's toolbar, the detail pane's money section and
  the product editor's Photos tab are examples.
- Do not raise the budget for code. `operations`' initial bundle was 831.38 kB against an 832 kB
  error budget (batch 17), almost all of it the Russian message catalogue, which the default
  locale ships eagerly: every wave that added messages was raising the budget by a kilobyte.
  The catalogue is now split by feature area and only its `core` area is eager (batch 18; the
  layout is in [`operations/README.md`](operations/README.md#localisation)), which took the
  initial total to 373.82 kB (-457.56 kB). The budget was reset to that plus 60 kB: **error at
  434 kB, warning at 400 kB**. Messages no longer count against it unless they go into `core`
  (a few hundred keys; `message-areas.ts` is where that is decided), so a feature that grows the
  initial bundle has put eager code there, and the answer is to lazy-load it. The figure ages with
  every merge: the `Initial total` line of `ng build --configuration production` is the source of
  truth, so size a feature against a fresh build, not against this number.

`operations` also has `npm run i18n:dead`, which lists message keys nothing references;
`--write` removes them from every locale's area modules (`--app-dir ../control-plane --variables
'^(en|ru|uzLatn)$'` scans control-plane, which still has the three single-file catalogues).

## Known debts

- **Two OIDC libraries against one Keycloak realm.** `control-plane` uses
  `angular-oauth2-oidc`, `operations` uses `angular-auth-oidc-client`. `storefront` isn't
  an OIDC consumer (it's phone/OTP), so this is a two-way split, not three. Converge on
  one.
- **Generated OpenAPI clients exist now and are actively avoided.** The generator
  machinery ADR 0035 asked for now runs — five clients are checked in at
  [`../platform/api/generated/`](../platform/api/generated/) — but no app imports one.
  `storefront` and `operations` each carry a code comment explaining why not: the
  generated types have a known schema-name collision bug that produces the wrong shape
  for at least two response types. Adoption needs that bug fixed first, not just a
  decision to start importing.
- **Angular 21 vs 22 split.** `storefront` pins `^21.1.0`; `control-plane` and
  `operations` pin `^22.1.0`. Align when the storefront next takes dependency work.
- **Workspace toolchain (pnpm workspace vs per-app npm) is deliberately undecided** —
  each app builds independently today with its own lockfile.
