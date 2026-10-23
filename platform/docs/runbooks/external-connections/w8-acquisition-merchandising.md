# Acquisition links, the tenant's Mini App and storefront merchandising: the facts that live outside the platform

**Last executed:** never — this is a draft. Everything below was built and proven against a fake: `FakeTelegramBotApi` in
place of Telegram's Bot API, switches left dark in place of counsel's answers, and a fixture tenant with two brands in place of
a signed one. ADR 0183 records why each is a fake and who closes it. Per ADR 0023 a runbook that has never been executed is a
draft; run each section once against the real thing and put the date here.

**The way back, before the first step.** Every switch below is a value or a setting and none is a migration. Turn the Mini App
off in the Telegram card and nothing at Telegram moves by itself, because the bot's menu button is held there. While Telegram
still shows a button that opens the storefront, the menu button step of **Marketing → Referrals → Telegram Mini App setup**
reads «Telegram still shows a button that opens the storefront…» and its button says **Take the menu button off**; pressing it
sets the bot's default back (`setChatMenuButton` to Telegram's default, read back with `getChatMenuButton`, and the audit fact
`integration.telegram_menu_button_reset` written only when the read-back agrees). The same button appears when the storefront's
hostname stops being verified. Two things it cannot reach: a chat's own button, set at that customer's earlier `/start`, which
Telegram offers no call to list or clear and which is put back to the default the next time that customer starts the bot; and
the main Mini App address held in @BotFather, which the default button shows for a bot that has one: correct or remove it from
`/myapps` in @BotFather. Set the page-load flag back to
`false` and nothing is counted (the rows already written are swept by the funnel retention job); set the layout back to
`STANDARD` and the storefront is what it was; remove the switcher entries and the strip disappears. Nothing here deletes a
customer, an order or a row of anyone's money.

| What lives outside the platform | Built against | Who closes it |
|---|---|---|
| A real bot's `/newapp` and the menu button on Telegram's production Bot API | `FakeTelegramBotApi` | The owner of the tenant's @BotFather, once per tenant |
| Counsel's answers: per-person attribution, the page-load count, the 400-day funnel retention | Nothing recorded (attribution), a platform flag left `false` (page loads), a code default flagged provisional (retention) | Finance and legal counsel; the platform owner writes the value |
| A signed tenant that runs two brands on one storefront | A fixture tenant with two brands | The platform owner and product, when a tenant asks |

## 1. A real bot: `/newapp` and the menu button

**What exists.** The Telegram card of Channel setup (**Settings → Sales channels → the TELEGRAM channel → Channel setup**) holds
the channel's own settings: the brand whose storefront the Mini App opens, whether the menu button opens it, the BotFather short
name, the public-offer link, and a menu button label and a welcome text per language. **No address is typed anywhere.** The Mini
App address is the brand's *verified* storefront hostname, read on every request (`StorefrontHostnames`), and a brand with no
verified hostname, or two of them, gets a reason instead of a link. **Marketing → Referrals** shows the guided setup beside the
links: seven steps, each derived from a fact on each read and none remembered, so the page can be closed and reopened at any
point. Six of them can be proved against Telegram. One cannot: `/newapp` has no API, so the flow shows the exact text to send,
records the short name BotFather answers with, and reads **recorded**, never **verified**.

**Where the value goes.**

1. In @BotFather, as the bot's owner: `/newapp`, choose the bot, give the title and description, and when it asks for the Web
   App URL send exactly the address the setup page shows (it is `https://<the brand's verified hostname>/`). BotFather answers
   with a link ending in the short name.
2. In the console, **Marketing → Referrals → Telegram Mini App setup**, enter the short name (3 to 64 letters, digits and
   underscores, exactly as BotFather gave it) and press **Record short name**; recording it switches the Mini App on for the
   channel. The same fields, with the brand, the labels and the welcome texts, are on the TELEGRAM channel's Telegram card under
   **Settings → Sales channels → Channel setup**. Neither save calls Telegram.
3. Press **Set the menu button** in the same setup. This is the one call to Telegram: `setChatMenuButton`, then
   `getChatMenuButton` to read back what Telegram now holds. The audit fact `integration.telegram_menu_button_applied` is written
   only when the read-back agrees.

