# ADR 0035: Frontend platform, repository split, and design system adoption

- Decision status: Accepted
- Implementation status: Partial — the four repositories exist as standalone git working
  trees under `frontend/` (`control-plane`, `operations`, `storefront`, `mobile`), each
  with its own `.gitignore`, and none is staged in the platform repository, which tracks
  only `frontend/README.md` and `frontend/prototypes/`. Two are past their initial commit:
  the storefront has three and the mobile application several. `tokens.css` is vendored
  into all three Angular applications (`storefront/src`, `operations/src`,
  `control-plane/src/design-system`) and `frontend/mobile/design-tokens/tokens.css` with
  the generated-not-authored header, the storefront additionally carrying a JizBiz brand
  sheet at `src/brands/jizbiz/tokens.css`, and the Flutter side has `QoidaTokens` plus a
  `token_drift_test.dart`. The storefront
  implements `MiniAppHost` with `telegram-host.ts`, a declared-and-unimplemented
  `click-host.ts`, `standalone-host.ts`, `host-detection.ts` and `create-host.ts`. Flutter
  is installed and its 33 test files run. Not built: three of the four repositories still
  have no remote — the storefront has one, `qoida-one/qoida-storefront-jizbiz` — so
  `.gitmodules` and the four gitlinks are absent and `git clone --recursive` gets nothing;
  no `sync-tokens` script and no Angular-side drift check, so the four vendored
  copies are unpoliced, and the storefront carries no ESLint configuration, so the lint
  rule forbidding `window.Telegram` outside the Telegram host does not exist either; the
  eleven primitives were never ported — no shared component library exists in any Angular
  app (`control-plane/src/design-system` holds `tokens.css` and nothing else), across 184
  TypeScript files; server-side Telegram `initData` verification does not exist anywhere in
  `src/main/java`, so the storefront's session exchange has no counterpart; and there is
  no OpenAPI release artifact and no consumer manifests or nightly smoke suite —
  though generated TypeScript clients now exist and are checked in at
  `api/generated/` (the full v1 client plus one per ADR 0057 surface group), no
  frontend imports any of them yet, so every API layer is still hand-written — and no nightly
  smoke suite. The screens are no longer uniformly stubs. The storefront routes a whole
  customer journey — unauthenticated browse, search and product, then cart, a
  session-guarded checkout, orders, profile and addresses — across 109 of those
  TypeScript files. Operations mounts `today` and `orders` (with `new` and `:orderId`
  children) plus eleven placeholders derived from its navigation model; the control plane
  mounts an overview and a capability-guarded tenants list plus two state screens; and the
  Flutter router still mounts `_UnbuiltRoute` for
  both of its shell routes even though catalogue, cart, checkout, orders and profile are
  written under `lib/src/features/`.
- Date proposed: 2026-08-21
- Date decided: 2026-08-22 (amended; the 2026-08-21 decision stands except where restated below)
- Deciders: Ayubkhon Abbosov (platform architecture, product owner)
- Depends on: ADR 0003, ADR 0024, ADR 0025, ADR 0031
- Supersedes / Superseded by: Supersedes ADR 0022
- Open inputs: package registry for the published design system (platform); accessibility and performance budget numbers (product)

## Context

ADR 0022 chose React 19 everywhere and forbade a second component framework. It
was decided before the existing Qoida applications were examined, and contact
with them invalidated its central premise. This ADR replaced it on 2026-08-21
with Angular across every web surface, a retained storefront, and a native
SwiftUI iOS application.

Three of those conclusions have since changed. The product owner settled the
following on 2026-08-22, and this document is rewritten around them rather than
appended to, because a decision record that has to be read in chronological
layers stops being a decision record.

**What changed.**

1. The frontends do not live in the platform repository. There are four
   applications and four repositories, wired into this one as git submodules
   under `frontend/`.
2. The mobile application is Flutter, not SwiftUI, and covers iOS and Android
   from one codebase. The archived SwiftUI application becomes reference
   material rather than a baseline to extend.
3. The Telegram Mini App is in scope for the storefront now. It is not a later
   channel. The Click Mini App is explicitly later.

**What did not change.** Angular remains the single web component framework.
The Qoida Design System remains the visual contract, adopted by consuming its
tokens rather than rewriting them. Authentication remains Authorization Code
with PKCE against the Qoida realm, with the additions in *Telegram Mini App*
below. The component gap identified on 2026-08-21 is unchanged in substance and
is restated with a corrected mobile column.

