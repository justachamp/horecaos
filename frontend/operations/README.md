# HorecaOS Operations

The console one restaurant's staff use during service.

It is used standing up, on a 1366×768 laptop in a call centre or a 24" screen on
a manager's desk, by somebody who is also on the phone. That single fact decides
everything about it: speed of the common action beats completeness of the rare
one, the queue is never hidden behind anything, and the number of late orders is
visible from every screen in the application.

Angular 22, `CONSOLE` surface of the HorecaOS Design System, `ru` / `uz-Latn` / `en`.

---

## What is here

This repository contains **foundations, not screens**. The smallest thing that
proves the shape is right and lets the next person start on a view instead of on
plumbing.

| Area                                                                                   | State                                   |
| -------------------------------------------------------------------------------------- | --------------------------------------- |
| Angular 22 workspace, standalone, zoneless, Vitest                                     | Builds and tests                        |
| Design tokens, vendored and applied                                                    | `src/tokens.css`, verified in a browser |
| Shell — rail, top bar, always-visible late count, F2                                   | Built                                   |
| Routing — two real routes, eleven honest placeholders                                  | Built                                   |
| Authentication — first-party sign-in page, backend exchanges credentials with Keycloak | Built and verified live                 |
| API client — Problem Details, idempotency, `If-Match`, cursor pages                    | Built and tested                        |
| Localisation — runtime switching, build-time completeness                              | Built and tested                        |
| Money and time formatting                                                              | Built and tested                        |

### Deliberately absent

- **Every screen.** The order board, the order detail, taking an order, the
  kitchen queue, dispatch, couriers, customers, staff, statistics, the menu,
  branches, settings. They are specified across `docs/operations-spec/` in the
  qoida-platform repository — 8 855 lines covering 109 views — and prototyped in
  `frontend/prototypes/operations`. A half-built screen is worse than an empty
  route, because it teaches operators habits the finished one has to break.
- **A generated API client.** ADR 0031 requires types generated from the OpenAPI
  document and ADR 0035 requires pinning a published version of it in CI. No
  document is published yet, so `src/app/core/api` hand-writes the _conventions_
  — which are stable — and hand-writes no _response types_, which are not.
- **Live updates.** ADR 0045 is not built. `ServiceStatus` is where the polling
  fallback goes, and it says so.
- **Any capability check.** Deliberate, not missing. See "Authorization" below.
- **A shared design-system package.** See "The tokens are a copy".

---

## Running it

```bash
npm install
npm start          # http://localhost:4200, against http://localhost:8080
npm run build      # production bundle into dist/
npm test           # Vitest, watching
npm run test:ci    # Vitest, once
npm run format     # Prettier (writes the whole tree)
npm run lint       # eslint: no raw px font-size (the closed type scale owns sizes)
npm run lint:rules # the lint rule's own fixtures
npm run i18n:dead  # message keys no template or TypeScript file references (--write removes them)
npm run i18n:areas # every route: the message areas it declares against the ones its code needs
npm run i18n:split # lay the catalogues out as one module per area (see Localisation)
npm run i18n:dead:test # the i18n tools' own tests, and the check that every route declares its areas
```