**How to prove the connection.**

- The setup page lists every step as done, and the menu button step says what Telegram reported, not what the platform sent.
- From a real Telegram account, open the bot. The chat's menu button carries the label for that account's language (else the
  brand's default language) and opens the storefront inside Telegram on the brand's hostname.
- Mint a Telegram Mini App link under **Referrals** and open it from a second account. The storefront opens inside the bot, and
  the link's click count goes up by one in the console within the minute.
- Mint a Telegram bot link and open it. The bot greets the account in its language with the welcome text, and the same count
  goes up. Nothing about the account is stored with the count.

**If it fails.**

- «The menu button step says UNKNOWN»: Telegram could not be read just now (the bot token is wrong, rotated, or Telegram is
  unreachable). Run **Connection check** on the installation under **Settings → Integrations**, then apply again.
- «The read-back differs»: Telegram holds a different button than the platform sent. Apply again; if it persists, the bot's
  menu button is being set by another tool (SendPulse, a script). The platform sets the *default* menu button of the bot and,
  while the Mini App is live, a per-chat one on each greeting. When the Mini App is off or its address is not verified, a greeting
  puts a chat that still holds a Mini App button back to the default; a chat whose button is not a Mini App button is left alone.
- «The Mini App address is not available»: the brand has no verified storefront hostname, or two. The setup page names which.
  Fix it under Channel setup for the WEB channel; this flow never edits it.
- BotFather shows the Mini App on a different address than the setup page: BotFather's copy is the one Telegram opens. Correct it
  from `/myapps` in @BotFather; the platform has no way to read or change it.

## 2. Counsel's answers

### 2a. Per-person attribution (ADR 0167 Decision 4, tier 2): declined until counsel answers

**What exists.** Nothing, by decision (ADR 0183 D16). There is no `acquisition_link_id` on an account or an order, no
attribution policy, no erasure null-out, and no storefront code that carries a link's token to sign-up or checkout. What exists is
the anonymous series: a click count per link per day (from the web storefront, the bot and the Mini App), registrations by origin,
and orders by channel, with the sentence on the screen that says the chart does not attribute a customer to a link. The audience
predicate `ACQUISITION_CHANNEL` selects nobody.

**Where the answer goes.** A new decision record that supersedes ADR 0183 D16, written when counsel says what the notice and the
lawful basis for recording which link brought a person must be, and whether the 400 days apply to it. That record, not a
setting, is what lets anyone build the tier. There is no flag to flip, on purpose.

**How to prove the line is held.** `BehaviouralFunnelStructureTests` fails the build if any funnel table gains a column that
could identify a person, and `TelegramBotLandingTests` asserts that a bot start through a link adds one to that link's count and
nothing else.

### 2b. The page-load count (ADR 0167 Decision 3, ADR 0183 D18): dark until counsel answers

**What exists.** A cookie-less, identifier-less count of how many times a storefront tab opened, labelled «page loads, not
people», shown beside the funnel and never as one of its bars or as a ratio. Each storefront sends one anonymous ping per tab
session (`POST /api/v1/storefront/tenants/{tenantId}/brands/{brandId}/page-loads`), rate-limited by a process-keyed handle that is
never stored. The platform adds one to a per-day, per-brand, per-channel counter. While the flag is off the endpoint counts
nothing, `GET` on the same path says so, and no storefront sends the ping. The platform's default customer terms carry the sentence
that says the platform counts and never by person (terms version 2).

**Where the value goes.** `reporting.page_load_count_enabled`, `PLATFORM` scope only, boolean, default `false`. As a platform
administrator:

```bash
curl -fsS -X POST "$HORECAOS_API_ORIGIN/api/v1/control-plane/configuration/keys/reporting.page_load_count_enabled/values" \
  -H "Authorization: Bearer $PLATFORM_ADMIN_TOKEN" -H "Idempotency-Key: $(uuidgen)" -H 'Content-Type: application/json' \
  -d '{"scopeType":"PLATFORM","explicitNull":false,"booleanValue":true,"reason":"Counsel confirmed the page-load count needs no consent (ticket ...)"}'
```

**How to prove the connection.** Open a storefront in a private window. The console's **Reports → Customer analytics** shows a
«page loads» figure for today after the next refresh, labelled as above, and the funnel's own bars are unchanged. Open the same
storefront twice in the same tab: the figure goes up once.

**Way back.** The same call with `"booleanValue":false` and the `expectedVersion` that `GET /api/v1/control-plane/configuration/keys/reporting.page_load_count_enabled/resolution?scopeType=PLATFORM` reports.

### 2c. The 400-day funnel retention (ADR 0167 open input 4): provisional until confirmed

**What exists.** `reporting.funnel_retention_days` resolves to its code default and is flagged provisional with who must confirm
it. `ProvisionalRetentionStartupCheck` reports every provisional key still on its default at `WARN` on each start of a non-local
profile, and as the gauge `horecaos.configuration.provisional_retention_keys`. It refuses to start only for a key marked as
governing personal data; this one is not (the funnel keeps counts and no person), so it only warns.

**Where the value goes.** Writing the value at `PLATFORM` scope is the confirmation — there is no second switch. When counsel
confirms 400 days (or names another number), write it:

```bash
curl -fsS -X POST "$HORECAOS_API_ORIGIN/api/v1/control-plane/configuration/keys/reporting.funnel_retention_days/values" \
  -H "Authorization: Bearer $PLATFORM_ADMIN_TOKEN" -H "Idempotency-Key: $(uuidgen)" -H 'Content-Type: application/json' \
  -d '{"scopeType":"PLATFORM","explicitNull":false,"integerValue":400,"reason":"Confirmed by counsel (ticket ...)"}'
```

**How to prove the connection.** Restart or redeploy; the `WARN` line for `reporting.funnel_retention_days` is gone from the start
log and the gauge reads `0`. The write itself is in the audit trail with the reason.

## 3. A signed tenant that runs two brands on one storefront

**What exists, dark.** The brand switcher (`content.brand_switcher_entries`), the order of a brand's branches
(`tenant.locations.storefront_sort_order`) and the marketplace layout (the brand-scoped policy `storefront.layout`, `STANDARD` by
default). A brand that uses none of them is served the home document it always was, byte for byte (the endpoint tests assert the
two keys are absent). ADR 0183 D17 built them without waiting for ADR 0157's Release 4 trigger.

**Where the value goes.** **Marketing → Storefront**, as someone holding `content.manage` for the brand:

1. **Brand switcher and layout.** Under «Other brands on this storefront» choose the sibling brand and its website channel and
   add it; order it with Move up / Move down. The page says for each entry whether a visitor would see it today and why not (hidden,
   the website has no verified address, the brand is not active). Under «Category layout» choose Marketplace only when the tenant
   has asked for it; the page says what it changes.
2. **Branch order.** Place the brand's branches in the order a customer without a shared location should see them. Needs
   `location.write`.

**How to prove the connection.**

```bash
curl -fsS "$HORECAOS_API_ORIGIN/api/v1/storefront/tenants/$TENANT/brands/$BRAND/content/home?channel=STOREFRONT" | jq '{brandSwitcher, layout}'
```

returns the entries in order, each with the target's name and an `https:` address on its verified hostname, and
`"layout": "MARKETPLACE"` when chosen. Open the brand's storefront in a browser: the strip of brands sits under the top bar, and
with Marketplace the category rail shows five categories and an «All categories» button that opens the full list.

**Two things to know before saying «done» to the tenant.**

- An entry names a *channel*, not an address. Releasing or un-verifying that website's hostname takes the entry off the storefront
  with no edit; verifying it puts it back.
- The customer apps do not call the public pickup search yet (they use the location the deployment is configured with), so the
  branch order is served by `POST /api/v1/storefront/pickup-locations` and proved there, and a customer sees it only once a
  branch chooser is built on that endpoint. A brand's own storefront must send its `brandId` in that request to get the brand's own
  order; without it the search spans every brand and, with no point, lists each brand's first branch, then each brand's second, and
  so on. Saving the order presents the version it was read at (`If-Match`); a colleague's change in between answers 409 and the
  screen shows the current order. The marketplace layout needs a tenant to say what its category navigation should do;
  what is built is the smallest honest version (ADR 0183, Consequences).
