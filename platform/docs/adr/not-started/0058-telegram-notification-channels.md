# ADR 0058: Telegram is a first-class notification channel for all three surfaces

- Decision status: Accepted
- Implementation status: Not started — this record only. The pieces it builds on exist
  to the depth their own records state: the notification model (ADR 0020), the Camel
  notification route (ADR 0007), provider installations (ADR 0026), secret references
  (ADR 0028), and the metric layer the digests need (ADR 0043, whose day-close still
  has no production caller — a named dependency below).
- Date proposed: 2026-08-30
- Date decided: 2026-08-30
- Deciders: platform owner (directed the channel and the per-surface scope), Claude
  (research and architecture)
- Depends on: 0004, 0006, 0007, 0020, 0025, 0026, 0028, 0029, 0032, 0043, 0045
- Supersedes / Superseded by: —
- Open inputs: bot topology per brand (one platform storefront bot vs a bot per brand —
  a brand-identity and BotFather-ownership question the first pilot tenant answers);
  whether supervisor stat digests are entitlement-gated (ADR 0021) from day one

## Context

Uzbekistan runs on Telegram. The legacy system already operated a Telegram ops bot; the
storefront already ships Telegram Mini App support; `TELEGRAM` already exists as a
payment method code; Click itself reaches customers through Telegram surfaces. Meanwhile
the platform's own notification story is browser-bound: ADR 0045's SSE streams serve an
open operations tab, and ADR 0020's model has templates and delivery attempts with no
channel that reaches a person whose screen is off. A restaurant owner in this market
expects the order feed, the incident, and the day's numbers in a Telegram group — with
or without forum topics — not in an email they do not read.

The owner directed the per-surface scope; research through the existing records added
the items each surface's own ADRs already generate but nothing yet delivers.

## Decision

Telegram becomes a delivery channel of the existing notification pipeline — never a
side channel. Events flow domain → outbox (ADR 0004) → `notifications` module
(preferences, templates, attempts per ADR 0020) → the Camel notification route
(ADR 0007) → a Telegram Bot API adapter in `integration`. Bot tokens are ADR 0026
provider installations carrying ADR 0028 secret references. Chats and topics are
provider bindings created through a verification handshake, never hand-entered ids.

Three audience models, one pipeline:

1. **Storefront (customer)** — a 1:1 bot chat, linked to the customer account through
   the Mini App context or a `/start` deep-link token bound to their session (ADR 0051).
   Transactional by default; marketing only under explicit ADR 0020 consent. Events:
   order state changes, payment status (including failure with a retry link), delivery
   and courier status with tracking deep-link, the fiscal receipt's OFD link on
   issuance (a legal artifact, not a courtesy), refund/remedy outcomes (ADR 0048),
   loyalty movements (ADR 0046) when that ships, and — separately feasibility-checked —
   OTP delivery via Telegram Gateway as a cheaper sibling to SMS.
2. **Operations (tenant staff)** — tenant-owned groups, optionally forum supergroups
   with topics per concern (orders / incidents / stats) or per location; flat groups
   are the degraded mode, never a blocker. Events: unhandled orders approaching their
   approval deadline (the same thresholds as the board's severity model), order
   incidents and BLOCKED processes needing a person, dispatch and courier events,
   payment failures and UNCERTAIN resolutions, blocked fiscal obligations (ADR 0038's
   worklist), stock-outs/86'd items, POS export and provider circuit-breaker alerts,
   dead-letter arrivals (ADR 0006) — and supervisor digests at 15 minutes, half-day,
   and day-close from the ADR 0043 metric layer.
3. **Control-plane (platform staff)** — platform-owned groups/topics. Events:
   onboarding run progress and the existing stuck-run alert, tenants awaiting
   activation approval (the maker-checker queue, ADR 0027), payment provider health
   across tenants, subscription and metering events (ADR 0021), identity drift
   findings (ADR 0009), control-band tier escalations (`ops/bands.yaml` — arithmetic
   detects, Telegram tells a human), and platform digests at half-day and day
  granularity.

Hard rules, inherited and specific:

- **Group chats never carry customer PII** (ADR 0029): no phone, no address, no note in
  an operations or control-plane message — a deep link into the authorized app carries
  the reader the rest of the way. A customer's own 1:1 chat may carry their own data.
- **At-least-once, deduplicated**: consumers of `notifications.commands` are idempotent
  by `eventId` (ADR 0032); a Telegram outage degrades to retries and dead letters
  (ADR 0006) and never blocks a business transaction.
- **Telegram's rate limits are a design input** (~30 msg/s global, ~1 msg/s per chat,
  20/min per group): digests batch, bursts coalesce (an order that changes state three
  times in a minute sends one edit or one summarizing message, not three), and per-chat
  send queues throttle in the adapter.