CI runs `lint`, `lint:rules`, `i18n:dead:test` and `format:check` (Prettier over the whole tree)
on every change; `npm run format` fixes a failure. To check only the files you touched, see
`python3 ../tools/format_changed.py` and
[`../README.md`](../README.md#formatting-and-lint-in-ci).

`npm start` uses `src/environments/environment.development.ts`, which points at
the platform on `localhost:8080` — the default in the platform repository's
`application.yml`. That repository's docker compose brings it up, and Keycloak
alongside it; this application itself never talks to Keycloak (ADR 0062).

**Without the platform reachable, the sign-in page's submit fails with a
Problem Details error** — `NETWORK_UNREACHABLE`, shown inline under the form.
That is correct behaviour, not a failure: `/login` itself renders regardless,
because unlike the redirect flow it replaces there is nothing upstream of it
that has to answer first.

---

## Authentication

Staff sign in on this application's own `/login` page (ADR 0062). The operator's
password never leaves this origin: submitting POSTs a username and password to
the platform's own `POST /api/v1/operations/auth/sessions`, which runs the
OAuth2 direct grant against Keycloak on the backend, over a confidential client
(`horecaos-staff-login`) this bundle never sees. This application does not hold
a Keycloak issuer, a client id, or a redirect URI — there is nothing left in
`src/environments/` to hold — and it does not depend on `angular-auth-oidc-client`,
which is gone along with the redirect flow and the `/auth/callback` route that
used to complete it (the one the owner reported broken in practice, and the
proximate reason ADR 0062 exists).

**Tokens are never persisted.** `StaffTokenStore` keeps the access and refresh
tokens in a closure that dies with the page — nothing survives in
`sessionStorage` any more either, because there is no redirect handshake left
that needs to survive a document navigation.

The cost is stated rather than hidden: **a page refresh drops the session.**
Before ADR 0062, Keycloak's SSO cookie sometimes made the redirect back
invisible; there is no cookie and no redirect to ride along with any more, so
every refresh is a sign-in. `Auth` proactively refreshes the access token a
minute before it expires (`bearer-token.interceptor.ts` attaches whatever is
current), so a session survives as long as the tab stays open.

### Verified live against the dev realm

Unlike the flow this replaces, the exchange was checked directly against a
running Keycloak (2026-09-01), not asserted and left for the next person: a
password grant on `horecaos-staff-login` returns a real token pair; a wrong
password and an unknown username both answer `invalid_grant` /
`"Invalid user credentials"` — indistinguishable, including for an account
Keycloak's own brute-force protection has since locked; an account with a
required action answers `invalid_grant` / `"Account is not fully set up"`; the
refresh grant, the RFC 7009 revocation endpoint, and revoking an already-dead
token all behave as this application's `Auth` assumes. See the platform
repository's ADR 0062 implementation notes for the full transcript.

### Authorization

There is no `can(capability)` helper and there will not be one. The API is the
enforcement point (ADR 0025). A client-side capability check is a usability
affordance — hiding a button an operator cannot use — and treating it as security
is a bug. Capability resolution runs over grants, scopes and entitlements that no
token claim summarises, so any client-side copy would be both weaker and stale.

---

## The API client

`src/app/core/api` is the only place this application talks to the platform. A
feature that injects `HttpClient` directly has silently opted out of all four
ADR 0031 conventions:

- **Problem Details.** Every failure becomes an `ApiError` carrying the stable
  `code` from the server's registry, the field errors, and the correlation id.
  `problem.detail` is English written for a developer and is never shown to an
  operator.
- **Idempotency.** `ApiClient` has no mutation overload that takes a bare body.
  It takes a `Command`, which carries a key minted once per operator intent and
  reused on every retry of that intent. A key minted inside a retry loop is how
  one approval becomes two.
- **Optimistic concurrency.** Reads return the version beside the body; mutations
  send it as a weak `If-Match`. Two operators deciding the same order at the same
  moment settle at one outcome, and the loser gets `STALE_VERSION` with both
  versions.
- **Cursor pagination.** No offsets, no page numbers. `resetOnFilterChange` exists
  because a cursor encodes the filter set and reusing one across a filter change
  fails.

### One thing the server and the ADR disagree about

ADR 0031 declares `/api/v1/operations/**` for this audience. The platform serves
the location endpoints there, but `OperationsOrderController` is still mapped on
the older `/api/v1/tenants/{tenantId}/brands/{brandId}/locations/{locationId}/orders`.
`src/app/core/api/operations-paths.ts` is the single file that knows this, so the
day the controller moves, exactly one file changes.

---

## Localisation

`ru`, `uz-Latn`, `en`, switchable at runtime from the top bar. Russian is the
default because that is what the staff read.

**A missing translation fails the build.** The English area modules define the key set;
`MessageKey` is derived from them (`messages.en.ts` puts them back together); the other
locales' modules are typed against them. Adding an English key without translating it is a
`tsc` error naming the key, once per untranslated locale, and `ng build` and
`ng test` both stop. Verified by adding a key and watching the build fail.

**The catalogue is split by feature area, and only `core` is in the initial bundle.** The
~5,900 keys used to be three modules, and the default locale's alone cost the initial bundle
about 460 kB. Now every key belongs to an area (`core/i18n/message-areas.ts`: `core`, `orders`,
`settings`, `catalog`, ...), decided by its leading segment (`orders.queue.title` is the `orders`
area) with a handful of exceptions for words several areas share (`orders.status.*` is `core`);
each area of each locale is `core/i18n/messages/<area>.<locale>.ts` and its own lazy chunk. `ru`'s
`core` ships in the initial bundle; everything else is fetched when something asks:

- a **route** declares what it shows, `canActivate: [messagesGuard('orders', 'customers')]` in
  `app.routes.ts`; the router waits for those chunks, so the screen draws with every string. A
  declaration covers the route's children; `core` is never declared;
- a **language switch** loads the areas the session has used, in the new language, before it
  swaps, so the screen does not lose strings halfway;
- a key read before its area was asked for shows its raw key for a moment and loads the area
  (with a console warning in development): a safety net, not a strategy.

`npm run i18n:areas` prints, per route, what it declares against what its code needs (every
file reachable from the route's component by import, `templateUrl` templates included, comments
ignored). The same check runs in `npm run i18n:dead:test`, so CI fails a route that names a key
of an area nothing on its way declares, and code that runs before any route opening on anything
but `core`. When a screen needs two keys from another area, move them into `core` with a line in
`AREA_BY_PREFIX` rather than declaring a 100 kB area for them; when the analysis overstates
because a file imports a module for a type, use `import type`.

**Adding a key** is as before, into the area module its prefix names (`messages/orders.en.ts`
and the two translations). A **new namespace** needs one line in `message-areas.ts`. Moving a key
between areas is the same edit and `npm run i18n:split`. A branch cut before the split that
still adds keys to the old `messages.{en,ru,uz-latn}.ts` is folded in by keeping its version of
those three files and running `npm run i18n:split`, which rewrites every area module from them.

Angular's own `$localize` was the obvious choice and is the wrong one here: it
compiles one bundle per locale, so switching languages means loading a different
deployment. A shared terminal changes hands between operators mid-shift.

Content names — dishes, brands, branches, people — are never message keys. They
are tenant data in the language the tenant wrote them.

**Which languages exist is the platform's to say, not this console's (ADR 0149).** One backend
class, `PlatformLocales` (`tenancy.api`), declares every language with its tag, script, direction,
face and **the tiers it is live in**; `GET /api/v1/operations/locales` serves it, and
`core/i18n/platform-locales.ts` reads it once before the shell draws (`platformLocalesGuard`). This
console keeps exactly one list of its own, `LOCALES` in `core/i18n/i18n.ts`, and it is a different
thing: the languages this *build* has a catalogue for. A language is offered in the interface when
the build holds its catalogue **and** the registry has its staff-UI tier live; the brand editor, the
branch editor, a template's wordings, an audience's languages, the staff-profile form and the catalog's
codes all read the registry's other tiers. A language the registry **declares but has not made live**
(`kk`, `ka` today) is offered nowhere.

`<html dir>` follows `<html lang>` (`core/i18n/document-direction.ts`): every entry says `LTR`, the
document says so, and new or touched CSS uses logical properties (`margin-inline-start`,
`inset-inline-end`, `text-align: start`). `no-physical-direction` (ESLint, `npm run lint`) fails a
stylesheet that is not on `tools/eslint-plugin-horecaos/physical-direction-baseline.json` and says
`margin-left`; the list can only shrink, and `npm run lint:rules` fails when a listed file no longer
needs to be there.

### Activating a language

No tenant makes a language exist: it is a release, per tier, and each tier has its own gate. Nothing
is activated by the registry's existence; `kk` and `ka` are declared with no tier live.

1. **Content** (a brand can author in it and a customer can read it) -- the storefront's and the mobile
   app's catalogues contain the language, then its registry entry gains `CONTENT`. A brand then
   *chooses* it by adding it to its supported set.
2. **Messages** (the platform can write to a customer in it) -- every template key a brand uses has a
   wording in it (a version needs one in every language **its brand serves**, not every language the
   platform has), the Telegram bot's replies and the transactional emails are translated
   (`TelegramBotMessages`, the invitation, reset and second-factor email tables), any SMS wording has
   cleared ADR 0091's gate and the segment estimate has been checked for the script (`SmsSegments`
   already falls to UCS-2 for Kazakh and Georgian), then the entry gains `MESSAGES`.