**Two facts constrain how this is executed today.** `gh` is not installed on the
development machine and the four GitHub remotes do not exist yet, so the
submodule wiring cannot be completed. Flutter and Dart are not installed either,
so the mobile application is written by hand and has never been compiled. Both
are recorded in *Repository layout* and *Consequences* rather than glossed.

## Decision

### Four applications, four repositories

| Path under `frontend/` | Stack | Surface | Audience |
|---|---|---|---|
| `control-plane` | Angular | CONSOLE | Qoida staff — tenants, onboarding, subscriptions, platform configuration |
| `operations` | Angular | CONSOLE | One restaurant's staff during service |
| `storefront` | Angular, hosted standalone and as a Telegram Mini App | FIELD | Customers |
| `mobile` | Flutter | MOBILE | Customers, iOS and Android |

Each is a standalone git repository. The platform repository references them as
submodules at those paths. Nothing in `frontend/` is tracked in the platform
repository's own index.

**Angular remains the single web component framework.** The storefront's
archived predecessor `qoida-storefront-jizbiz` is Angular 21.1 with
`@ngrx/signals`, Tailwind 4 and Vitest — current generation, and the reference
for the storefront's screen inventory and interface. What the storefront
repository retains is that design and that inventory, not that working tree:
the code now lives in a different repository against different APIs, so the
original "retain and re-point in place" no longer describes anything real.

The courier surface named in the 2026-08-21 version is withdrawn from this ADR.
It has no approved scope (ADR 0014, ADR 0042) and naming a fifth repository for
it now would be planning a repository rather than an application.

### The mobile application is a customer ordering app

The archived `milliy-ios` application — bundle name `Rayhon`, against
`https://api.rayhonmilliy.uz/api/v1/customers` — is a **customer** application.
Its 207 Swift files are the customer journey and nothing else: onboarding,
phone-and-OTP login, a home screen driven by server-sent UI element blocks and
special offers, categories, search with filters and recent searches, product
detail, cart, checkout with payment method and address selection, order list and
detail, courier tracking on a Yandex map, feedback, profile, saved addresses with
geocode and reverse-geocode, favourites, invitations, FAQ, support chat,
notification settings, language and theme. Its dependencies are
`YandexMapsMobile`, `SDWebImageSwiftUI`, `FirebaseAnalytics` and
`FirebaseMessaging`.

There is no dispatch screen, no assignment queue, no shift state, no proof of
delivery, and no staff authentication path. It is the native twin of the
storefront, not a courier or operations client.

**The Flutter application is therefore a customer ordering application for iOS
and Android**, and the same product as the storefront on a different platform.
A courier application is a different product with a different audience,
different session model, and different battery and background-location
behaviour; it is out of scope here and belongs to whatever ADR 0042 settles.

This matters because the legacy platform enum in `qoida-dashboard` —
`android`, `ios`, `web`, `telegram`, `support` — shows Android was already an
ordering platform this business served and iOS-only was already a gap. One
Flutter codebase closes it; two native codebases would have been the price of
the same coverage under the previous decision.

### Telegram Mini App now, Click Mini App later

Telegram is a real ordering surface for this business, not a nice-to-have
channel: the legacy order data carries `platform=telegram` on 8 of 23 orders,
and `telegram` is a first-class member of the legacy platform enum alongside
`web` and the two native platforms.

The storefront is therefore built to run in two hosts from one codebase:

- **Standalone web**, at the tenant's domain, authenticating with PKCE.
- **Telegram Mini App**, inside Telegram's WebView, authenticating with
  Telegram's `initData` handshake.

The seam is a `MiniAppHost` abstraction with exactly one implementation,
`TELEGRAM`, and a named-but-unimplemented `CLICK`. Nothing in the storefront may
read `window.Telegram` at module scope or branch on user agent; host detection
happens once, at bootstrap, behind that interface. **The Click Mini App is not
built.** Leaving the seam costs an interface and a switch statement; discovering
after the fact that Telegram assumptions leaked into forty components costs a
rewrite of the checkout flow.

### Telegram Mini App authentication

This is a second authentication path, and it is the part most likely to be got
wrong quietly.

