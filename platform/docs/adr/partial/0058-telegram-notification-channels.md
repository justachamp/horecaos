# ADR 0058: Telegram is a first-class notification channel for all three surfaces

- Decision status: Accepted
- Implementation status: Partial — Rollout stage 1 (operations groups) is built and
  callable end to end: `TelegramLinkCodeController` issues a short opaque code
  (`integration.telegram_pending_links`), `TelegramUpdateHandler`/`TelegramRightsVerifier`
  verify the bot's own rights via `getChatMember` before creating a binding,
  `TelegramChannelAdapter` sends through the existing ADR 0007 route with a per-chat
  durable lease (`integration.telegram_chat_locks`) plus an ordering precondition, the
  full Bot API error taxonomy (429/403/`migrate_to_chat_id`/topic-gone —
  `TelegramBotApiClient`), and an edit-vs-send lifecycle
  (`integration.telegram_tracked_messages`); a platform-wide circuit breaker alerts over
  the existing ADR 0023 metric mechanism, never Telegram itself.
  `OrderNotificationTrigger` fans order-confirmed/rejected out to every subscribed chat
  and `ApprovalDeadlineWarningSweeper` adds the flagship approval-deadline warning at
  the board's own two-minute severity threshold. `FakeTelegramBotApi` exercises the
  whole taxonomy plus `migrate_to_chat_id` against a real PostgreSQL
  (`TelegramOperationsNotificationIntegrationTest`). `TELEGRAM` spans the four V0026
  channel CHECK constraints and a binding-shaped `recipient_endpoints.provider_binding_id`
  variant (V0099–V0101). Not built: customer 1:1 linking, digests (blocked on ADR
  0043's day-close caller), control-plane audience, and every other domain's trigger
  listener (payments, fulfillment, fiscal, inventory, onboarding, bands) — named as
  separate items in this record's own checklist. Group language is one configured
  default, not real tenant configuration, pending a tenant-language column. Bot
  topology remains this record's own open input.
- Date proposed: 2026-08-30
- Date decided: 2026-08-30
- Deciders: platform owner (directed the channel and the per-surface scope), Claude
  (research and architecture; deep-reviewed 2026-08-30 against Telegram platform
  mechanics and the corpus, and amended accordingly)
- Depends on: 0004, 0006, 0007, 0020, 0025, 0026, 0027, 0028, 0029, 0032, 0033, 0043, 0045
- Supersedes / Superseded by: —
- Open inputs: bot topology per brand — and this is a THROUGHPUT decision as much as a
  branding one: all traffic on one bot shares one ~30 msg/s ceiling, so a shared
  platform bot lets one tenant's marketing blast throttle another tenant's order
  alerts, while bot-per-brand isolates them at the cost of token lifecycle (owner;
  the first pilot tenant answers it). Whether supervisor stat digests are
  entitlement-gated (ADR 0021) from day one (owner).

## Context

Uzbekistan runs on Telegram. The legacy system already operated a Telegram ops bot; the
storefront already ships Telegram Mini App support; `TELEGRAM` already exists as a
payment method code; Click itself reaches customers through Telegram surfaces. Meanwhile
the platform's own notification story is browser-bound: ADR 0045's SSE streams serve an
open operations tab, and ADR 0020's model has templates and delivery attempts with no
channel that reaches a person whose screen is off. A restaurant owner in this market
expects the order feed, the incident, and the day's numbers in a Telegram group — with
or without forum topics — not in an email they do not read.

The honest starting point, established by review against the code: ADR 0020's pipeline
is real but narrow — an in-process `@TransactionalEventListener` fed by ordering events
only, delivering over SMS only, with `notifications.commands` existing on paper (this
record's own earlier draft repeated that paper claim; corrected here). Telegram arrives
as a second delivery adapter on that in-process pipeline, and every event class listed
below that is not an order event needs its owning module to grow a trigger listener
first. Whether the pipeline ever gains its Kafka leg remains ADR 0020's decision to
advance or supersede — this record does not decide it.

## Decision

Telegram becomes a delivery channel of the notification pipeline — never a side
channel. Events flow domain → the notifications module (preferences, templates,
attempts per ADR 0020, fed by per-domain trigger listeners, today in-process) → the
Camel notification route (ADR 0007) → a Telegram Bot API adapter in `integration`.
Bot tokens are ADR 0026 provider installations carrying ADR 0028 secret references —
noting the Telegram shape is two-directional like `MARKETPLACE` (outbound Bot API
calls plus inbound webhook deliveries that must be authenticated), not the
outbound-only shape `NOTIFICATION` installations have needed so far. Chats and topics
are provider bindings created through a verification handshake, never hand-entered ids.

Three audience models, one pipeline:

1. **Storefront (customer)** — a 1:1 bot chat, linked to the customer account through
   the Mini App context or a `/start` deep-link code bound to their session (ADR 0051).
   **Telegram is additive for customers, never the assumed default**: the storefront
   also runs standalone and inside Click's super-app (ADR 0035's `MiniAppHost`
   abstraction exists precisely because a Telegram context cannot be assumed), and a
   Click-only customer has no chat to link — their notifications remain in-app/SMS per
   ADR 0020, and this record deliberately does not invent a Click channel.
   Transactional by default; marketing only under explicit ADR 0020 consent. Events:
   order state changes, payment status (including failure with a retry link), delivery
   and courier status with tracking deep-link, the fiscal receipt's OFD link on
   issuance (a legal artifact, not a courtesy), refund/remedy outcomes (ADR 0048),
   loyalty movements (ADR 0046) when that ships. Separately feasibility-checked: OTP
   delivery via **Telegram Gateway — a distinct, separately billed Telegram product
   with its own account and credentials, not the Bot API and not this bot's ADR 0026
   installation** — as a cheaper sibling to SMS.
2. **Operations (tenant staff)** — tenant-owned groups, optionally forum supergroups
   with topics per concern (orders / incidents / stats) or per location; flat groups
   are the degraded mode, never a blocker. Events: unhandled orders approaching their
   approval deadline (the same thresholds as the board's severity model), order
   incidents and BLOCKED processes needing a person, dispatch and courier events,
   payment failures and UNCERTAIN resolutions, blocked fiscal obligations (ADR 0038's
   worklist), stock-outs/86'd items, POS export and provider circuit-breaker alerts,
   dead-letter arrivals (ADR 0006) — and supervisor digests at 15 minutes, half-day,
   and day-close from the ADR 0043 metric layer. **Each of these event classes outside
   ordering requires a new trigger listener in its owning module — named build items,
   not assumed plumbing.**
3. **Control-plane (platform staff)** — platform-owned groups/topics. Events:
   onboarding run progress and the existing stuck-run alert, tenants awaiting
   activation approval (the maker-checker queue, ADR 0027), payment provider health
   across tenants, subscription and metering events (ADR 0021), identity drift
   findings (ADR 0009), control-band tier escalations (`ops/bands.yaml` — arithmetic
   detects, Telegram tells a human), and platform digests at half-day and day
   granularity.

Hard rules, inherited and specific:

- **Inbound updates are authenticated or dropped.** The Bot API's sole authenticity
  mechanism is the webhook `secret_token`: `setWebhook` registers it, and the adapter
  verifies the `X-Telegram-Bot-Api-Secret-Token` header against the installation's
  ADR 0028 secret reference before touching any update — the same
  validate-then-deduplicate-then-handle discipline every provider webhook already
  follows. Local development uses the long-polling consumer (no public URL exists in
  the dev loop); staging and production register webhooks with secret tokens.
- **Group chats never carry customer PII** (ADR 0029): no phone, no address, no note in
  an operations or control-plane message — a deep link into the authorized app carries
  the reader the rest of the way. A customer's own 1:1 chat may carry their own data.
- **At-least-once, deduplicated**: consumers are idempotent by `eventId` (ADR 0032); a
  Telegram outage degrades to retries and dead letters (ADR 0006) and never blocks a
  business transaction. An adapter-level circuit breaker distinguishes platform-wide
  Telegram failure from per-binding errors and raises its alert over a **non-Telegram**
  channel — an alert that can only ride the failing transport is not an alert.
- **The per-chat queue is an ordering boundary, not just a throttle**: strict FIFO per
  chat id, so a payment-confirmed can never overtake an order-cancelled in the same
  chat. Telegram's limits (~30 msg/s per bot, ~1 msg/s per chat, ~20/min per group)
  are a design input: digests batch, bursts coalesce. Across replicas, per-chat
  serialization uses a durable claim (the `OutboxRelay` lease pattern), because ADR
  0033's in-process limiter cannot coordinate two JVMs — or v1 explicitly pins the
  send path to a single consumer and says so.
- **Message lifecycle**: for a message tracking live state, edit in place while the
  tracked message is under Telegram's ~48-hour edit window and still present; on edit
  failure (expired, deleted, unchanged-content), send new and re-track. Bindings
  survive group upgrades: the adapter handles `migrate_to_chat_id` by rewriting the
  binding's chat id and replaying the failed send once. Retirement taxonomy covers
  reality: 403 (blocked/kicked) retires a binding, and so do the 400-class
  topic-deleted/thread-not-found answers — a phantom topic must not queue forever.
  **For a customer 1:1 binding, a 403 is consent revocation in effect**: it also flips
  that customer's Telegram preference off in ADR 0020's store, so records match
  reality.
- **The linking handshake verifies what it needs**: `/start` deep-link payloads are
  limited to 64 URL-safe characters (ADR 0044 already documents this), so link codes
  are short opaque tokens resolved against a server-side pending-link table with its
  own expiry. Group linking calls `getChatMember` to verify the bot's rights (topic
  posting needs admin with `can_manage_topics`) and fails actionably if absent; the
  operations bot is provisioned with BotFather privacy mode disabled, or typed
  commands in groups never reach it.
- **Templates are localized** (uz / ru / en) through ADR 0020's template model; the
  customer's language follows their profile, a group's language follows tenant
  configuration, and per-principal language is honored in personal surfaces (callback
  answers, 1:1 staff chats).
- **Every subscription is inspectable**: which chat receives what is a binding row an
  operator can list and revoke — parity with ADR 0026's installation discipline.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| FCM/web push as the primary channel | The mobile app is on hold (ADR 0055) and web push adoption in this market is weak; Telegram is where the audience already is | The Flutter app resumes and push re-earns its place alongside |
| SMS for customer notifications | Cost per message dwarfs Telegram at digest volumes; SMS stays for OTP fallback and for customers Telegram cannot reach (Click-only cohort) | Telegram Gateway OTP proves unreliable |
| A standalone bot service beside the platform | A second notification pipeline with its own retries, templates, and consent is the module-local reinvention AGENTS.md forbids | Never |
| Per-tenant BotFather bots for operations | Token lifecycle burden on tenants for no identity gain in an internal group — but see Open inputs: throughput isolation may force per-brand bots regardless of identity | A tenant demands their own branded staff bot, or shared-bot throughput contention appears |
| Email digests | Effectively unread in this market; adds a provider for no reach | An enterprise tenant contract requires it |

## Consequences

### Positive

- Every surface gets an off-screen channel through infrastructure that exists to its
  waist: installations, secrets, templates, the Camel route — with the missing half
  (trigger listeners per domain, the channel value, the adapter) named as build items
  rather than assumed.
- The digests give ADR 0043's metric layer its first real consumer — pressure in the
  right direction.

### Negative

- The supervisor and platform digests are **blocked on ADR 0043's day-close getting a
  production caller**; shipping digests first would ship empty numbers.
- Telegram becomes a soft dependency of operations awareness; its outages become
  support tickets even though order flow is untouched.
- Group-linking handshakes, rights verification, migration handling, and topic
  management add operator-facing surface area to build and document.
- The trigger-listener build-out touches many modules; each listener is small, but the
  sum is the real cost this record's first draft understated.

### Accepted trade-offs

One platform operations bot serving many tenant groups leaks the platform's name into
tenant spaces and shares one throughput ceiling; accepted for v1 in exchange for zero
tenant token management, and revisited the moment contention is observed (see Open
inputs).

## Specification

Deferred to implementation, with these fixtures of the design: a `telegram` provider
kind in `integration` (installation = bot token reference + webhook secret-token
reference; binding = chat id + optional topic id + audience + event-class
subscriptions, created by a `/link <code>` handshake initiated from the authorized
app); **the schema surface named honestly**: a `TELEGRAM` channel value added
append-only across the four V0026 CHECK constraints, and a binding-shaped
endpoint-reference variant beside the contact-point shape, since a chat binding is an
ADR 0026 object, not an ADR 0015 contact point; envelope-to-message templating in
`notifications` keyed by event type and locale; digest scheduling reading ADR 0043
facts on the tenant's business-day clock; adapter-level per-chat FIFO queues with
durable multi-replica claims and the Bot API error taxonomy (429 retry-after honored;
403 and topic-gone retire the binding; customer 403 syncs consent); a
`FakeTelegramBotApi` test double covering that taxonomy plus `migrate_to_chat_id`,
in the `FakeSmsGateway`/`FakeClickHttpProvider` genre ADR 0007 established; and the
Mini App `initData` verification needed for customer chat linking (shared groundwork
with the storefront session, tracked where ADR 0035 left it).

## Rollout and rollback

Rollout by audience: operations groups first (highest daily value, no consent surface),
then customer transactional, then digests once ADR 0043 closes days, then control-plane.
Rollback per audience is unsubscribing bindings; the pipeline beneath is unchanged.

## Implementation checklist

- [ ] Telegram provider kind: installation, secret references (token + webhook secret), binding + `/link` handshake with rights verification
- [ ] Channel migration: `TELEGRAM` across the four preference/template/notification/attempt constraints; binding-shaped endpoint reference
- [ ] Adapter on the Camel notification route: per-chat FIFO with durable claims, error taxonomy, `migrate_to_chat_id`, edit-window lifecycle, platform-wide breaker with non-Telegram alerting
- [ ] Webhook ingress with `secret_token` verification; long-polling profile for local dev
- [ ] Trigger listeners per event class outside ordering (payments, fulfillment, fiscal, inventory, integration, onboarding, bands) — each a named small build in its owning module
- [ ] Operations event subscriptions and group/topic routing
- [ ] Customer 1:1 linking via Mini App / deep-link code + pending-link table; consent sync on 403
- [ ] Digest scheduler over ADR 0043 facts (15m / half-day / day-close; platform half-day / day)
- [ ] Control-plane subscriptions (onboarding, approvals, drift, bands, subscriptions)
- [ ] `FakeTelegramBotApi` harness; PII lint covers Telegram payloads (ADR 0032/0029)
- [ ] Consent and preference surfaces per ADR 0020 for the customer audience

## Exit criteria

A pilot tenant's operations group receives a real order's approval-deadline warning and
a day-close digest with non-empty numbers; a customer's own chat shows their order's
state changes and fiscal receipt link; a forged webhook POST without the secret token
is dropped before parsing; a group upgraded to a supergroup keeps receiving without
operator help; every message arrived through the notifications-module → route → adapter
path with a delivery attempt row; and no group message anywhere carries a customer's
phone, address, or note.

## References

- ADR 0020 — notification preferences, templates, delivery (and the in-process,
  order-only reality this record builds out from)
- ADR 0043 — the metric layer the digests read (day-close caller is the named blocker)
- ADR 0045 — realtime SSE, the on-screen sibling of this channel
- ADR 0026/0028 — installations and secret references the bot tokens reuse
- ADR 0044 — the deep-link payload constraints already documented there
- docs/qoida-review.md — the legacy Telegram ops bot this consciously succeeds
