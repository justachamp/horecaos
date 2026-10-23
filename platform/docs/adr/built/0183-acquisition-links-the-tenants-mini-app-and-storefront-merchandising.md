# ADR 0183: Acquisition links that work, the tenant's Mini App, and the storefront's brand switcher, branch order and layout

- Decision status: Accepted — proposed by Claude (batch 21, wave `w8-acquisition-merchandising`); accepted by the platform owner 2026-10-14
- Implementation status: Built — everything this record specifies exists and is exercised by a test: `V0674` to `V0676`,
  the Telegram channel's own settings and the guided Mini App setup, server-built links with the bot and Mini App landings, the
  dark page-load count, the funnel alert and the provisional-retention check, and the brand switcher, branch order and layout
  with their console editors (batch 21, 2026-10-23). The three facts outside the platform stay open and are named, with where each
  value goes and how to prove it, in `docs/runbooks/external-connections/w8-acquisition-merchandising.md`: the BotFather round
  trip, counsel's answers (per-person attribution stays declined, the page-load count stays off, the 400-day retention stays
  provisional), and a tenant that asks for the marketplace layout.
  **Correction, 2026-10-23 (fix wave H, the menu button's way back).** The Rollout section's «rollback of any of them is removing
  the reading call» did not hold for the menu button, and nor did the runbook's first draft («goes back to the bot's default on
  the next apply»): the button is held at Telegram and nothing reset it, so a Mini App switched off, or a hostname that stopped
  being verified, left the bot opening the storefront. It does now. While Telegram shows a Mini App button the brand's setup no
  longer stands behind, the `MENU_BUTTON` step reports `STALE_BUTTON` and `TelegramMiniAppSetupService.applyMenuButton` sets
  Telegram's default back, reads it back, and writes the audit fact `integration.telegram_menu_button_reset` only when the read-back
  agrees (`MiniAppAndLinksEndpointTests`: `aMenuButtonOfAMiniAppSwitchedOffCanBeTakenOff`, `anAddressThatLapsesCanBeTakenOff`,
  `takingTheButtonOffIsReadBackToo`; another brand's live Mini App is left alone, `anotherBrandsLiveMiniAppIsNotTakenOff`).
  `TelegramCustomerLanding` puts a chat's own Mini App button back to the default at that customer's next `/start`
  (`TelegramBotLandingTests.aMiniAppSwitchedOffTakesTheChatsButtonOff`). Still outside the platform: a chat that never starts
  the bot again, and a main Mini App address held in @BotFather; the runbook says where each is corrected.
  **Correction, 2026-10-23 (fix wave H, the no-point pickup search).** D17 and the endpoint's description said that with no
  point the public search lists «the brand's branches in the order the brand set». The request named no brand and the query had no
  tenant or brand predicate (the search is platform-wide by design, with a point as without), so the answer was every brand's
  branches sorted by one raw `storefront_sort_order`: numbers one brand chose were compared with numbers another chose, and a brand
  that placed nothing sorted behind every placed branch of every other brand. `brandId` is now an optional field of the request
  that limits the search to one brand, whose own order then applies exactly as D17 says (a brand's own storefront names itself).
  Without it, a search with no point orders by each branch's rank inside its own brand's order, then by the brand's name, so every
  brand's first branch precedes any brand's second; one brand's order is never disturbed by another's numbers
  (`StorefrontBranchOrderEndpointTests.noPointAcrossBrandsInterleavesEachBrandsOwnOrder`, `aBrandNamedInTheSearchGetsItsOwnOrderAlone`).
  The customer apps still do not call the search, so the runbook says what a branch chooser built on it must send.
- Date proposed: 2026-10-14
- Date decided: 2026-10-14
- Deciders: proposed by Claude (batch 21); Ayubkhon Abbosov (platform owner) decides
- Depends on: [ADR 0025](../built/0025-fine-grained-authorization-and-capability-model.md),
  [ADR 0027](../built/0027-audit-evidence-and-approval-model.md),
  [ADR 0028](../partial/0028-secrets-management-and-credential-lifecycle.md),
  [ADR 0029](../partial/0029-pii-protection-envelope-encryption-and-key-rotation.md),
  [ADR 0030](../built/0030-configuration-and-policy-resolution.md),
  [ADR 0031](../built/0031-http-api-conventions.md),
  [ADR 0033](../built/0033-caching-rate-limiting-and-shared-runtime-state.md),
  [ADR 0036](../partial/0036-sales-channels-and-location-serviceability.md),
  [ADR 0044](../partial/0044-marketing-campaigns-audiences-and-engagement.md),
  [ADR 0055](../meta/0055-greenfield-launch-scope.md),
  [ADR 0058](../partial/0058-telegram-notification-channels.md),
  [ADR 0065](../partial/0065-tenant-self-service-integrations-and-the-write-only-secret-door.md),
  [ADR 0068](../built/0068-tenant-terms-of-service-documents.md),
  [ADR 0075](../partial/0075-a-customer-orders-from-the-chat-they-are-already-in.md),
  [ADR 0157](../partial/0157-content-and-merchandising-slots.md),
  [ADR 0167](../partial/0167-behavioural-telemetry-lawful-basis-and-the-funnel.md)
- Supersedes / Superseded by: — (amends nothing by editing it. It answers four open inputs and adds a dated
  status addition to each record it touches: ADR 0167 (D16 and D18), ADR 0157 (D17), ADR 0075 (D27), and ADR 0044
  (D16 and the link formats). No Decision, Alternatives or Consequences section of any of them is rewritten)
- Open inputs: each is closed on the value the owner chose; the ones that name a person other than the owner, or an
  external fact, stay with that person and the work they block is marked.
  - **Whether recording which link brought an account, or an order, needs consent or another lawful basis**
    (legal counsel; ADR 0167 open input 2). Blocks: only tier 2, which D16 declines to build until this is answered.
  - **Whether a cookie-less, identifier-less page-load count needs a lawful basis** (legal counsel; ADR 0167 open
    input 1, narrowed). Blocks: only turning the D18 counter on, for any brand. It ships dark.
  - **The BotFather round trip** (the person who holds the tenant's @BotFather account — a tenant's owner or admin).
    Only the holder can send `/newbot` and `/newapp`, and Telegram has no API for either. Everything else here is
    built and tested against a fake Bot API. Blocks: proving the Mini App link against a real bot; it is documented
    in `docs/runbooks/external-connections/w8-acquisition-merchandising.md`.
  - **Finance and counsel's confirmation of the 400-day funnel retention** (ADR 0167 open input 4). Blocks: clearing
    the provisional flag D18's startup check keeps reporting. The 400-day default stays in force.
  - **A signed tenant that runs two brands on one storefront and asks for the marketplace layout** (platform owner,
    product; ADR 0157 open input). Blocks: switching `storefront.layout` to `MARKETPLACE` for a real brand. The layout
    is built and dark.

**To accept as written:** the owner has accepted this record by the instruction recorded below.

**Decision record, 2026-10-14.** Accepted by Ayubkhon Abbosov (platform owner) with the instruction "accept all defaults, start batch 21" (batch 21); each decision below is taken at the value the owner chose or, where the owner said nothing else, at the smallest default the batch-21 plan proposed.

## Context

Three rows of the operations gap map each stop on a decision no engineer can take for the owner, and all three
sit on one thing: the way a person *arrives* at a restaurant's storefront.

**Row `6.6a`** — a marketer can mint an acquisition link and see its click count, and cannot copy a working link. The
console renders the literal text `https://{tenant-domain}/?ref=…` and `https://t.me/{bot}?start=…`, a Mini App
link is rendered with `?start=` where Telegram's direct link needs `?startapp=`, and a `MOBILE_APP` link is offered
although the Flutter app is on hold (ADR 0055). The bot answers `/start <token>` with «invalid or expired code», because
the only two payload families it knows are a customer link code and `auth_<code>`. A Mini App opened from a
`?startapp=` link counts nothing, because the storefront reads only `?ref=`. And nothing guides a tenant through the part
of Telegram that only a human can do: `/newapp` in @BotFather, naming a short name, pointing the menu button at the
storefront.

**Row `7.6c`** — the funnel exists with its visits stage honestly labelled «not measured». Its alert is missing (the one
unrepairable loss, a closed day with no funnel row, pages nobody), the provisional retention flag ADR 0167 asks for
is not set, and three pieces of housekeeping the record's checklist owes are not done.

**Row `6.8`** — home groups, the offer carousel and the preview are built. The brand switcher entries, the branch order
and the marketplace layout wait on ADR 0157's Release 4 trigger: one tenant running two brands on one storefront
asking for them.

**Row `10.5`** — the Telegram card of Channel setup shows a status line and a link to Integrations. The fields
`settings.md` §10.5 lists for TELEGRAM (menu button label and URL, Mini App mode, per-language texts, the public-offer
link) do not exist, and ADR 0075's open input «where the tenant's Mini App lives» has stood open since 2026-09-06.

The decisions below are the owner's, taken on the defaults the batch-21 plan proposed.

## Decision

1. **D16 — Per-person attribution is declined until counsel answers. Anonymous link counts only.** Which link
   brought a given *account* or a given *order* is not recorded, not even dark: no `acquisition_link_id`, no
   `attribution_link_id`, no `marketing.attribution_recording` policy, no erasure null-out and no storefront code
   that carries a token to sign-up or checkout. ADR 0167 Decision 4 tier 2 stays a specification and nothing more. What
   exists is what Decision 4 tier 1 describes: an anonymous click series per link (now fed by the bot and the Mini
   App as well as the web storefront), registrations by origin, and orders by channel, with the sentence on the
   screen that says the chart does not attribute a customer to a link. Recorded as a decline in the gap map, so the
   rows read «NOT BUILT by decision» for that half. The audience predicate `ACQUISITION_CHANNEL` therefore continues to
   select nobody, which is the same decision seen from the audience builder.
2. **D17 — Waive ADR 0157's Release 4 trigger for the branch order and the brand switcher entries; keep the marketplace
   layout dark.** The branch order (`tenant.locations.storefront_sort_order`) and the switcher entries
   (`content.brand_switcher_entries`) are fully specified in ADR 0157 and do not depend on a tenant asking, so they are
   built now. They render nothing until an entry exists or a sort order is set, so a single-brand tenant sees exactly
   what it sees today. The marketplace layout is built behind a brand-scoped policy `storefront.layout` in
   `{STANDARD, MARKETPLACE}`, default `STANDARD`, served on the storefront's existing content read; it stays dark in the
   sense that no brand has it set, and the console says what it changes before it is switched. ADR 0157's other
   Release 4 sentence, that «no layout field exists before then», is answered here: the field exists and is this one.
3. **D18 — A visits stage without client telemetry is a page-load *count*, labelled «page loads, not people», dark
   behind a platform flag.** A storefront posts one anonymous ping per tab session to a public, rate-limited endpoint;
   the platform adds one to a per-day, per-brand, per-channel counter. No cookie, no identifier, no address, no user
   agent, no session, no path, and no distinct-visitor or page-view figure. Whether such a counter needs a lawful basis
   is a legal call engineering cannot assert, so the flag `reporting.page_load_count_enabled` is a `PLATFORM`-scope
   boolean that defaults to false, and no storefront calls the endpoint until it is true. Recorded as a dated addition
   to ADR 0167, whose Decision 1 («no emitter in the storefronts») is therefore not rewritten but is read with this
   addition: the one emitter allowed is this counter, and only after counsel answers.
4. **D27 — The tenant's Telegram Mini App lives at the brand's verified storefront hostname; its menu button is set on
   the platform's own call, in mini-app mode.** The Mini App URL is never typed. It is `https://<hostname>/` where the
   hostname is the verified `tenant.channel_hostnames` row of the active WEB channel that sells at the brand's
   branches (ADR 0036; DNS-TXT verified, ADR 0036 batch 13). That closes ADR 0075's open input and the trust question
   in it: the platform vouches for a hostname it has already verified, and a tenant supplies no URL. The per-channel
   field set is the one `settings.md` §10.5 lists: menu button label (per language), Mini App on or off, welcome text (per
   language, falling back to the platform's wording), and a public-offer link (the tenant's ADR 0068 terms page on the
   same hostname). The «dynamic button» in that list is **not** built, and the IA row is amended to say so.

Beneath the four, the engineering decisions that make row `6.6a` and row `7.6c` real:

5. **A link's URL is computed on the server, once, and never stored.** `GET` of a brand's links returns `url` and,
   when it cannot be built, `unavailable` with a code that names what is missing. WEB is
   `https://<verified hostname>/?ref=<token>`; a bot link is `https://t.me/<bot username>?start=<token>`; a Mini App
   link is `https://t.me/<bot username>/<short name>?startapp=<token>`. `MOBILE_APP` is no longer offered and a new
   one is refused (the app is on hold, ADR 0055); an existing row keeps reading.
6. **A click on a Telegram link is counted by the bot and by the Mini App, through the one public click path.** The
   bot counts a `/start <token>` for an `ACTIVE` link of the same brand once, then answers exactly as a bare
   `/start`. The Mini App counts a launch whose start parameter is a token, through the same anonymous endpoint the
   web storefront already calls. One implementation (`marketing.api.AttributionClickPort`), two rows.
7. **The Mini-App/BotFather setup is a guided flow whose steps are derived from facts, never from a progress table.**
   Bot connected, webhook registered, storefront hostname verified, short name recorded, menu button read back from
   Telegram, test link shown. Closing the page and returning resumes at the first step whose fact is false.
8. **Retention that is provisional says so, and the platform will not start production on it where it governs
   personal data.** `ConfigurationKey` gains a provisional marker. At startup on a non-local profile every provisional
   key is reported; a provisional key marked as governing personal data refuses the start. `reporting.funnel_retention_days`
   is provisional and governs no personal data (the rows are counts), so it is reported and never refuses.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| Build tier 2 dark behind `marketing.attribution_recording` (ADR 0167 «Decision 4a») | Owner chose to decline. It would have added columns to `customer.customer_accounts` and `ordering.orders`, a composite foreign key, an erasure participant and token carriage in both storefronts, for a feature no brand could switch on before counsel answers; ADR 0167 itself weighs a dormant feature against ADR 0046's distaste for them | Counsel confirms a basis or a consent purpose in writing; the next record then specifies the build on that answer |
| Split row `6.6a` so it closes without tier 2 | Equivalent to the decline in effect; the decline is recorded on the row instead, so the row's text and the code agree | Never |
| Re-scope IA 7.6 and `statistics.md` Band D so visits are the tenant's own GA4 (ADR 0106, row `10.8e`) | Gives the funnel no top stage on the platform's own screen, and a tenant without GA4 has none at all. ADR 0167 already rejected reading GA4 into the funnel | Counsel rules out any first-party count permanently |
| Count page loads from the reverse proxy's access log | An address is personal data and the logs are held «PII-free by construction» (ADR 0029). A log that is a measurement source is not | Never; the answer is a basis, not a cleverer source |
| Turn the page-load counter on now | Whether a count with no identifier needs a basis is a legal question; ADR 0167's asymmetry argument (a chart stage is recoverable, an unlawfully held year is not) applies to a counter as much as to an event | Counsel answers |
| Keep ADR 0157's Release 4 trigger for everything | Honours the record literally. Leaves the home group order, the branch order and the switcher unbuilt behind a trigger the owner has now waived for the parts that need no tenant | A part turns out to need a tenant's wishes (the marketplace layout still does, and still waits) |
| Waive the trigger for the marketplace layout too | The layout is a presentation mode with no record of what it must look like beyond one row of the parity matrix. Building it dark costs a screen; switching it on without a tenant that wants it costs trust | A tenant asks, and says what its category navigation should do |
| Let the tenant type the Mini App URL | A tenant-supplied URL the platform renders as a button inside a message it sends is a link the platform vouches for (ADR 0075's own words). The verified hostname is a URL the platform has already proved the tenant controls | The Mini App is served from a host other than the storefront's |
| A separate hostname for the Mini App | Two hosts to verify and keep in step for one codebase that ADR 0035 says already serves both | ADR 0035 splits the two builds |
| Store the Mini App short name on the bot installation's `non_sensitive_config` | There is no tenant-facing writer for that column (ADR 0075's finding), and the short name is a fact about a *channel* the tenant edits in Channel setup, beside the other fields. Kept in the per-channel settings table; read by the link renderer through a port | Another channel type needs a per-installation fact the installation row is the better home for |
| A progress table for the setup flow | A second copy of facts that already exist, which would drift the moment someone edits the hostname in another screen | Never; derive |
| Refuse every provisional retention key at startup on a production profile (ADR 0029's literal guard) | Would refuse a start over a figure that governs counts and no person, for a pilot whose operator cannot clear it (counsel's answer is outside the platform) | A provisional key governing personal data is added; it refuses by this record |

## Consequences

### Positive

- A marketer copies a link that works, and sees why one does not (no verified hostname, no bot, no short name).
- A customer who taps a Telegram link is counted, and is greeted as a customer and not told their code expired.
- A tenant is led through the one part of Telegram only a human can do, and the screen never claims a step is done that
  Telegram has not confirmed.
- The one unrepairable loss in the funnel (a closed day with no row) pages somebody.
- The brand switcher, branch order and layout exist and cost a single-brand tenant nothing.
- Nothing here collects a fact about a person, so nothing here adds to an erasure request.

### Negative

- **The chart still cannot say which customers an influencer brought.** It shows how many opened the link, how many
  registered and how many ordered, side by side, and says so. The audience predicate `ACQUISITION_CHANNEL` selects
  nobody until tier 2 exists, and the audience builder still offers it.
- **A Telegram deep-link click is counted when the bot is started, not when the customer does anything.** A customer who
  taps the link twice in a minute is one click; one who taps it and walks away is also one click.
- **The page-load count is the weakest number on the screen.** It counts a tab opening, not a person, and not even a
  distinct tab if the browser refuses storage. It is labelled so, drawn with no ratio, and off.
- **The brand switcher and the branch order are built before the tenant that needs them.** If that tenant's wishes
  differ from ADR 0157's sketch, the sketch changes and so does the code.
- **A hostname decides everything for the Mini App.** A brand with no verified WEB hostname at its branches has no
  Mini App, no web link and no setup flow beyond the first steps. That is the right failure, but it is a dependency on
  the DNS-TXT verification of batch 13.
- **One more place a Bot API call can fail the screen.** Reading the menu button back is a live call; when Telegram is
  unreachable the step shows «could not check», not «not done».
- **No customer-facing screen consumes the branch order yet.** Neither storefront has a branch chooser (each uses the
  location its deployment is configured with and calls no pickup search), so the order is served by
  `POST /api/v1/storefront/pickup-locations` and proved there; a customer sees it once a chooser is built on that endpoint.

### Accepted trade-offs

- The dynamic button the parity matrix lists is not built and the IA is amended to say so.
- A provisional key that governs counts is reported and never refuses a start; the owner accepts a quieter guard for a
  figure that holds no person.
- The marketplace layout is built without a tenant to design it with. It is small on purpose: compact category
  navigation and an all-categories overlay, the two things the matrix row names.

## Specification

### Telegram per-channel settings (D27) — tenancy

```text
tenant.telegram_channel_settings                   -- V0674
  tenant_id, channel_id            PK; FK (tenant_id, channel_id) -> tenant.sales_channels ON DELETE CASCADE
  brand_id null                    FK (tenant_id, brand_id) -> tenant.brands; the brand whose storefront the Mini App opens
  mini_app_enabled boolean not null default false
  mini_app_short_name varchar(64) null   -- the BotFather /newapp short name; ^[A-Za-z0-9_]{3,64}$
  public_offer_link boolean not null default false
  created_at, updated_at

tenant.telegram_channel_texts                      -- V0674
  tenant_id, channel_id, kind ('MENU_BUTTON_LABEL' | 'WELCOME'), locale, body   PK (tenant_id, channel_id, kind, locale)
  body <= 64 characters for a label, <= 1000 for a welcome; no control characters
```

- `GET  /api/v1/control-plane/tenants/{t}/sales-channels/{c}/setup/telegram` — `channel.read`. Returns the fields, the
  derived `miniAppUrl` (or `miniAppUnavailable` naming what is missing), the derived `publicOfferUrl`, and `miniAppShortName`.
- `PUT  …/setup/telegram?expectedVersion=` — `channel.manage`. Full replace, audited with `ChangeDocuments.diff`
  (`channel.telegram.updated`). Only a `TELEGRAM` channel accepts it. The channel's `version` is bumped like every other
  facet of Channel setup.
- `TelegramBotApiClient.setChatMenuButton` and `getChatMenuButton` (`web_app` type, `default` type), exercised against
  `FakeTelegramBotApi`.
- The bot's welcome text: a bare `/start`, or a `/start <token>` for an active link, is answered with the channel's
  welcome text in the customer's language (Telegram's `language_code`, mapped through `PlatformLocales`), falling back to the
  brand's default language and then to `TelegramBotMessages`. An entitled tenant with an active conversation flow keeps
  that flow; the welcome text is what a bare `/start` gets when no flow answers.

### Acquisition links (6.6a) — marketing and integration

- `marketing.api.AttributionClickPort#recordClick(tenantId, brandId | null, token, visitorHandle)` — implemented by
  `AttributionLinkClickService`; `visitorHandle` is an `AnonymousVisitorHandle` (a keyed hash minted at process start,
  never stored). The bot passes a handle of the Telegram user id; the controller one of the address.
- `marketing.api.TelegramLinkTargets#forBrand(tenantId, brandId)` — implemented in `integration`; returns the bot
  username and the Mini App short name, or the reason there is none.
- `tenancy.api.StorefrontHostnames#forBrand(tenantId, brandId)` — the verified hostname of the active WEB channel that
  sells at the brand's branches, exactly one, or the reason there is none (`NONE_CLAIMED`, `NOT_VERIFIED`, `AMBIGUOUS`).
- `AttributionLinkResponse` gains `url` and `unavailable`. `unavailable` is one of `WEB_HOSTNAME_NOT_CLAIMED`,
  `WEB_HOSTNAME_NOT_VERIFIED`, `WEB_HOSTNAME_AMBIGUOUS`, `TELEGRAM_BOT_NOT_CONNECTED`,
  `TELEGRAM_MINI_APP_NOT_SET_UP`, `MOBILE_APP_ON_HOLD`.
- The setup flow, under `/api/v1/operations/tenants/{t}/brands/{b}/telegram/mini-app-setup`:
  `GET` the derived steps (`CONNECT_BOT`, `REGISTER_WEBHOOK`, `TELEGRAM_CHANNEL`, `STOREFRONT_ADDRESS`, `BOTFATHER_APP`,
  `MENU_BUTTON`, `TEST_LINK`); `POST …/menu-button` sets the bot's default menu button through `setChatMenuButton`, reads it
  back with `getChatMenuButton`, and audits `integration.telegram_menu_button_applied` only when the read-back agrees.
  `integration.installation.manage`. The short name is recorded through the per-channel settings endpoint above, which is the one
  Channel setup uses. Telegram keeps one default button per bot, so its label is the channel's label in the brand's default
  language; a customer who starts the bot also gets a menu button for that chat in their own language.

### Funnel (7.6c) — reporting

- `reporting.page_load_counts_day` (`V0675`): `tenant_id, brand_id, business_date, channel_code, page_loads bigint`,
  counts only, the structural no-subject test extended to it.
- `POST /api/v1/storefront/tenants/{t}/brands/{b}/page-loads`: public, answers 202 whether or not it counted (429 only for a
  visitor past thirty a minute), rate limited per visitor handle before anything else; counts only when
  `reporting.page_load_count_enabled` is true for the platform.
- The funnel read's `VISITS` stage stays `measured=false, reason=NOT_COLLECTED` while the flag is off. With it on the stage is
  `measured=true` with a `count` and `basis=PAGE_LOADS`, and `unrecordedDays` counts the business dates in the range before the
  counter wrote its first row (a range reaching back past the day it was switched on says its total is partial rather than
  summing the gap as zero). The console draws the figure beside the funnel, labelled «page loads, not people», and not as one of
  its bars or as a ratio against any stage. `GET` on the same storefront path says whether the counter is on, and a storefront
  sends the ping only when it is.
- Prometheus (`adr-0167-alerts.yml`, morning tier, with `promtool` tests and `customer-funnel-gap.md`):
  `HorecaosFunnelDayWithoutRow` (a closed day with orders and no funnel row) and `HorecaosFunnelUnrecordedDaysGrowing` (days
  past the first week still carrying a «not recorded» count, growing).
- `ConfigurationKey` gains `provisional(governsPersonalData, awaitingConfirmationFrom)`. `ProvisionalRetentionStartupCheck`
  reports every provisional key on a non-local profile and refuses to start for one that governs personal data.
  `reporting.funnel_retention_days` is flagged and governs none.

### Storefront merchandising (6.8) — content, tenancy

```text
content.brand_switcher_entries                     -- V0676
  tenant_id, id, brand_id (shows the switcher), target_brand_id, channel_id (whose VERIFIED hostname it links to),
  sort_key, state ('ACTIVE' | 'HIDDEN'), created_by, created_at, updated_at, version
  FK (tenant_id, brand_id), FK (tenant_id, target_brand_id) -> tenant.brands; FK (tenant_id, channel_id) -> tenant.sales_channels
  UNIQUE (tenant_id, brand_id, target_brand_id, channel_id); a brand does not list itself

tenant.locations + storefront_sort_order integer null   -- V0676
```

- The home document gains `brandSwitcher` (entries: target brand id and name, its logo as a servable image when it has one,
  and `https://<verified hostname>/`; omitted when empty, served on a WEB channel only, and re-checked on every read) and
  `layout` (`MARKETPLACE`, from policy `storefront.layout`; omitted for `STANDARD`, so a brand that uses neither is served the
  document it always was).
- Switcher editor, `/api/v1/tenants/{t}/brands/{b}/content/brand-switcher`: `GET /entries` and `GET /candidates` (the tenant's
  other active brands that sell through an active website channel at one of their own branches, with each hostname or none)
  under `content.read`; `POST /entries`, `PUT /entries/{id}` (show or hide, `If-Match`), `DELETE /entries/{id}` and `PUT /order`
  under `content.manage`; every change audited as `content.switcher_entry.*`. An entry names a brand and a WEB channel of the
  same tenant, never itself, never the same pair twice.
- Layout: `PUT /api/v1/tenants/{t}/brands/{b}/content/policies/layout` (`content.manage`, `If-Match`), `STANDARD` or
  `MARKETPLACE`, anything else refused; the policy read returns `layout` and `layoutVersion`.
- Branch order: `GET` and `PUT /api/v1/operations/tenants/{t}/brands/{b}/storefront-branch-order` (`location.read` and
  `location.write` at the brand). `PUT` names the branches first to last and replaces the order in one statement: the named
  branches take 10, 20, 30... and every other branch of the brand returns to unset. Audited as
  `brand.storefront_branch_order.changed`.
- The public pickup search `POST /api/v1/storefront/pickup-locations` takes `point` as optional. With a point it orders by
  distance, then `storefront_sort_order` (unset last), then id; with none it orders by `storefront_sort_order` (unset last),
  then name, then id, reports `distanceKnown=false` and a distance of 0 that is not a claim. A branch with no coordinate is
  offered only when there is no point.
- The console (Marketing → Storefront) replaces the «not built» tab with «Brand switcher and layout» and adds «Branch order»,
  both lazy. Both storefronts read the two new document fields, check each address again, and draw nothing when neither is
  present; with `MARKETPLACE` the category rail is the first five categories and an «All categories» button that opens a modal
  dialog listing all of them.

## Rollout and rollback

Ship in this order: the decision record; the per-channel Telegram settings and the Bot API calls (inert until a
channel is configured); the server-computed links, the bot landing and the Mini App landing (the click series starts
filling); the console setup flow; the funnel alert and the provisional flag; the page-load counter (dark); the switcher,
branch order and layout (render nothing until used). Rollback of any of them is removing the reading call: the tables
are additive and hold no person. Turning the page-load flag off stops the counter; the rows stay and are swept by the
funnel retention job. A layout set to `STANDARD` is today's storefront.

## Implementation checklist

- [x] Owner accepts the record (batch 21).
- [x] `V0674`, `V0675`, `V0676` with grants for the application role. `reporting.page_load_counts_day` needs no grant of
      its own for the reporting read role: `V0031` gives it `SELECT` on every present and future table of the schema.
- [x] Telegram per-channel settings, `setChatMenuButton`/`getChatMenuButton`, welcome texts, the public-offer link
      (`TelegramChannelSetupEndpointTests`, `TelegramBotLandingTests`, `telegram-card.spec.ts`).
- [x] Server-computed link URLs; `MOBILE_APP` no longer offered; bot landing; Mini App landing in both storefronts
      (`MiniAppAndLinksEndpointTests`, `TelegramBotLandingTests`, `attribution-landing.spec.ts` in each storefront).
- [x] The guided Mini-App/BotFather setup flow and the console wizard (`mini-app-setup-card.spec.ts`).
- [x] The funnel alert rule (`promtool test rules`), the provisional flag and its startup check, the page-load counter (dark),
      the housekeeping ADR 0167's checklist still owed (`statistics.md` Band D, ADR 0043's status line, the notice as a default
      clause).