- Telegram supplies `initData`, signed with an HMAC derived from the bot token.
  The client sends it to the platform verbatim; the platform verifies the
  signature and the `auth_date` freshness window server-side and exchanges it
  for a normal Qoida token.
- **The browser never holds the bot token** and never performs the verification.
  A client-side check of a signature whose key is in the client is decoration.
- The resulting session obeys everything else in *Authentication* below: token
  in memory, capability checks as usability affordance only, API as the
  enforcement point.
- Telegram supplies its own theme parameters. They are ignored. FIELD's tenant
  accent is the tenant's, and a storefront that recolours itself to the user's
  Telegram theme is a different product in every screenshot.

### The design system reaches four repositories by vendoring

There is no package registry and no decision about one, so a published package
is not available. Until it is:

- Each repository **vendors its own copy** of the token sheet — the three
  Angular applications as `tokens.css`, the Flutter application as a generated
  Dart `ThemeExtension`.
- Every vendored copy carries a header naming the source of record and stating
  that the file is **generated, not authored**. An edit to a vendored copy is a
  defect, not a customisation.
- A `sync-tokens` script in the platform repository regenerates all four copies
  from the source of record, and CI in each repository fails if its copy differs
  from what the script would produce.
- Each application's README states plainly that a published package is the right
  answer and that it is blocked on a registry decision. No application invents
  a distribution mechanism of its own.

**The trigger that makes publishing worth the setup:** the first time the drift
check fails in a repository whose owner did not know a token had moved. That is
the moment vendoring stops being an accounting exercise and starts costing
someone a debugging session. A second trigger is a fifth consumer of the tokens,
whatever it turns out to be. Either one opens the registry decision — and note
it is two registries, npm-compatible for three applications and pub-compatible
for the fourth, which is a cost of the Flutter choice that a single-language
stack would not have paid.

The working reference for all of this is
`frontend/prototypes/control-plane/src/tokens.css` and its identical twin under
`prototypes/operations/`. They carry the tokens verbatim and the prototype
sections show the intended density and behaviour. They are throwaway React and
must never be imported by an application, but they are the closest thing to a
specification of how CONSOLE feels.

### What crosses repository boundaries

Contracts, not components. Generated API clients, TypeScript and Dart types from
the OpenAPI document, money and time formatting, and the ADR 0025 capability
primitives. Presentation components are not shared between CONSOLE and FIELD,
because their surface classes are deliberately different contracts, and a
component satisfying both would satisfy neither well.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| One monorepo containing platform and all four applications, as this ADR previously assumed | The honest strongest option. One version of the design system with no sync script and no drift check; an API change and its client change in one atomic commit and one review; one CI pipeline; one dependency-update stream; `git bisect` that crosses the API boundary. Rejected on the product owner's instruction that each application be independently ownable, releasable, and grantable to a different contributor without handing over the platform source. That is a real property and a monorepo does not give it cheaply | Independent ownership stops being needed, or the drift and version-skew cost measurably exceeds the isolation benefit — most likely signalled by a release blocked on manual cross-repository coordination |
| Nx or Turborepo monorepo for the three Angular applications, Flutter separate | Keeps atomic changes and one design system across the web surfaces, where drift actually hurts, and accepts the split only where the toolchain forces it. Rejected for the same reason as above, and because it produces an asymmetry — three applications governed one way and one governed another — that has to be explained forever | The same trigger as the row above. This is the shape to fall back to first, not the full monorepo |
| Two repositories: platform plus consoles, and a customer-surfaces repository | Splits along audience rather than application. Rejected because it gives up atomic API-and-client changes anyway while keeping most of the coupling, so it pays the split's cost without buying the split's isolation | Never; if the split is worth making it is worth making cleanly |
| Git subtree instead of submodules | No detached-HEAD confusion, no `--recursive` clone trap, and the platform repository stays self-contained. Rejected because subtree makes the four applications' history a subordinate copy rather than an independently ownable repository, which is the property being bought | The submodule ergonomics prove worse in practice than the isolation is worth |
| SwiftUI iOS application, as this ADR previously decided | The existing application is on a supportable baseline and integrates Yandex Maps Mobile and Firebase Messaging as first-party SDKs. Rejected because it serves one of the two platforms the legacy system already recorded orders from, and the second native codebase costs more than one cross-platform one | Never for this application. A future courier application with heavy background location may reasonably reach a different conclusion |
| React Native rather than Flutter | Would share TypeScript, tooling, and possibly formatting code with three Angular applications, and avoids a second package registry. Rejected on the product owner's choice; Flutter's rendering model also makes a strict design system easier to honour, because nothing is inherited from a platform widget set that disagrees with it | A shared TypeScript client and formatting layer proves to be the dominant cost of the mobile application |
| Kotlin Multiplatform, or two native codebases | Best native integration and the highest cost. Two codebases means every screen twice for an application whose entire value is a customer journey already specified | The mobile application needs platform capabilities Flutter cannot reach through a plugin |
| Telegram Mini App as its own fifth application | Cleaner deployment story and a smaller bundle inside Telegram. Rejected because it is the same catalogue, cart, checkout, and order journey as the storefront; two implementations of one journey diverge, and the divergence shows up as a customer being quoted two different delivery fees | The Mini App's product diverges from the web storefront's, rather than only its host |
| Build the Click Mini App alongside Telegram | Both are WebView hosts with an init handshake, so the marginal cost looks small. Rejected because it is not small: a second host means a second signed-payload verification path server-side, a second review process, and a second set of platform quirks to test — for a channel with no evidence of demand, where Telegram has 8 of 23 legacy orders | Click Mini App traffic is asked for by a paying tenant, or Click's own checkout integration makes the host a requirement |
| Publish the design system as a package immediately | The correct end state. Rejected as sequencing, not as substance: it requires choosing and standing up an npm-compatible and a pub-compatible registry with authentication in four CI pipelines, before any application has a screen. Vendoring plus a drift check gets the same guarantee for a fraction of the setup | Either trigger in *The design system reaches four repositories by vendoring* fires |
| Port the design system to Angular *and* keep React wrappers | Lets either framework consume it. Rejected as a maintenance trap: two implementations of one visual contract drift, and the drift is invisible until a designer notices two buttons differ | A second product with its own team needs the system in React |

