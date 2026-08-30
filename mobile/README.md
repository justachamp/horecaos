# Qoida mobile

The Qoida customer ordering application for iOS and Android. Flutter, MOBILE
skin, per [ADR 0035][adr0035].

> **Nothing in this repository has ever been compiled.**
>
> Flutter and Dart are not installed on the machine this was written on. There
> was no `flutter create`, no `flutter pub get`, no `flutter analyze`, no
> `flutter test`, and no build of any kind. Every file here was written by hand
> from knowledge of the framework, checked against current package versions on
> pub.dev, and never handed to a toolchain that accepted it.
>
> Assume it does not build. The first run will find errors. There is a list of
> the ones most likely to bite in [What the first person with Flutter has to
> run](#what-the-first-person-with-flutter-has-to-run), and it is worth reading
> before starting rather than after.

## What this application is for

**A customer ordering application.** The same product as the storefront, on a
different platform.

That conclusion comes from reading the archived
`legacy-archive/milliy-ios` application in the platform repository — bundle name
`Rayhon`, against `https://api.rayhonmilliy.uz/api/v1/customers`. Its entire
screen inventory is one customer journey:

| Area | Screens in the archive |
|---|---|
| Onboarding and auth | Onboarding carousel, phone login, OTP verification |
| Home | Server-driven UI element blocks, categories, popular, special offers, search with recent searches |
| Product | Product detail with variants, modifier options, favourites |
| Cart | Cart, line editing, promotion entry |
| Checkout | Address selection, payment method, confirmation, order total breakdown |
| Orders | Active / completed / cancelled lists, order detail, tracking on a Yandex map, courier detail, rating |
| Profile | Profile edit, saved addresses with geocode and reverse-geocode, invitations, FAQ, support, notification settings, language, theme |

Its dependencies say the same thing: `YandexMapsMobile`, `SDWebImageSwiftUI`,
`FirebaseAnalytics`, `FirebaseMessaging`. Its API surface — `/accounts`,
`/carts`, `/orders`, `/addresses`, `/favourites`, `/payment-methods`,
`/invitations`, `/support` — is `/api/v1/customers/**` and nothing else.

There is no dispatch screen, no assignment queue, no shift state, no proof of
delivery, and no staff authentication path anywhere in it. **It is not a courier
application and not an operations client**, and neither is this one. A courier
application is a different product with a different session model and different
background-location behaviour; it belongs to whatever ADR 0042 settles.

ADR 0035 reaches the same conclusion independently, and quotes 207 Swift files
where a count of the application alone gives 152; the difference is 55 vendored
CocoaPods sources. Same archive, same answer.

## What is built

Foundations, and deliberately nothing else. No screens: those are built against
`docs/operations-spec/` and the prototypes by whoever takes them, and a
half-built screen is worse than an empty route.

| | Where |
|---|---|
| Design tokens as Dart constants, and the MOBILE `ThemeData` built from them | `lib/src/design/` |
| Closed type scale mapped onto every Material text slot | `lib/src/design/qoida_typography.dart` |
| Router with a shell, two routes, and a pure-function auth guard | `lib/src/routing/` |
| Keycloak Authorization Code + PKCE, system browser, refresh token in the keystore | `lib/src/auth/` |
| HTTP client honouring [ADR 0031][adr0031] | `lib/src/api/` |
| Money that does not divide UZS by a hundred | `lib/src/format/money.dart` |
| ru, uz-Latn, en, with a missing translation failing the build | `lib/src/l10n/`, `test/l10n/` |
| Tests for the shell, the guard, the client, PKCE, the tokens and the locales | `test/` |

### The MOBILE skin

ADR 0035 restates the skin in Flutter's idioms, and most of the work is saying
what Flutter's own design system is switched **off**:

- Material 3 surface **tint** elevation is disabled scheme-wide. This system
  expresses depth with one shadow and a hairline, never by recolouring a
  surface by depth.
- The ink **ripple** is replaced with `NoSplash`. A ripple is decoration, and
  there is none here.
- Shapes are the **8dp FIELD radius**, set explicitly on cards, dialogs, sheets
  and buttons. Material's 28dp sheet corner and the CONSOLE 0px corner are both
  wrong for a phone.
- Minimum targets are **48dp on both platforms** — Material's number rather than
  iOS's 44, because one codebase should not render as two products.
- Page transitions are pinned to one builder for **every** `TargetPlatform`, so
  an iOS build does not inherit the Cupertino slide.
- `MediaQuery.textScaler` is honoured and **clamped** to 0.85–1.5. A closed type
  scale does not survive a 200 % system setting.
- Locales carry the script subtag: `Locale.fromSubtags(languageCode: 'uz',
  scriptCode: 'Latn')`. uz-Latn and uz-Cyrl are not the same locale, and the
  archive's bare `uz` was ambiguous.

Adaptive Cupertino/Material widgets are not used. One Material-based,
Qoida-skinned widget set runs on both platforms, because "one codebase" stops
being true the moment the tree branches on platform.

### The API client

Written against `docs/adr/built/0031-http-api-conventions.md` **and** against the
platform's own `ApiProblem`, `ErrorCode`, `AggregateVersion`,
`IdempotencyInterceptor`, `CorrelationIdFilter`, `ApiMoney` and `Page`, plus
`StorefrontOrderingController` as a worked example. Not against the ADR text
alone: the ADR spells the correlation header `X-Correlation-Id` and the filter
spells it `X-Correlation-ID`, which is harmless — HTTP field names are
case-insensitive — but is the kind of difference that only reading both finds.

- **Problem Details.** `application/problem+json` is parsed into a
  `ProblemDetails` carrying `code`, `correlationId`, field errors and the
  extension members the platform actually sets (`currentVersion`, `reason`,
  `requiredCapability`). Callers branch on `code`, never on `title`. An unknown
  code decodes rather than throwing, because ADR 0031 evolves a major version
  additively.
- **Idempotency.** Every mutation takes an `IdempotencyKey` as a **required**
  parameter with no default. The key belongs to a user intent — one tap of
  "place order" — not to an HTTP call, so it survives a retry. That is why the
  client's automatic retry after a 401 refresh is safe, and it is tested.
- **Expected version.** `If-Match: W/"7"`, the weak validator the platform's
  `AggregateVersion` renders. `ETag` on the way back is parsed into an `int` so
  no caller assembles a header.
- **Cursor pagination.** `?cursor=&limit=` in, `{items, nextCursor}` out. There
  is no total and no page number, and there will not be one.
- **Money** is `{amountMinor, currency}` and never a bare number.
- **`Retry-After`** is read on 429 and 503. The HTTP-date form is deliberately
  not parsed — honouring it needs a trusted clock and a phone's clock is not one.

**It is not a generated client, and it is not meant to be one.** ADR 0035
requires clients generated from a published, pinned OpenAPI document with CI
failing on a regeneration diff. That machinery does not exist yet. What is here
is the transport and the conventions — the layer a generated client sits on.
Response *types* are deliberately absent, because hand-copying those is the
exact failure ADR 0035 names.

### Money, and the bug this repeats

`MinorUnits` is a table, and it exists because ISO 4217 and this platform
disagree. ISO gives UZS an exponent of 2. The platform stores **whole som** as
its minor unit (ADR 0018), so `{"amountMinor": 84000, "currency": "UZS"}` is
`84 000 so'm` and there is nothing to divide.

Any formatter that asks ICU for the decimal places — `NumberFormat.currency`
without an explicit `decimalDigits`, `NumberFormat.simpleCurrency`, `Intl`
on the web — gets the ISO answer and shows a customer one hundredth of the
price. That shipped in this codebase in August 2026.
`test/format/money_test.dart` is the regression test. An unrecorded currency
throws rather than defaulting to 2, because an exception at the seam is
recoverable and a wrong number on a receipt is not.

The group separator is a **no-break space**, so `84 000` never wraps between the
`84` and the `000` on a narrow row.

### Authentication

Authorization Code with PKCE (S256 only) against the `qoida` realm, in the
**system browser** — `ASWebAuthenticationSession` on iOS, Custom Tabs on Android
— never a WebView. A WebView would put the customer's Keycloak password inside
this process and would forgo the system cookie jar, so single sign-on between
our surfaces would stop working.

- The access token lives in memory and dies with the process.
- **The refresh token is in the keystore.** ADR 0035 makes this a considered
  difference from the web surfaces, which persist nothing: a customer
  application that demands a fresh login every session is a deleted application.
- `state` is verified **before** the code is read. An unchecked `state` is a
  login-CSRF that signs the customer into the attacker's account.
- Concurrent refreshes collapse into one redemption, because Keycloak rotates
  the refresh token and three parallel redemptions invalidate each other.
- The realm's endpoints are derived from the issuer using Keycloak's fixed
  layout rather than fetched from `/.well-known/openid-configuration`. Discovery
  is the more correct answer; deriving avoids a round trip on cold start before
  the customer has done anything, and is the first thing to revisit once there
  is a reachable realm to try it against.

Capability checks in a client are a usability affordance. The API is the
enforcement point ([ADR 0025][adr0025]).

## What is deliberately absent

- **Screens.** Two routes render "not built yet" in the customer's language.
  That is the point.
- **Dark theme.** Not a decision to ship light-only — a gap. The design system's
  token sheet has no dark palette to vendor, and inventing one here would put
  colours in this repository that the design system never approved and the three
  Angular applications would not match. `themeMode` is pinned to light so a
  device in dark mode gets the approved palette rather than Material's inversion
  of it.
- **Maps.** ADR 0035 names the community-maintained Yandex MapKit Flutter
  binding as the weakest dependency in the stack, with a platform-view fallback.
  Nothing is chosen here, because choosing a map binding that cannot be run is
  choosing badly.
- **Push notifications.** Firebase Messaging is a clean carry-over from the
  archive and needs `google-services.json` / `GoogleService-Info.plist`, neither
  of which belongs in git.
- **The IBM Plex Sans faces.** See `assets/fonts/README.md`.
- **The Qoida icon font.** `QIcon` is the seam and maps onto Material icons
  today. Call sites already go through it, so replacing the set moves no call
  site.
- **id_token validation.** The token is kept for `id_token_hint` on logout and
  nothing in this application makes an authorization decision from its claims.
  Signature, issuer, audience and nonce checks need a reachable realm to test
  against.
- **`android/` and `ios/`.** Generated by `flutter create`, and hand-writing a
  `project.pbxproj` or a Gradle wrapper is a guaranteed-wrong way to spend an
  afternoon. The recreate command and the configuration that must follow it are
  below.
- **A generated OpenAPI client, a consumer manifest, and CI.** All three are
  ADR 0035 checklist items that need a published document and a pipeline.

## Why the tokens are vendored

`lib/src/design/qoida_tokens.dart` and `qoida_typography.dart` carry a
generated-output header and are transcribed from
`design-tokens/tokens.css`, which is itself a verbatim copy of
`frontend/prototypes/control-plane/src/tokens.css` in the platform repository.

The four applications each vendor their own copy because **there is no shared
package registry yet**, and picking one is a platform decision this repository
should not make on its own. A published design-system package with a version is
the right answer; it needs a registry decision first, and inventing one here
would commit three other repositories to it.

`test/design/token_drift_test.dart` is the drift check, in the form that is
possible today: it parses the vendored CSS and asserts every Dart constant still
matches. It catches a Dart file edited away from the sheet beside it. It does
**not** catch both being edited together and away from the design system — only
ADR 0035's `sync-tokens`, in the platform repository, can do that, and it is not
written yet.

The one value that deliberately differs is the corner radius: the vendored sheet
is CONSOLE, where `--q-radius` is `0px`, and MOBILE takes FIELD's 8dp. The test
asserts both numbers so a future sheet carrying a FIELD radius cannot make the
agreement a coincidence.

## Localisation

ru, uz-Latn and en, through `flutter gen-l10n` and the ARB files in
`lib/src/l10n/`.

`gen_l10n` does **not** fail on a missing translation. It silently emits the
template locale's string, so a forgotten Uzbek message ships as English and
nobody finds out until a customer does. `l10n.yaml` writes the list to
`l10n_untranslated.json`, which is a file nobody reads.

`test/l10n/arb_parity_test.dart` is the gate. It fails the build when a locale
is missing a message, carries one the template does not have, or leaves a value
byte-identical to the English — with a named escape list for the two strings
that genuinely are the same in two locales.

Content is authentic Russian and Uzbek, not machine-translated placeholder.

## Running it

Nothing here has been run. These are the commands, not a record of them working.

```bash
cd frontend/mobile

# 1. Recreate the platform folders. This writes android/ and ios/ into the
#    existing tree and leaves lib/, test/ and pubspec.yaml alone.
flutter create --platforms=ios,android --org uz.qoida --project-name qoida_mobile .
# Read `git status` before committing what it wrote. It is safe on an existing
# tree in principle; everything here is committed already so that "in principle"
# never has to be trusted.

# 2. Resolve.
flutter pub get

# 3. Generate the localisations. Nothing in lib/ analyses until this has run:
#    lib/src/l10n/generated/ is gitignored and does not exist in a fresh clone.
flutter gen-l10n

# 4. Analyse and test.
flutter analyze
flutter test

# 5. Run against a local platform and Keycloak.
flutter run \
  --dart-define=QOIDA_API_BASE_URI=http://10.0.2.2:8080 \
  --dart-define=QOIDA_OIDC_ISSUER_URI=http://10.0.2.2:8081/realms/qoida \
  --dart-define=QOIDA_OIDC_CLIENT_ID=qoida-mobile \
  --dart-define=QOIDA_OIDC_REDIRECT_SCHEME=uz.qoida.mobile
```

`10.0.2.2` is the Android emulator's route to the host. An iOS simulator reaches
the host on `localhost`. The defaults in `AppConfig` point at `localhost:8080`
and `localhost:8081` — wrong for every environment except an iOS simulator, on
purpose, so a build that forgets `--dart-define` fails to reach anything rather
than quietly pointing at production.

## What the first person with Flutter has to run

In order, with what to expect.

1. **`flutter create --platforms=ios,android .`** — then commit the result. It
   does not touch `lib/`, `test/`, `pubspec.yaml` or `README.md`.

2. **`flutter pub get`.** The most likely first failure is `intl`. The
   constraint is `any` rather than a caret range on purpose:
   `flutter_localizations` pins `intl` to one exact version, and a caret range
   fights that pin instead of resolving. If it still fails, take the version
   `flutter_localizations` names.

3. **`flutter gen-l10n`.** Everything in `lib/` references
   `lib/src/l10n/generated/app_localizations.dart`, which is generated and
   gitignored. Nothing analyses before this runs. Check that `app_uz_Latn.arb`
   produced `Locale.fromSubtags(languageCode: 'uz', scriptCode: 'Latn')` and not
   a country subtag — the whole point of the script tag is lost if it did not.

4. **`flutter analyze`.** Expect errors, and expect most of them in
   `lib/src/design/qoida_theme.dart`. Material's theme-data classes were
   renamed in Flutter's normalisation work (`CardTheme` to `CardThemeData` and
   so on) and the exact set that has moved in 3.47 was checked against the API
   docs but not against a compiler. The other candidates, in rough order of
   likelihood:
   - `FlutterWebAuth2Options` field names in `flutter_web_auth_2` 5.x
     (`lib/src/auth/authorization_browser.dart`).
   - `AndroidOptions` / `IOSOptions` in `flutter_secure_storage` 11, which
     replaced the deprecated `encryptedSharedPreferences` path
     (`lib/src/auth/token_store.dart`).
   - `FadeForwardsPageTransitionsBuilder`, if it is named differently in this
     release (`lib/src/design/qoida_theme.dart`).
   - `NumberFormat.symbols.DECIMAL_SEP` in `intl`
     (`lib/src/format/money.dart`).

   Every one of these is a name, not a design. Fixing a name does not change
   what the file decided.

