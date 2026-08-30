# HorecaOS mobile

The HorecaOS customer ordering application for iOS and Android — the same product as the
storefront, on a different platform. Flutter, the MOBILE skin, per
[ADR 0035](../platform/docs/adr/partial/0035-angular-frontend-platform-and-design-system-adoption.md).

## Status: on hold

**This application is on hold for the current launch.** The Angular storefront
(`../frontend/storefront`) is the customer surface for launch; Flutter resumes only once
a pilot tenant asks for it or the storefront hits a limit a native app would fix
([ADR 0055](../platform/docs/adr/meta/0055-greenfield-launch-scope.md)). This is not a
statement about code quality — it is a statement about which of two customer surfaces
gets finished first.

An earlier version of this README described the app as having "no screens" beyond an
auth shell. That was never accurate: `lib/src/features/` holds five feature areas, and
`test/` holds tests for all of them. What was accurate, and still is, is the router.

## What is actually mounted

`lib/src/routing/app_router.dart` defines exactly three routes: a starting placeholder, a
sign-in screen, and — inside the one shell route — `menu` (`StorefrontHome`, the real
catalogue browsing screen) and `orders`, which renders `_UnbuiltRoute`, a literal
"not built yet" placeholder, even though a complete `OrdersPage` exists and is tested.
`AppShell`'s bottom navigation has exactly those two destinations. There is no
`Routes.cart`, `Routes.checkout`, or `Routes.profile` constant, and nothing outside
`lib/src/features/cart`, `checkout`, and `profile` themselves references those
directories.

**Cart, checkout, orders, and profile are built and tested, and none of the three is
reachable.** Each has its own controller, repository, and screens under
`lib/src/features/<name>/`, and its own test files under `test/features/<name>/`.
`lib/src/features/profile/profile_routes.dart` is the clearest evidence: it is a
complete, self-contained `GoRoute` subtree (profile, addresses, language, notifications)
whose own doc comment spells out the two wiring steps nobody has done — add its routes to
the shell, add `Routes.profile` to the destinations list. Wiring these four features into
the router is the single highest-leverage task in this application, whenever it resumes.

## What is built

| Area | Where |
|---|---|
| Design tokens as Dart constants, and the MOBILE `ThemeData` built from them | `lib/src/design/` |
| Router: shell, the two mounted routes above, a pure-function auth guard | `lib/src/routing/` |
| Keycloak Authorization Code + PKCE (system browser, not a WebView), refresh token in the keystore | `lib/src/auth/` |
| HTTP client honoring [ADR 0031](../platform/docs/adr/built/0031-http-api-conventions.md) — Problem Details, required `Idempotency-Key`, `If-Match`/`ETag`, cursor pagination, `{amountMinor, currency}` money, `Retry-After` | `lib/src/api/` |
| Money that does not divide UZS by a hundred (see below) | `lib/src/format/money.dart` |
| Catalogue browsing — the one mounted feature | `lib/src/features/catalogue/` |
| Cart, checkout, orders, profile — built, tested, **unmounted** (above) | `lib/src/features/{cart,checkout,orders,profile}/` |
| ru, uz-Latn, en, with a missing translation failing the build | `lib/src/l10n/`, `test/l10n/` |
| `android/`, `ios/` platform scaffolding (already generated; do not re-run `flutter create`) | `android/`, `ios/` |

It is not a generated OpenAPI client, and is not meant to be one yet: ADR 0035 wants a
client generated from a published, pinned OpenAPI document with CI failing on drift, and
that machinery does not run against this app. What is here is the transport and
conventions layer a generated client would sit on top of.

### The UZS bug this app specifically guards against

ISO 4217 gives UZS a decimal exponent of 2. This platform stores whole so'm as its minor
unit instead ([ADR 0018](../platform/docs/adr/partial/0018-deterministic-pricing-promotions-taxes-and-quotes.md)),
so `{"amountMinor": 84000, "currency": "UZS"}` is 84,000 so'm with nothing to divide. Any
formatter that asks ICU for the decimal places instead of using `MinorUnits`'s explicit
table shows a customer one hundredth of the real price — that shipped once, in this
codebase. `test/format/money_test.dart` is the regression test.