## Repository layout and the interim state

Each application is created as a standalone git repository at its path: `git
init`, a real `.gitignore`, and an initial commit.

**The submodule wiring is deliberately not done yet.** `gh` is not installed and
the four GitHub remotes do not exist. A `.gitmodules` entry pointing at a URL
that does not resolve breaks `git clone --recursive` for every consumer of a
repository that is already pushed and shared, and it breaks it in a way that
looks like a network failure rather than a mistake. So:

- No `git submodule add` is run.
- No `.gitmodules` file is created or edited.
- Nothing under `frontend/` is added to the platform repository's index.

The promotion step, once the four remotes exist:

1. Create the four empty remotes.
2. In each application repository, add the remote and push `main`.
3. In the platform repository, `git submodule add <url> frontend/<name>` for each,
   which creates `.gitmodules` and stages the four gitlinks.
4. Verify `git clone --recursive` of the platform repository into a clean
   directory produces four populated working trees.
5. Commit `.gitmodules` and the gitlinks in one commit.

Until step 5, `frontend/` is four untracked directories from the platform
repository's point of view, and that is the intended state rather than an
oversight.

## The design system

The contract this ADR adopts wholesale, unchanged from 2026-08-21:

- **Surface classes.** `.console` for both back-office applications: strict
  Carbon, 0px corners, 1px hairline elevation, no shadows, no gradients, dense
  tables, IBM Plex Sans, platform blue `#0f62fe` used scarcely, never a tenant
  accent. `.field` for the storefront: 8px radii, 48px targets, one soft shadow,
  tenant accent injected as a CSS variable. `MOBILE` for the Flutter
  application, restated below.
- **Closed type scale.** Every size is a named class. No inline `font-size`.
- **Scarce blue.** Links, primary actions, focus, selection. Never decoration.
- **Sentence case, no emoji, no gradients, no illustration.** Empty states are a
  caption and a tertiary action.
- **Motion animates `transform` and `opacity` only**, and console table data does
  not animate at all.
- **UZS is thousands-separated with spaces and no decimals** — `84 000 so'm` —
  in tabular figures, matching ADR 0018's whole-som minor unit. There is nothing
  to divide.
- **Status tone is a dot plus text**, never colour alone. Yellow is a dot only;
  its text pair is the darker warning ink.
- **Locales are ru, uz-Latn, and en**, runtime-switchable, with content names
  left authentic rather than machine-translated. This closes ADR 0022's open
  input on supported locales.