- **Templates are localized** (uz / ru / en) through ADR 0020's template model; the
  customer's language follows their profile, a group's language follows tenant
  configuration.
- **Every subscription is inspectable**: which chat receives what is a binding row an
  operator can list and revoke — parity with ADR 0026's installation discipline.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| FCM/web push as the primary channel | The mobile app is on hold (ADR 0055) and web push adoption in this market is weak; Telegram is where the audience already is | The Flutter app resumes and push re-earns its place alongside |
| SMS for customer notifications | Cost per message dwarfs Telegram at digest volumes; SMS stays for OTP fallback only | Telegram Gateway OTP proves unreliable |
| A standalone bot service beside the platform | A second notification pipeline with its own retries, templates, and consent is the module-local reinvention AGENTS.md forbids | Never |
| Per-tenant BotFather bots for operations | Token lifecycle burden on tenants for no identity gain in an internal group | A tenant demands their own branded staff bot |
| Email digests | Effectively unread in this market; adds a provider for no reach | An enterprise tenant contract requires it |

## Consequences

### Positive

- Every surface gets an off-screen channel through infrastructure that already exists
  to its waist: outbox, route, templates, installations, secrets.
- The digests give ADR 0043's metric layer its first real consumer — pressure in the
  right direction.

### Negative

- The supervisor and platform digests are **blocked on ADR 0043's day-close getting a
  production caller**; shipping digests first would ship empty numbers.
- Telegram becomes a soft dependency of operations awareness; its outages become
  support tickets even though order flow is untouched.
- Group-linking handshakes and topic management add operator-facing surface area to
  build and document.

### Accepted trade-offs

One platform operations bot serving many tenant groups leaks the platform's name into
tenant spaces; accepted for v1 in exchange for zero tenant token management.

## Specification

Deferred to implementation, with these fixtures of the design: a `telegram` provider
kind in `integration` (installation = bot token reference; binding = chat id + optional
topic id + audience + event-class subscriptions, created by a `/link <code>` handshake
initiated from the authorized app); envelope-to-message templating in `notifications`
keyed by event type and locale; digest scheduling reading ADR 0043 facts; adapter-level
per-chat queues and Bot API error taxonomy (429 retry-after honored, 403 forbidden
retires the binding); the Mini App `initData` verification needed for customer chat
linking (shared groundwork with the storefront session, tracked where ADR 0035 left it).

## Rollout and rollback

Rollout by audience: operations groups first (highest daily value, no consent surface),
then customer transactional, then digests once ADR 0043 closes days, then control-plane.
Rollback per audience is unsubscribing bindings; the pipeline beneath is unchanged.

## Implementation checklist

- [ ] Telegram provider kind: installation, secret reference, binding + `/link` handshake
- [ ] Adapter on the Camel notification route with per-chat throttling and error taxonomy
- [ ] Operations event subscriptions (deadline, incident, dispatch, payment, fiscal, dead-letter)
- [ ] Customer 1:1 linking via Mini App / deep link; transactional templates uz/ru/en
- [ ] Digest scheduler over ADR 0043 facts (15m / half-day / day-close; platform half-day / day)
- [ ] Control-plane subscriptions (onboarding, approvals, drift, bands, subscriptions)
- [ ] PII lint: the event-classification test (ADR 0032/0029) covers Telegram payloads
- [ ] Consent and preference surfaces per ADR 0020 for the customer audience

## Exit criteria

A pilot tenant's operations group receives a real order's approval-deadline warning and
a day-close digest with non-empty numbers; a customer's own chat shows their order's
state changes and fiscal receipt link; every message arrived through the outbox → route
→ adapter path with a delivery attempt row; and no group message anywhere carries a
customer's phone, address, or note.

## References

- ADR 0020 — notification preferences, templates, delivery
- ADR 0043 — the metric layer the digests read (day-close caller is the named blocker)
- ADR 0045 — realtime SSE, the on-screen sibling of this channel
- ADR 0026/0028 — installations and secret references the bot tokens reuse
- docs/qoida-review.md — the legacy Telegram ops bot this consciously succeeds
