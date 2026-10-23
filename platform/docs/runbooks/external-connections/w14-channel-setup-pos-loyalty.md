# External connections: channel setup, kiosk idle media and the till's loyalty balance

- Last executed: never (every section below is a draft until the person it names has done it once; ADR 0023)
- Reader: the platform owner, who holds the vendor contacts, the pilot branch and the BotFather identity
- Records: [ADR 0187](../../adr/built/0187-kiosk-idle-media-and-the-channel-forms-the-console-does-not-owe.md) (kiosk idle media, the channel forms the console does not owe),
  [ADR 0162](../../adr/partial/0162-kiosk-device-pairing.md) (kiosk pairing), [ADR 0171](../../adr/partial/0171-who-owns-a-loyalty-balance-when-the-till-also-keeps-one.md)
  (who owns a loyalty balance), [ADR 0058](../../adr/partial/0058-telegram-notification-channels.md) (Telegram chats)

Wave `w14-channel-setup-pos-loyalty` built everything it could without five facts that only the owner can supply. Each
section says what exists, where the value goes when it arrives, and how to prove the connection. None of them needs a
release: they are devices, accounts, a row in a table and a configuration key.

**How to get back.** Nothing here is a point of no return. A kiosk is revoked from the console and the flag switched off;
a staged till is moved back to *Report only* in one step; a consent key is flipped back; a ceiling row is replaced by a
new one with its evidence.

## 1. A kiosk unit, under operating-system lockdown, at a pilot branch

**What exists.** Pairing, the registry, revocation, idle pictures and their playback, all behind the dark flag
`feature.kiosk_devices`. Proved by `KioskPairingHttpTests` (18), `KioskIdleMediaHttpTests` (17), `MediaLifecycleTests`
(the 9:16 rule at verification) and the specs of the device shell and the KIOSK card. No unit has been paired, and the lockdown
the record relies on has never been exercised on one.

**What the owner supplies.** A tablet or kiosk cabinet with a browser that can be locked to one page (the operating
system's kiosk mode, not an application), a pilot branch whose manager has `kiosk.device.manage`, and a KIOSK sales
channel selling at that branch.

**Where it goes.**

1. Switch `feature.kiosk_devices` on for the pilot tenant (Settings, the find-a-setting registry).
2. Install the unit as [terminals-and-kiosks.md](../terminals-and-kiosks.md) says, open `/device`, choose *Kiosk*.
3. The manager scans the QR with her phone, ticks that the code in front of her is the one shown, chooses the channel, pairs.
4. Settings, Channel setup, the KIOSK channel, *Idle screen pictures*: upload two pictures (the page crops them to 9:16 and
   refuses a file over 1 MB), put them in order.

**Prove it.** Within a minute of step 4 the kiosk's screen plays the two pictures in that order, eight seconds each, with the
channel and branch names in a corner. Remove one; within a minute the loop is one picture. Remove both; the status screen
returns. Revoke the kiosk; on its next request it returns to the pairing screen. Then write the date, the unit and the OS
lockdown used into the *Last executed* line of `terminals-and-kiosks.md`.

## 2. A real BotFather round trip

**What exists.** The whole Telegram path against a fake Bot API (`TelegramWebhookRegistrationEndpointTests`, nine cases),
and, from this wave, the two review event classes `REVIEW_POSITIVE` (4 or 5 stars) and `REVIEW_NEGATIVE` (1 to 3) that a
chat can subscribe to in Settings, Notifications, routing. Telegram has no sandbox, so a real `setWebhook` has never been
sent.

**What the owner supplies.** The BotFather identity of the brand whose bot is being connected.

**Where it goes.** The token is pasted into the installation's write-only secret door
([connect-telegram-bot.md](../connect-telegram-bot.md), steps 1 to 7); this wave changes nothing about it.

**Prove it.** Follow `connect-telegram-bot.md` to its step 7. Then bind a staff chat, subscribe it to `REVIEW_NEGATIVE`
and `REVIEW_POSITIVE`, submit a two-star and a five-star test review from the storefront on a completed order, and check
that the chat is told of each once, by the review's id and its stars and nothing about the customer. Update *Last executed* in
`connect-telegram-bot.md`.

## 3. A real till for the loyalty balance: iiko and R-Keeper

**What exists.** The whole path against a fake till (`FakePosAdapter`, `fake-pos` SUPPORTED in V0555): matching, pull,
push, caps, the reconciliation report, resolutions, and, from this wave, staging the rollout from the console, a push sweep
every thirty seconds (so a platform movement reaches the till inside a minute, not inside the five-minute pull), and a chat
alert for a difference that needs a person. Proved by `LoyaltyPosSyncHttpTests` (34) and `LoyaltyPosSyncStagingTests`.

**What the owner supplies, per vendor.** The vendor's wallet API documentation, a sandbox, and a named contact (ADR 0171,
open input 1). The record names six things to learn: read a balance, list movements since a cursor, credit and debit with an
idempotency key, set a balance, place a hold, and whether the programme is one point to one som.