### Why tokens are vendored, not shared

`lib/src/design/horecaos_tokens.dart` is transcribed from `../frontend/design-tokens/tokens.css`
and carries a generated-output header. There is no shared package registry across the four
frontend apps yet, so each vendors its own copy; `test/design/token_drift_test.dart`
catches the Dart constants drifting from the vendored sheet beside them, but — like the
Angular apps' own drift checks — cannot catch both being edited away from the design
system together. One value deliberately differs: the vendored sheet's corner radius is
CONSOLE's `0px`; MOBILE takes FIELD's `8dp`, and the test asserts both numbers so that is
never a silent coincidence.

## Testing

Enough to prove the built features, not coverage theater:

| File | What it proves |
|---|---|
| `test/format/money_test.dart` | UZS is not divided by a hundred; an unknown currency throws |
| `test/api/api_client_test.dart` | Idempotency key on mutations, `If-Match`/`ETag`, Problem Details, cursor pagination, `Retry-After`, no personal data in telemetry |
| `test/auth/pkce_test.dart`, `test/auth/auth_session_test.dart` | RFC 7636 PKCE vectors; restore, rotation, `state`-mismatch refusal, concurrent-refresh collapse |
| `test/routing/guard_test.dart`, `test/routing/app_shell_test.dart` | The auth guard as a pure function; shell navigation |
| `test/design/theme_test.dart`, `test/design/token_drift_test.dart`, `test/design/design_system_lint_test.dart` | Tokens reach the theme; no ad hoc `Colors.*`/`TextStyle`/literal color outside the design system |
| `test/l10n/arb_parity_test.dart` | No missing, extra, or untranslated message in any locale |
| `test/features/{cart,checkout,orders,profile,catalogue}/` | The built, unmounted features — controllers, repositories, and screens |

No CI job builds or tests this app yet; `mobile/**` only feeds the path filter that
gates the frontend lint/build jobs in `.github/workflows/ci.yml`, it does not itself run
`flutter test`.

## Running it

```bash
cd mobile
flutter pub get
flutter gen-l10n     # generates lib/src/l10n/generated/, gitignored, required before analyze
flutter analyze
flutter test
flutter run \
  --dart-define=HORECAOS_API_BASE_URI=http://10.0.2.2:8080 \
  --dart-define=HORECAOS_OIDC_ISSUER_URI=http://10.0.2.2:8081/realms/horecaos \
  --dart-define=HORECAOS_OIDC_CLIENT_ID=horecaos-mobile \
  --dart-define=HORECAOS_OIDC_REDIRECT_SCHEME=uz.horecaos.mobile
```

`10.0.2.2` is the Android emulator's route to the host; an iOS simulator reaches the host
on `localhost`. `android/` and `ios/` are already committed — do not run `flutter create`
again over them. Nothing here has a recorded run against a live Keycloak realm; the derived
endpoint paths, the `horecaos-mobile` client's existence and redirect allowlist, and
whether the realm issues a refresh token at all are unverified until someone runs this
against one.

## What is deliberately absent

- **Dark theme.** The design system's token sheet has no dark palette yet; `themeMode` is
  pinned to light so a device in dark mode gets the approved palette rather than an
  invented one.
- **Maps.** [ADR 0035](../platform/docs/adr/partial/0035-angular-frontend-platform-and-design-system-adoption.md)
  names the community-maintained Yandex MapKit Flutter binding as the weakest dependency
  in the stack. Nothing is wired here yet.
- **Push notifications.** Needs `google-services.json` / `GoogleService-Info.plist`,
  neither of which belongs in git.
- **A courier application.** Nothing here has a dispatch screen, an assignment queue, or
  staff authentication, and this is not meant to grow one — a courier app is a different
  product with a different session model, for whenever ADR 0042 gets a frontend.