5. **`flutter test`.** These tests need no network, no realm, and no platform
   channel — everything crossing a boundary is behind an interface with a fake.
   `test/routing/app_shell_test.dart` needs step 3 to have run.

6. **A debug build on both platforms.** `flutter build apk --debug` and
   `flutter build ios --debug --no-codesign`.

7. **Then, and only then, treat any of it as working.** ADR 0035's checklist
   says the same thing in the same words.

### After `flutter create`, configure

- **Android** — declare the redirect scheme so the browser can hand the
  authorization code back. `flutter_web_auth_2` needs its callback activity in
  `android/app/src/main/AndroidManifest.xml` with
  `android:scheme="uz.qoida.mobile"`, and `minSdk` at 23 or above for
  `flutter_secure_storage`.
- **iOS** — add `uz.qoida.mobile` to `CFBundleURLTypes` in `Info.plist`, and
  enable the Keychain Sharing capability if the token store needs it.
- **Keycloak** — the `qoida-mobile` client must be **public** (no secret, none
  can be kept in a binary), with PKCE required at S256, and
  `uz.qoida.mobile://oauth/callback` allowlisted **exactly**. A wildcard
  redirect on a public client lets any application registering the same scheme
  receive the authorization code.

## What cannot be verified without a running Keycloak

Everything below is written, tested against a fake, and unproven against a real
realm:

- That the derived endpoint paths match this realm's. They follow Keycloak's
  fixed layout under an issuer, and a realm behind a reverse proxy that rewrites
  paths would break them.
- That `qoida-mobile` exists, is public, and has the exact redirect allowlisted.
- That the realm issues a refresh token at all. It requires the
  `offline_access` scope or a session-idle configuration that permits it; a realm
  that does not will sign the customer out on every cold start, and the symptom
  will look like a bug in `AuthSession.restore`.
- That refresh-token rotation behaves as assumed. The collapse-concurrent-
  refreshes logic is written for a realm that rotates; a realm that does not
  will still work, and one that rotates *and* revokes the whole family on reuse
  will be less forgiving than the tests are.
- That `ui_locales=uz-Latn` resolves to a login theme. A realm with no `uz`
  theme falls back to its default. Cosmetic, and visible.
- That the platform accepts the token's audience as `qoida-api`.

## Testing

Enough to prove the shell, the guard and the API client, and no coverage
theatre.

| File | What it proves |
|---|---|
| `test/format/money_test.dart` | UZS is not divided by a hundred; grouping; an unknown currency throws |
| `test/api/api_client_test.dart` | Idempotency key present on mutations and stable across a refresh retry; `If-Match`/`ETag`; Problem Details including an unknown code; cursor pagination; `Retry-After`; **no personal data in telemetry** |
| `test/auth/pkce_test.dart` | The RFC 7636 appendix B vector; verifier length and alphabet; no repeats |
| `test/auth/auth_session_test.dart` | Restore, rotation, `state` mismatch refused before any exchange, concurrent-refresh collapse, proactive refresh, sign-out with the realm unreachable, no token in `toString` |
| `test/routing/guard_test.dart` | The guard as a pure function, including that it never redirects to where it already is |
| `test/routing/app_shell_test.dart` | Signed-out lands on sign-in, restored lands in the shell, the bar navigates, a sign-out moves without any screen asking |
| `test/design/theme_test.dart` | Tokens reach the theme; tint, ripple and elevation are off; 8dp/48dp; every text slot filled from the closed scale |
| `test/design/token_drift_test.dart` | Every Dart constant still matches the vendored sheet |
| `test/design/design_system_lint_test.dart` | No `Colors.*`, colour literal, `TextStyle(`, `fontSize:`, `Icons.` or emoji outside the design system |
| `test/l10n/arb_parity_test.dart` | No missing, extra, or untranslated message in any locale |

`design_system_lint_test.dart` is a source scan rather than a lint rule.
Expressing "no `TextStyle` at a call site" in the analyzer needs `custom_lint`,
which is another dependency and another unverifiable thing. The scan is cruder,
has no dependencies, and fails the same build. If `custom_lint` is adopted, that
test is what it replaces.

## This repository is not a submodule yet

ADR 0035 wires the four applications into the platform repository as git
submodules under `frontend/`. That is not done, because `gh` is not installed
and the four GitHub remotes do not exist. A submodule pointing at a URL that
does not resolve breaks `git clone --recursive` for everyone, and the platform
repository is pushed and shared.

So this is a standalone git repository with its own history and no remote.
Nothing here is staged in the platform repository's index, and there is no
`.gitmodules`. Promotion happens once the remotes exist: push, `git submodule
add` for all four, verify a recursive clone into a clean directory, and commit
`.gitmodules` with the four gitlinks in one commit.

[adr0031]: ../../docs/adr/built/0031-http-api-conventions.md
[adr0035]: ../../docs/adr/partial/0035-angular-frontend-platform-and-design-system-adoption.md
[adr0025]: ../../docs/adr/built/0025-fine-grained-authorization-and-capability-model.md