### The MOBILE skin, restated in Flutter

The MOBILE skin was specified against SwiftUI's idioms. Flutter's are different,
and the translation is not mechanical — Flutter ships an opinionated design
system of its own that contradicts this one in several places, so the skin has
to say what it switches off as well as what it sets.

| Concern | SwiftUI expression (withdrawn) | Flutter expression |
|---|---|---|
| Token access | `Color` extensions on the asset catalogue | A `QoidaTokens extends ThemeExtension<QoidaTokens>`, generated from the same source of record. Widgets read `Theme.of(context).extension<QoidaTokens>()`. Never `Colors.*`, never a literal `Color(0x…)` |
| Semantic colour | `Color(.systemBackground)` and friends | Explicit token values. Platform semantic colours are not used: they differ between iOS and Android and would make one codebase render as two products |
| Type scale | `.font(.custom(…))` per call site | A closed `TextTheme` whose named styles mirror `.q-body`, `.q-body-sm`, `.q-caption`, `.q-title` and the rest. A `TextStyle` constructed at a call site is a lint failure, exactly as an inline `font-size` is on web |
| Font | System font with a custom face where needed | IBM Plex Sans bundled as an application asset, not fetched. ru and uz-Latn need the Cyrillic and Latin Extended subsets present offline, and a font that arrives over the network arrives after first paint |
| Iconography | SF Symbols | One bundled icon font behind a single `QIcon` widget — the same replaceability seam the web `Icon` component uses for Lucide-substituting-for-Carbon. SF Symbols do not exist on Android and would have forced two icon sets |
| Geometry | 8pt corner radii, 44pt minimum targets | 8dp radii from `--q-radius` FIELD value, 48dp minimum targets. Material 3's default shapes are overridden explicitly in `ThemeData`, not inherited |
| Elevation | One soft shadow | One soft shadow. Material 3 surface *tint* elevation is disabled — it recolours surfaces by depth, which the design system does not do |
| Feedback | `UIImpactFeedbackGenerator` | `HapticFeedback`, and the Material ink ripple is replaced with a token-controlled press state. A ripple is decoration this system does not use |
| Navigation | `NavigationStack`, `.sheet` | A single declarative router; sheets are `showModalBottomSheet` with the FIELD radius. One widget set on both platforms — Material-based and Qoida-skinned — rather than adaptive Cupertino/Material widgets, because "one codebase" stops being true the moment the tree branches on platform |
| Dynamic type | Dynamic Type | `MediaQuery.textScaler`, honoured and clamped to a bounded range so the closed scale survives a 200 % system setting |
| Money and dates | `Int` extension formatting | One shared formatter: `intl` with a space group separator and no decimals for UZS; 24h clock and `DD.MM` for ru and uz. Not reimplemented per screen — the legacy application reimplemented it per screen |
| Locale | `LanType` enum of `en`/`ru`/`uz` | `Locale('ru')`, `Locale.fromSubtags(languageCode: 'uz', scriptCode: 'Latn')`, `Locale('en')`. The script subtag is carried because uz-Latn and uz-Cyrl are not the same locale and the legacy `uz` was ambiguous |
| Maps | Yandex Maps Mobile, first-party SDK | Yandex MapKit through a Flutter binding. **This is the weakest dependency in the stack** and is named as a risk below, with a platform-view fallback |
| Push | Firebase Messaging, first-party | Firebase Messaging, first-party Flutter plugin. This one is a clean carry-over |

### Component gap

Neither console can be built from the eleven primitives that exist (`Button`,
`Icon`, `Input`, `Select`, `Card`, `StatusPill`, `EmptyState`, `Tabs`,
`DataTable`, `IconChip`, `PhoneFrame`). Each addition is authored into the design
system first, then implemented per surface, or the system stops being the source
of truth. The mobile column below replaces the SwiftUI column of the previous
version.