**Where it goes.** No adapter code is written first. In order:

1. A provider note under `docs/providers/` (`iiko-loyalty.md`, `r-keeper-loyalty.md`) answering the six things with the
   vendor's own words and endpoints, as `clopos-api.md` does.
2. A row in `integration.pos_provider_capabilities` for `LOYALTY_BALANCE_SYNC` with its evidence, as V0555 did for the fake
   (`SUPPORTED`, `PARTIAL` or `UNSUPPORTED`). Until it exists the database refuses to enable the capability.
3. The adapter: the four default methods of `PosAdapter` (`listTillCustomers`, `readBalance`, `readMovements`,
   `writeBalance`), and contract tests written like the fake's.
4. The tenant's binding with the capability enabled, then the rollout from the console (Marketing, Loyalty, *Синхронизация
   баланса с POS*): *Report only* for as long as finance wants to read the report, then *Imports*, then *Imports and sends*.

**Prove it.** Against the vendor's sandbox: a customer earns at the till and the platform shows it as a `POS_SYNC_EARN`
adjustment within the pull interval; the same customer redeems on an order and the till shows the lower balance within 60
seconds (measure it with a clock, once); a deliberately corrupted till balance shows in the next reconciliation as
`TILL_HIGHER` or `TILL_LOWER` and changes nothing until a person resolves it.

## 4. Whether Clopos keeps a customer balance and exposes it

**What exists.** A Clopos binding reads *Not assessed* in the console, plainly, and cannot be given the capability
(`integration.pos_capability_is_supported()`); the notes in `docs/providers/clopos-api.md` record no balance field.

**What the owner supplies.** An answer from Clopos, asked through the owner: does the programme hold a customer balance, and
is it readable and writable through the Open API?

**Where it goes.** The answer, with the vendor's wording, into `docs/providers/clopos-api.md`; if the answer is yes, one
ceiling row for `clopos` with its evidence, and then section 3's steps 3 and 4 apply to Clopos.

**Prove it.** The Clopos binding's card on the loyalty tab changes from *Not assessed* to the row's state.

## 5. Counsel's answer on a consent purpose for syncing a balance

**What exists.** The accepted default is *no consent purpose needed*, because no customer data is sent to the till (the
platform writes a number against the till's own customer id). The gate that would apply if counsel says otherwise is
built and dark: `loyalty.pos_sync.require_consent` (default off, settable at platform, tenant or brand). Switched on, a
till customer whose platform account has not granted the purpose `loyalty.pos_sync` is not paired and appears in the
reconciliation report as `NOT_CONSENTED`; absence counts as withheld. A customer who was already paired is held to it too:
the next pass of any kind suspends their pairing (nothing is pulled, pushed or compared for them, and `NOT_CONSENTED` is
opened), whether the key was off when they were paired or they withdraw later, and the pairing comes back by itself when they
consent or the key is turned off again. Accepting the till's or the platform's number on an open difference is refused for a
customer without consent as well. Proved by `LoyaltyPosSyncStagingTests`.

**What the owner supplies.** Counsel's yes or no.

**Where it goes.** If no: nothing, and the `NOT_CONSENTED` value stays declared and never produced. If yes: counsel's wording
becomes a consent type with the code `loyalty.pos_sync` (a default consent type in `customers`, with a migration that adds the
row to every existing tenant, as V0543 did for `review.public_display`), the storefront asks for it, and then the key is set
for the tenants that need it.

**Prove it.** With the key on, run *Reconcile now*: a customer who has not consented is listed `NOT_CONSENTED` and not paired
(or, if they were paired before the key was turned on, suspended); after they consent the next pass pairs them or brings the
pairing back, and the difference closes.
