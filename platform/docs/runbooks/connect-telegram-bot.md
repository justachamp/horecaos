# Provisioning a new Telegram bot through the operations console

**Last executed:** never — this is a draft. Every screen and endpoint named
below is read from the code that ships it
(`OperationsProviderInstallationController`, `TelegramWebhookRegistrationService`,
`installation-detail-panel.ts`) and proven end to end against a fake Bot API by
`TelegramWebhookRegistrationEndpointTests`' nine cases — but Telegram has no
sandbox distinct from its production Bot API (V0374's own migration comment
states this), so the one thing no test can prove ahead of time is a real
BotFather bot's `setWebhook` round trip. Run this once against a real bot,
confirm step 7's checks pass, and update this line with the date — per ADR
0023, a runbook that has never been executed is a draft.

**How to get back.** Nothing below is a point of no return. Connected the
wrong bot token: **Rotate credential** with the right one (there is no delete
action on an installation yet — see "Rolling back / disconnecting" below, the
same limitation `connect-click-payme-sandbox.md`'s own section names). Bound
the wrong brand or location: **Suspend** the binding (step 5's Bindings
section) — a suspended binding never resolves for new traffic. Registered the
webhook against the wrong deployment: **Re-register webhook** again — every
call is a rotation (see step 6), and Telegram always obeys whichever
`setWebhook` call it received most recently, exactly as
`sendpulse-cutover.md`'s own step 6 relies on for its cutover.

## Before you start

- **A BotFather identity for this bot.** Whoever holds `@BotFather` for the
  brand this bot represents — the owner, usually (`sendpulse-cutover.md`'s own
  "What only the owner can do" section names the same access for an existing
  bot).
- **A signed-in operations session for the tenant**, held by someone with
  `INTEGRATION_INSTALLATION_MANAGE` (Connect, Bind, Reconcile, Register
  webhook) and `INTEGRATION_BINDING_ACTIVATE` (Activate). Unlike
  `connect-click-payme-sandbox.md`'s own merchant-binding step, no persona
  juggling is needed here: both capabilities are held by the `tenant-owner`
  **and** `tenant-admin` bundles alike
  (`OperationsProviderInstallationController`'s own doc comment).
- **The `telegram-prod` provider-environment row.** Unlike Click's and Payme's
  sandbox rows, this one does not need seeding by hand: `V0374` ships it as a
  migration, already applied on `main`. If **Connect a provider** answers `400
  Unknown provider environment for this category: telegram-prod`, that
  migration was never applied to this database — see Troubleshooting.
- **`HORECAOS_API_ORIGIN` set to this deployment's real, `https`, publicly
  reachable origin on the `platform-app` container.**
  `TelegramWebhookRegistrationService` builds the URL it hands Telegram from
  exactly this value, and refuses to register anything non-`https` outside a
  local/test profile. Production's own `compose.production.yaml` now requires
  the variable just to start the container; if this host boots the image
  another way, confirm it is set — see Troubleshooting.

## 1. Get a BotFather token (owner)

Talk to `@BotFather`: `/newbot` → a display name → a username ending in
`bot`. BotFather returns the token once. This is the only moment it exists
outside the platform — paste it directly into the Connect form in the next
step and keep it nowhere else; from the instant it is submitted there is no
endpoint anywhere that reads it back (ADR 0065).

## 2. Connect the provider

**Settings → Integrations → Connect a provider.**

- Provider: `TELEGRAM_BOT_API`. Display name: anything recognisable
  ("Front desk bot"). Environment: `telegram-prod` (seeded by V0374). Bot
  token (**secret**): the token from step 1.
- **Connect.**

Underneath: writes the token through the door
(`POST .../integrations/secrets`, `SecretIngressController`), then
`POST .../integrations` (`ProviderInstallationController#install`) creates the
row, status `DRAFT`. The drawer does not close — it continues straight into
its own second step.

## 3. Bind to the brand

The wizard's own second step, immediately after Connect — there is nowhere
else in the console to create this binding, so complete it now rather than
skipping.

- Brand: the brand this bot speaks for. Location: leave "— Entire brand —"
  unless this bot serves one location only.
- **Bind.**

Underneath: `POST .../integrations/{installationId}/bindings`
(`ProviderInstallationController#bind`) creates the binding, status
`SUSPENDED`, with no capabilities attached — Telegram declares none, so step
5's "every enabled binding capability must be verified" gate can never block
it. (Clicking **Skip for now** instead leaves the installation with no
binding at all; the only way back in is the raw `curl` shape
`connect-click-payme-sandbox.md`'s own step 3 shows, since the connect
wizard's bind step does not reappear once skipped.)

## 4. Open the installation → Reconcile

Close the drawer. Find the new row in "Provider installations" and open it —
the installation details drawer.

**Connection check → Check now.**

Underneath: `POST .../integrations/{installationId}/capability-reconciliation`
(`ProviderCapabilityReconciliationService`). **Not a live call to Telegram —**
it proves the bot token's secret reference resolves to a non-blank value and
that the wired adapter declares this provider type; that is genuinely all a
non-POS reconciliation can prove without an external effect (the class's own
doc comment states why). Status must read `SUCCEEDED` here — that is exactly
what step 5's activation gate checks next. If you want a real, live proof the
token itself works before activating anything, use **Rotate credential** with
the same token again (`connect-click-payme-sandbox.md`'s own tip): a `200`
carrying a `botUsername` is Telegram's own `getMe` confirming it live.

## 5. Activate the binding

**Bindings** section → the `SUSPENDED` row → **Activate** → a reason prompt.

Underneath: `POST .../integrations/{installationId}/bindings/{bindingId}/activate`
(`ProviderInstallationController#activateBinding`). Refuses with
`INVALID_REQUEST` unless step 4's connection check reads `SUCCEEDED` (ADR
0026: "a capability the provider has not demonstrated must not become the
sole business path for a live restaurant"). The first binding to activate
also flips the installation itself from `DRAFT` to `ACTIVE` — the status both
`TelegramWebhookRegistrationService` and the console's own webhook section
check next.

**Reload the Integrations page before reopening the installation.** Nothing
about the drawer's own Activate action refreshes the table it reads from —
only Reconcile does — so the drawer still shows the pre-activation `DRAFT`
status, and step 6's webhook section stays hidden, until the page is reloaded
and the row is reopened fresh.

## 6. Register the webhook

Reopen the installation. Once the row reads `ACTIVE`, a **Telegram webhook**
section appears (shown for no other status or provider type).

- **Register webhook** the first time this installation ever registers one;
  the same button reads **Re-register webhook** from then on.

Underneath: `POST .../integrations/{installationId}/webhook-registration`
(`TelegramWebhookRegistrationService#register`). Mints a fresh 64-character
secret token, writes it through the ADR 0065 door under a platform-minted
reference, calls Telegram's own `setWebhook` with
`url = HORECAOS_API_ORIGIN + /providers/telegram/{installationId}/webhook`
and that secret token, and — only once Telegram accepts — one short
transaction sets `webhook_secret_reference` and records
`webhookRegisteredAt`/`webhookUrl`. A Telegram refusal changes nothing in the
database; the freshly-written secret is simply an orphaned, harmless value
left in the secrets manager.

**Re-registering rotates.** There is no separate "first registration" branch
— every click mints a brand-new secret and calls `setWebhook` again, and the
previous secret stops authenticating the instant Telegram accepts the new one
(Telegram's own `setWebhook` is atomic per bot). This is the supported
recovery from a mismatched or leaked webhook secret (see Troubleshooting) —
re-register rather than trying to reason about which secret is current.

## 7. Verify

- **Console:** the Telegram webhook section reads "Registered at
  ⟨timestamp⟩" instead of "Not registered", and
  `GET .../integrations` (the installations list) now returns
  `webhookRegistered: true` for this row.
- **Storefront:** ask a real Telegram account to run this tenant's storefront
  "Continue with Telegram" sign-in (`StorefrontTelegramSignInController`) and
  confirm it completes — the surest proof updates are actually reaching the
  platform, not merely that `setWebhook` itself answered `200`.
- **Optional — `getWebhookInfo`, without ever putting the bot token in a shell
  command:**

  ```bash
  read -rsp 'bot token: ' BOT_TOKEN; echo
  curl -fsS "https://api.telegram.org/bot${BOT_TOKEN}/getWebhookInfo" | jq .
  ```

  `read -rsp` takes the token from the terminal directly into a variable — it
  is never typed as literal text on the command line, so unlike
  `curl .../bot<token>/...` written out directly, it never lands in shell
  history. Expect `url` =
  `<HORECAOS_API_ORIGIN>/providers/telegram/<installationId>/webhook` and
  `last_error_date` absent or older than this call. This check is entirely
  optional — the two checks above already prove the same thing without the
  token ever leaving BotFather and the platform's own secret store a second
  time, so skip it if you would rather not.

## Troubleshooting

- **"Something went wrong" in the console, and `SecretWriteFailedException`
  in `platform-app`'s own logs** (step 2 or step 6, wherever a secret is
  written). The OpenBao policy on this host has not picked up ADR 0065's
  write grant for the tenant-writable `provider_*` categories — see
  `production-setup.md`'s "Configure" section, "Reloading a policy": re-run
  its `for policy …` loop with the current `deploy/infra/openbao/policies/`
  copy.
- **`400 Unknown provider environment for this category: telegram-prod`**
  (step 2). `V0374` was never applied to this database:
  `SELECT 1 FROM integration.provider_environments WHERE code = 'telegram-prod'`
  — no row means the migration has not run.
- **Telegram deliveries to `/providers/telegram/<id>/webhook` come back
  `403`.** Either this installation has never registered a webhook
  (`webhook_secret_reference IS NULL`), its status is no longer `ACTIVE`, or
  the `X-Telegram-Bot-Api-Secret-Token` header Telegram is presenting does not
  match the reference on file (`TelegramWebhookController` compares in
  constant time and never distinguishes "no such installation" from "wrong
  token", by design). **Re-register the webhook** (step 6): it mints a fresh
  secret and repoints Telegram at it in the same call.
- **`401` on the webhook path itself, instead of `403`.** This host is
  running an image built before this change — `POST /providers/telegram/*/webhook`
  was not yet permitted in `SecurityConfiguration`, so the request never
  reaches `TelegramWebhookController` at all. Redeploy the current image.
- **Register webhook answers `422` naming `https`.** `HORECAOS_API_ORIGIN`
  was not passed to `platform-app`, or is not an `https://` URL —
  `TelegramWebhookRegistrationService#requireHttpsOriginUnlessLocal` refuses
  outside a local/test profile rather than handing Telegram a URL it would
  itself reject less legibly. Confirm whatever bootstrap this host actually
  used set the variable; production's own `compose.production.yaml` requires
  it just to start the container.

## Rolling back / disconnecting

Same limitation `connect-click-payme-sandbox.md`'s own section names: there
is no archive/retire action on an installation yet (`status` supports
`RETIRED` in the database's own check constraint, but nothing transitions a
row to it). Suspending every binding under it (**Bindings** → **Suspend**)
stops it resolving for new order/notification traffic, but the installation
itself stays `ACTIVE` and **its webhook keeps accepting Telegram
deliveries** — suspending a binding only ever writes the binding row
(`ProviderInstallationController#suspendBinding`'s own SQL), never
`integration.installations.status`. To actually stop Telegram calling this
endpoint, revoke the bot's token from BotFather (`/mybots` → the bot → API
Token → Revoke current token) — the platform has no `deleteWebhook` action of
its own.

## What this runbook does not cover

- **Cutting an existing, already-live bot over from SendPulse.** That is
  `sendpulse-cutover.md`'s job — a different starting state (an `ACTIVE`
  installation with real subscribers already bound) and a different, mostly
  irreversible procedure.
- **Building the group/topic-based operations bot bindings** (ADR 0058 stage
  2's `/link` handshake) or the customer 1:1 Mini App/deep-link binding (ADR
  0063). Both ride the same installation this runbook provisions, but are a
  separate handshake initiated from inside Telegram or the storefront, not a
  console action.