3. **Staff UI** (this console and the control plane speak it) -- both consoles' catalogues exist for
   it (`messages/<area>.<locale>.ts`, typed so a missing key fails the build; the control plane's
   `messages.<locale>.ts`), the face is bundled and the glyph audit has passed, then the entry gains
   `STAFF_UI`. Russian stays the staff default.
4. **Script and layout** -- the bundled `@ibm/plex-sans` covers Latin, Latin Extended and Cyrillic,
   and neither Kazakh's extra Cyrillic letters nor Georgian. Kazakh: audit the bundled face against
   the Kazakh alphabet and add a second face only if it fails. Georgian: an openly licensed face chosen
   at activation, declared as its own `@font-face` with a `unicode-range` and loaded only when the
   locale is active, so a Russian-speaking cashier never downloads Mkhedruli. A right-to-left language
   is not a checklist item: it reaches order lines, receipts, fiscal documents and SMS and needs its own
   record.

What activating adds *no* migration and *no* change to any module other than its catalogue: the
locale columns are a BCP 47 shape check (V0499), the brand's supported set is the tenant's choice,
and the registry is the only list. `LocaleListsLiveInTheRegistryTests` fails the backend build the day
a module declares one of its own again.

---

## Money

`{ amountMinor, currency }`, always. `src/app/core/format/money.ts` carries the
one arithmetic mistake this codebase has already shipped:

ISO 4217 gives UZS two minor units and every `Intl` implementation agrees. **The
platform stores whole som.** A formatter that asks `Intl` for the exponent and
divides by 100 renders a 125 000 som bill as `1 250,00`. That shipped last week.

So the exponents are declared as data this platform owns, an unknown currency
throws rather than guessing, and there is a test asserting that `Intl` says 2 and
this module says 0 — so that the disagreement is deliberate and documented rather
than discovered again.

---

## The tokens are a copy

`src/tokens.css` is **generated, not authored**. It is the design system's
CONSOLE sheet, vendored byte-for-byte apart from one line (its height rule names
React's `#root`; Angular's mount node is `q-root`). It is listed in
`.prettierignore` so a formatting pass cannot make a copy stop being a copy.

Four frontend repositories each hold their own copy because there is no shared
package registry yet, and **inventing one is not this application's decision to
make.** A published `@horecaos/design-tokens` package is the right answer; it needs a
registry decision first — self-hosted Verdaccio, GitHub Packages, or npm private
— and that belongs in an ADR, not in a `package.json`. Until then ADR 0035's
`sync-tokens` script and a per-repository drift check are what keep the four
copies honest.

Editing this file to change a colour is always wrong. Change it in the design
system and regenerate.

---

## Privacy

No personal data goes into a log, a trace or an analytics event (ADR 0029). Two
places where that is enforced rather than merely intended:

- `ApiError.message` carries the code, the status and the correlation id, and
  never `problem.detail` — which can name something the operator typed. Error
  messages reach logs and error reporting.
- The correlation id is a fresh UUID per request. An id that encoded a user, a
  session or a device would write personal data into every log line the request
  touches.

`Auth.displayName` is personal data. It may be rendered in the account menu; it
may not be logged. `Auth.subject` is the opaque `sub` claim and is the safe value
for correlating a session.

The `@ibm/plex-*` packages ship a postinstall telemetry script. npm's
`allow-scripts` gate blocks it and it has deliberately not been approved.

---

## Layout of the source

```text
src/
  tokens.css                 generated design tokens, do not edit
  styles.css                 everything visual this application may decide
  environments/              build-time configuration, no secrets
  app/
    core/
      api/                   ADR 0031 conventions and the one HTTP seam
      auth/                  the sign-in exchange, the guard, token storage
      i18n/                  catalogues (one module per area and locale), runtime switching, the `t` pipe
      format/                money and time
    shared/
      styles/                q- rules pages opt into, loaded by styles.css (see below)
      ui/                    the shared components
    shell/                   rail, top bar, the counters shown everywhere
    features/
      today/                 the landing route
      orders/                the docked-detail layout, with no board in it
      auth/                  the first-party sign-in page (ADR 0062)
      not-built/             the honest empty route
```

## Related documents in the qoida-platform repository

- `docs/adr/0003` — identity and access
- `docs/adr/0062` — staff sign in inside the platform
- `docs/adr/0025` — capabilities and scopes
- `docs/adr/0029` — personal data
- `docs/adr/0031` — HTTP API conventions
- `docs/adr/0035` — Angular, the design system, and the four repositories
- `docs/operations-spec/` — the 109 views this shell will carry
- `frontend/prototypes/operations/` — throwaway React. Never import it; read it.

## Repository status

Standalone git repository, no remote. It becomes a submodule of qoida-platform
once the remote exists. Nothing here creates a `.gitmodules` entry, because a
submodule pointing at a URL that does not resolve breaks `git clone --recursive`
for everybody.