| Component | CONSOLE (Angular) | FIELD storefront (Angular) | MOBILE (Flutter) |
|---|---|---|---|
| `DataTable` — sorting, server pagination, selection, saved views, virtualisation | Required. An order list is thousands of rows | Not used | **Not ported.** A table is not a phone pattern; the equivalent is a lazily-built list of cards |
| `DataGrid` — inline edit, fill-down, keyboard navigation | Required. Bulk fiscal-code backfill, menu editor | Not used | Not ported |
| `MatrixGrid` — editable cross-tab | Required. Channel × payment method | Not used | Not ported |
| `Modal`, `ConfirmDialog`, `Drawer`, `ActionMenu` | Required throughout | Sheets rather than drawers | `showModalBottomSheet` and `showDialog`, FIELD radius, 48dp targets. A drawer beside a list has no phone equivalent |
| `Toast`, `InlineAlert` | Every mutation | Every mutation | A token-skinned snackbar. Material's default snackbar shape and colour are overridden |
| `MapCanvas`, `PolygonEditor`, `AddressPicker` | Zones, regions, geofences — editing | Address pin at checkout | Address pin and courier tracking, read-mostly. Polygon *editing* is console-only; a phone edits no delivery zone |
| `LocalizedFieldGroup` — `{ru, uz, en}` | ~40 forms | Not used — customers read one locale | Not used |
| `Combobox` — async search, create-on-miss | Customer-by-phone-with-create, product picker | Search | Search, as a full-screen route rather than a dropdown |
| `MoneyInput`, `PercentInput` | Required. Large-integer UZS, no minor units | Tips, if ever | Rarely; the shared formatter matters far more than the input |
| `DateRangePicker`, `TimeInput`, `ScheduleGrid` | Reports, venue hours, prep bands | Scheduled-order slot picker | Scheduled-order slot picker only. 24h clock, `DD.MM` |
| `MediaUploader` with crop | Logos, banners, product images | Not used | Avatar and support-chat attachment. Camera and gallery permission flows are a mobile-only concern with no web analogue |
| `ImportWizard` — dry-run diff | Excel catalog, customer CSV | Not used | Not ported |
| `SecretInput` — masked, reveal-once | Provider credentials, ADR 0028 | Not used | Not ported. A phone is not where a provider credential is entered |
| `StatusPill` overlay and dual-state | Lateness as an overlay on a status | Order status | Order status and courier progress |
| `LockedState`, `DeniedState` | Plan lock is an upsell; capability denial is a wall | Rare | Rare |
| `Chart` | Dashboards, ABC-XYZ, forecasting | Not used | Not ported for the pilot |
| `Board` / kanban column | Kitchen display, order buffer | Not used | Not ported |
| `PhoneFrame` and siblings | Previewing FIELD and MOBILE inside CONSOLE | Not used | The real thing, not a frame |

Charts, maps, and rich text are third-party by necessity, each wrapped behind a
Qoida component so the library stays replaceable.

**One console template is not enough.** Operations needs three shells — the
operator console (dense, keyboard-first), a kitchen device shell (fullscreen,
touch targets, offline banner), and a wallboard (TV-legible at distance).
Carbon's square geometry survives all three; the density and hit-target scales
do not.

## Authentication

Carried forward: Authorization Code with PKCE against the Qoida realm, tokens in
memory rather than local storage, exact allowlisted redirect URIs, proactive
refresh coordinated across tabs, explicit tenant selection verified against
membership before it enters API context, and logout clearing application cache
and in-memory state.

Two additions from this rewrite:

- **Telegram Mini App** uses the `initData` exchange described above instead of a
  redirect, because a redirect flow inside Telegram's WebView is hostile at best.
  Everything after the exchange is identical.
- **The Flutter application** performs PKCE with the system browser and
  `flutter_secure_storage` for the refresh token only. An access token still
  lives in memory. The refresh token is on the device because a customer
  application that demands re-login every session is a deleted application, and
  that is a considered difference from the web surfaces, not an inconsistency.

The client never holds a Keycloak service-account credential, never holds the
Telegram bot token, and never infers tenant access from role-name strings.
Capability checks in a client are a usability affordance; the API is the
enforcement point, per ADR 0025.

## Contract verification across repositories (ADR 0031)

ADR 0031 requires clients generated from the OpenAPI document, with CI failing on
an undocumented breaking change. A colocated client is checked against the
server's document at build time. **A client in another repository cannot be**,
and pretending otherwise is how a client ships against a contract that moved.

What replaces it:

1. **The document becomes a published artifact.** Every platform release
   publishes its generated OpenAPI document as an immutable, versioned artifact
   tagged with the release. It is the contract of record, as ADR 0031 already
   says; it now has an address other repositories can pin.