- [x] Brand switcher entries, branch order, marketplace layout; the console editor (`BrandSwitcherEndpointTests`,
      `StorefrontBranchOrderEndpointTests`, `storefront-switcher.spec.ts`, `storefront-branch-order.spec.ts`).
- [x] `docs/runbooks/external-connections/w8-acquisition-merchandising.md`; `docs/runbooks/connect-telegram-bot.md`
      points at the guided flow.
- [x] The OpenAPI baselines (five documents) and the one allowance the gate needed: the pickup search's `point`
      may become optional (`AcceptedContractWidenings`, this record's D17). The new request and view records and handlers take
      names no other record or handler has, so no released schema or `operationId` is renamed.
- [x] Dated status additions on ADR 0167, 0157, 0075, 0044; `tools/file-adrs`.
- [ ] Outside the platform, so not ticked by a build: the BotFather round trip, counsel's answers (per-person attribution, the
      page-load count, the 400-day retention) and a signed tenant that runs two brands. They are listed, with where each value goes
      and how to prove it, in `docs/runbooks/external-connections/w8-acquisition-merchandising.md`.

## Exit criteria

A marketer opens Referrals, mints a Telegram Mini App link, and copies a URL that opens the storefront inside the bot; the
same link minted before the tenant had a short name reads «not set up yet» and names the step. A customer who taps a bot
link is greeted, and the link's click count has gone up by one with no row anywhere that could be joined to them. A
tenant's owner opens Channel setup on the Telegram channel and sets the menu button label and welcome texts without typing
a URL. A brand with a verified hostname and two branches shows them in the owner's chosen order. The funnel alert fires in
`promtool test rules` for a closed day with orders and no row. No table added by this record has a column that could
identify a person, and a test says so.

## References

- ADR 0044 (attribution links), ADR 0058 and ADR 0060 (the Telegram bot), ADR 0065 (the write-only secret door), ADR 0068
  (terms), ADR 0075 (the customer orders from the chat), ADR 0157 (content and merchandising slots, Release 4), ADR 0167
  (the funnel and its lawful basis), ADR 0029 (provisional retention)
- `platform/docs/operations-gap-map.md` rows `6.6a`, `6.8`, `7.6c`, `10.5`
- `platform/docs/operations-spec/settings.md` §10.5 TELEGRAM, `statistics.md` §2.6 Band D,
  `brands-and-locations.md` «Порядок в списке»
- `platform/docs/runbooks/connect-telegram-bot.md`, `platform/docs/runbooks/external-connections/w8-acquisition-merchandising.md`