2. **Each client pins a document version and generates from it in CI.** The
   generated output is committed, and CI fails if regenerating produces a diff.
   Response types are never hand-copied — the failure mode this prevents is a
   developer editing a generated type to make a build pass.
3. **Each client publishes a consumer manifest** — the operation identifiers and
   the request and response fields it actually uses. The manifests live in the
   platform repository, one file per client, updated by the client's CI.
4. **The platform's OpenAPI diff gate checks the new document against every
   manifest** and fails if any pinned client's consumed surface breaks. This is
   consumer-driven contract testing without a broker, and it is the piece that
   actually replaces colocation: the server learns it broke a client at the
   server's build, not at the client's.
5. **A nightly end-to-end smoke suite** runs each client against a platform built
   from `main`. Manifests catch shape breaks; only a running system catches
   semantic ones.

Until step 4 exists, the four clients are pinned to `v1` and a breaking change
inside `v1` is caught by a person. That is the honest interim and it should be
short.

## What the split means for the ADR 0024 cutover

ADR 0024 routes cutover by complete journey and requires target frontends in
wave 7. "The frontend" is no longer one deployable, so:

- **A cutover decision names four build identifiers**, not one — the commit and
  the deployed build of each surface in scope for that journey. A cutover record
  that names only a platform release does not describe what a customer was
  served.
- **The single-writer gate is unaffected.** It is server-side, and none of these
  clients can reach it except through the API. The split changes no ownership
  semantics.
- **Rollback is asymmetric, and this is the real cost.** Rolling the platform
  back no longer rolls the clients back, because they deploy separately. Every
  client must therefore keep working against the platform release immediately
  preceding the one it was built against, for the entire length of ADR 0024's
  rollback window. This is a testable requirement, not a hope: the smoke suite
  runs each client against the previous release as well as the current one.
- **The Telegram Mini App does not roll back like a web page.** BotFather points
  at one URL. The previous build stays served at a versioned path so the pointer
  can be moved back in one step rather than waiting for a redeploy.
- **The mobile application cannot roll back at all.** An App Store release is
  stuck until review. It is therefore the most conservative consumer of the API,
  its behaviour changes are server-flagged wherever possible, and **no cutover
  may depend on a mobile release landing**. A journey whose cutover requires a
  new mobile build is a journey that is not ready to cut over.

## Consequences

### Positive

- Each application is independently ownable, releasable, and grantable. A
  contributor on the storefront needs the storefront repository and a published
  OpenAPI document, not the platform's source.
- One Flutter codebase serves both platforms the legacy system already recorded
  orders from. The previous decision served one of them.
- Telegram becomes a first-class ordering surface at the cost of a host
  abstraction rather than a second application, and the legacy data says it
  earns that.
- Consumer manifests make server-side breakage visible at the server's build,
  which colocated type-checking never actually did — it only failed the client.
- The design system's expensive half — tokens, scale, surface classes — is
  consumed as generated output in all four repositories, with a check that says
  so.

### Negative

- **I think the four-way split is the wrong call at this team size, and I want
  that on the record once.** One person and a handful of agents are building all
  four applications against an API that changes daily. The monorepo's atomic
  API-and-client commit is worth more right now than independent ownership,
  because there is no second owner yet. The machinery in *Contract verification*
  above exists purely to rebuild, imperfectly and later, a guarantee a monorepo
  gives for free today. That said, the decision is the product owner's, the
  isolation property is real, and un-splitting later is far cheaper than
  splitting later — so this ADR specifies the split as asked, and nothing below
  hedges it.
- No atomic change across API and client. An endpoint rename is now a
  coordinated sequence across up to five repositories with a compatibility
  window in the middle.
- Four CI pipelines, four dependency-update streams, four release processes, four
  sets of secrets. All of it duplicated work that a monorepo does once.
- The design system now exists in three languages — CSS custom properties,
  Angular component styles, and generated Dart — rather than two. The drift check
  catches divergence in the generated copies; it cannot catch a component that
  honours a token on web and ignores it on mobile.
- **The SwiftUI application is written off.** Two hundred and seven files,
  a working Yandex Maps integration, and a shipped Firebase Messaging setup
  become reference material. That is a genuine asset the previous version of this
  ADR counted and this one discards.
- **The Yandex MapKit Flutter binding is community-maintained**, where the
  SwiftUI application used the first-party SDK. If it lags a MapKit release or is
  abandoned, the fallback is a platform view wrapping the native SDK on each
  platform through a method channel — which is real work and partially
  reintroduces the two-codebase cost the Flutter choice was made to avoid.
- **The mobile application has never been compiled.** Flutter and Dart are not
  installed on this machine, so `frontend/mobile` is scaffolded by hand: the file
  layout, `pubspec.yaml`, and Dart sources are written from knowledge of the
  framework, not from a toolchain that accepted them. It should be assumed not to
  build until someone with Flutter installed runs `flutter pub get` and
  `flutter analyze`, and the first such run should be expected to find errors.
- **The submodules are not wired.** Until the four remotes exist, a fresh clone
  of the platform repository yields four empty directories under `frontend/` and
  no indication that anything is missing.
- Choosing Angular narrows the hiring pool relative to React in most markets,
  Tashkent included, and Flutter narrows it again in a different direction.

### Accepted trade-offs

- CONSOLE and FIELD share tokens but not components. That is a deliberate seam:
  `.console` and `.field` are different visual contracts.
- The Flutter application holds a refresh token on the device where the web
  surfaces hold nothing. A consumer application that logs a customer out every
  session does not get used.
- Vendored tokens are duplication with a check rather than a package with a
  version. Accepted as sequencing; the triggers for undoing it are named.
- Telegram's own theme parameters are ignored in favour of the tenant accent.
  The Mini App will look less like Telegram than Telegram would prefer.

## Implementation checklist

- [x] Create `frontend/control-plane`, `frontend/operations`, `frontend/storefront`,
      `frontend/mobile` as standalone repositories, each with a real `.gitignore`
      and an initial commit. No `.gitmodules`, no `git submodule add`, nothing
      staged in the platform repository.
- [x] Vendor `tokens.css` into the three Angular applications and a generated
      `QoidaTokens` `ThemeExtension` into the Flutter application, each with the
      generated-not-authored header.
- [ ] Write `sync-tokens` in the platform repository and the per-repository drift
      check that fails CI on a hand-edited copy.
- [ ] Port the eleven primitives to Angular against the tokens, verified against
      the design-system cards and the prototype sections rather than screenshots.
- [ ] Author the missing components into the design system, then implement per
      surface against the gap table above.
- [ ] Implement the MOBILE skin: `ThemeExtension`, closed `TextTheme`, bundled
      IBM Plex Sans, `QIcon`, Material-3 shape and surface-tint overrides,
      ripple replacement, clamped `textScaler`.
- [ ] Implement `MiniAppHost` with `TELEGRAM`; leave `CLICK` declared and
      unimplemented. Add a lint rule forbidding `window.Telegram` outside the
      Telegram host implementation.
- [ ] Implement server-side `initData` verification — HMAC against the bot token,
      `auth_date` freshness window, exchange for a Qoida token.
- [ ] Publish the OpenAPI document as a versioned release artifact; pin it in
      each client and generate clients in CI with a committed-output diff check.
- [ ] Add consumer manifests, one per client, and extend the ADR 0031 diff gate
      to fail on a manifest break.
- [ ] Add the nightly smoke suite, running each client against both the current
      and the previous platform release.
- [ ] Once the four remotes exist: push each repository, run `git submodule add`
      for all four, verify `git clone --recursive` into a clean directory, and
      commit `.gitmodules` with the four gitlinks in one commit.
- [x] Install Flutter on a build machine and run `flutter pub get`,
      `flutter analyze`, and a debug build against `frontend/mobile`. Fix what it
      finds before treating any of it as working.

## Exit criteria

- All four applications render from the vendored token sheet with no local
  colour, spacing, or type literals, enforced by a lint rule in each repository.
- The drift check passes in all four repositories, and a token change reaches all
  four through `sync-tokens` without a hand edit.
- A deliberately introduced breaking change to the OpenAPI document fails the
  platform's build by way of a consumer manifest, not by way of a client's build.
- The storefront serves the same journey standalone and inside Telegram, from one
  codebase, with `CLICK` still unimplemented and no Telegram assumption outside
  the Telegram host implementation.
- The Flutter application builds and runs on both iOS and Android from one
  codebase, covering the customer journey the archived `milliy-ios` application
  covered, against the new platform APIs.
- `git clone --recursive` of the platform repository produces four populated
  application working trees.
