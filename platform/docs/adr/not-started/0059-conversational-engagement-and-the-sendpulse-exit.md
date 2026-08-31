# ADR 0059: Conversations belong to the platform — flows, contacts, and the operator inbox

- Decision status: Accepted
- Implementation status: Not started — this record only. It builds on ADR 0058's
  Telegram plumbing (bot installations, chat bindings, inbound authentication, the
  customer linking handshake), which is itself Not started.
- Date proposed: 2026-08-30
- Date decided: 2026-08-30
- Deciders: platform owner (directed the feature, the Telegram-first scope, YAML-only
  authoring, and the owner-directive trigger for further channels), Claude
  (architecture and scope discipline; deep-reviewed 2026-08-30 and amended)
- Depends on: 0020, 0025, 0026, 0027, 0028, 0029, 0044, 0051, 0058
- Supersedes / Superseded by: —
- Open inputs: none for gating — resolved by the owner on 2026-08-31: entitlement-
  gated from day one under the shared Telegram entitlement key family (ADR 0021),
  decided together with ADR 0058's bot-per-brand topology.
  The Instagram/Meta adapter trigger is resolved: the owner's own directive alone —
  no tenant demand or market threshold gates it. Flow authoring is resolved: YAML,
  and no visual builder will ever be built.

## Context

The business today runs its customer-facing Telegram bot on SendPulse: the client base
lives there, a welcome-series flow answers `/start` (observed 2026-08-30: greeting, an
order button opening the ordering surface, a feedback branch capturing free text into a
contact field, a thank-you), and staff chat with customers through SendPulse's inbox.
Two forces push this in-platform. Economically, SendPulse prices per contact, so the
bill grows with exactly the asset the platform already stores for free — `customers` is
tenant-scoped and consent-modeled, and duplicating it in a vendor pays rent on our own
data. In governance terms, customer PII sitting in a third party lives outside the
envelope encryption ADR 0029 mandates for the platform's own copy of the same facts.
And strategically: a multi-tenant HoReCa platform in a Telegram-first market can hand
every tenant a brand bot with flows and an inbox as a product feature; a SendPulse
account cannot be resold.

The observed flow also bounds the problem honestly: three messages, two buttons, one
captured input. The engagement product this business actually uses is small; the vendor
around it is large.

## Decision

The platform owns conversational engagement, Telegram first, through a channel-neutral
core and channel-specific adapters:

- **Contacts are `customers`.** No parallel contact store. A conversation links a
  channel identity (Telegram chat) to a customer account via ADR 0058's handshake;
  captured inputs (the `{{feedback}}`-style fields) land as customer data or as the
  feedback/support facts the relevant module owns — never as an engagement-silo copy.
  (One flag from review: `helpcenter`, an obvious destination for feedback facts, has
  no owning ADR anywhere in the corpus — routing durable data into an ungoverned
  module would undercut this record's own boundary discipline; give it a record or
  route elsewhere.)
- **Flows are declarative, versioned, per-brand YAML documents** — workflow, states,
  and a deliberately small block vocabulary (message, buttons, input-to-field, delay,
  condition, operator handoff), authored as configuration through the control-plane
  and executed by a flow engine in the platform. **No visual builder — decided, not
  deferred** (owner, 2026-08-30): YAML is the authoring surface, full stop; a future
  GUI would need a superseding record.
- **The conversations concern is very likely a new module.** Review against the
  documented module cycle (`catalog → tenancy → payments → integration →
  notifications → ordering → pricing → catalog`, the one the onboarding handlers hit
  in practice) disqualifies this record's earlier candidates by default: folding
  conversations into `customers` or `notifications` puts inbound-dispatch edges from
  `integration` into cycle-exposed territory. The default answer is a new leaf module
  (`conversations`) that `integration` depends on one-way, itself importing only
  `customers`/`notifications` api types and using the established
  raw-SQL-by-schema escape hatch for anything deeper. Final confirmation at
  implementation, against `ModularArchitectureTests`.
- **The operator inbox lives in the operations app**: staff read and answer customer
  conversations for their location/brand there, with takeover from and return to the
  flow engine. Capability-gated (ADR 0025); inbox actions are ADR 0027 audit facts
  like any other staff action.
- **Conversation history has a stated PII posture, not a gesture**: free-text message
  bodies and captured inputs are envelope-encrypted (ADR 0029); channel ids, block
  ids, and timing metadata are not. Default retention is 12 months, tenant-adjustable
  downward. Two named dependencies, in the same spirit as ADR 0058's day-close
  honesty: ADR 0029 has no retention/erasure machinery yet for this posture to run
  on, and its envelope scheme reasons about order-scale write volumes — chat-scale
  volume needs its own look before stage 1 ships.
- **Broadcasts and segments are ADR 0044 marketing** running over this channel under
  ADR 0020 consent — not a separate blast tool. The Bot API has no bulk-send product:
  the campaign scheduler paces within the per-bot ceiling (~30 msg/s shared by ALL of
  that bot's traffic), reports an estimated delivery window instead of promising
  instant delivery, and monitors user block/report rates — Telegram's own anti-spam
  enforcement acts on those regardless of our consent records. This is also a bot
  topology input (ADR 0058's open input): a shared bot means one tenant's campaign
  can starve another tenant's order alerts.
- **The core is channel-neutral; only the Telegram adapter is built** — shared with
  ADR 0058's adapter, including its inbound `secret_token` authentication and its
  `FakeTelegramBotApi` test harness. Instagram/Meta and others become adapters behind
  the same conversation/contact/flow model when the owner directs it — nothing in v1
  may import a channel SDK type into the core (the standing hexagonal rule).
- **SendPulse is exited, not integrated — and the exit closes the door.** The bot is
  owned in BotFather, so cutover is repointing its webhook to the platform — atomic
  per bot and reversible by the same call — after contacts are exported through
  SendPulse's API into `customers` with consent flags carried over. Two truths the
  cutover plan states rather than hides: **flow state is not exportable** — a
  customer mid-flow at cutover is treated as idle and simply starts fresh at their
  next `/start` (blast radius is small for a three-message welcome series, and the
  runbook says so instead of promising nobody notices anything); and **SendPulse has
  held the bot token**, so cutover ends with a BotFather token rotation and an ADR
  0026 secret-reference update — until that rotation, the vendor retains the standing
  ability to re-point the webhook to itself.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| Stay on SendPulse | The bill scales with the contact base by design; tenants' customer PII lives outside ADR 0029; the capability cannot be resold to tenants | Never as the end state; it remains the fallback during cutover by webhook repoint |
| A different vendor (Manychat, Chatfuel, …) | Same per-contact economics and the same data-outside-the-platform problem, plus a second migration later | — |
| Build a visual flow builder | The builder is the vendor's product, not this platform's; the observed most-used flow is six blocks of configuration; the owner decided YAML is the authoring surface | Never absent a superseding record |
| Multi-channel from day one | Meta app review and API surface are paid before anyone needs them; the channel-neutral core keeps the door open at near-zero cost | The owner directs a second channel |
| Bolt the inbox onto Telegram's own group tools | Group chats leak customer PII to all members and offer no capability gating, assignment, or history tied to the customer record | Never |

## Consequences

### Positive

- The engagement bill stops scaling with the customer base; the customer base stops
  living outside the platform's encryption and consent model.
- Every tenant gains a brand bot, welcome flows, and a staffed inbox as a platform
  feature — a real differentiator in this market.
- ADR 0043's day-close and ADR 0044's campaigns both gain a delivery surface that
  people actually read.

### Negative

- The operations app takes on a genuinely medium-sized new surface (the inbox:
  assignment, unread state, takeover/return, history) — the largest single piece here.
- The flow engine becomes an availability concern for first-contact customer
  experience; a broken flow answers `/start` with silence.
- Flow authoring is a platform-staff task by design — a support load the vendor's GUI
  used to carry, accepted in exchange for ownership and reviewable, versionable flows.
- The ADR 0029 retention/erasure gap and the chat-scale volume question are real
  pre-work, not paperwork.

### Accepted trade-offs

Config-as-flows trades SendPulse's drag-and-drop convenience for ownership, price,
and reviewable YAML; accepted explicitly and permanently — the GUI is denied, not
parked.

## Specification

Deferred to implementation, with these fixtures: a `conversations` module (default: new
leaf module per the Decision) owning conversation state, channel identity links, and
message history under the stated PII posture; the flow YAML schema and engine
(idempotent block execution, at-least-once inbound updates deduplicated per ADR 0032
discipline, inbound updates authenticated per ADR 0058's `secret_token` rule); the
Telegram adapter shared with ADR 0058's, including the `FakeTelegramBotApi` harness;
inbox endpoints under the operations surface with per-location capability scoping and
ADR 0027 audit; the SendPulse contact-export mapping with consent provenance; the
token-rotation cutover step.

## Rollout and rollback

Stage 1: flow engine + welcome flow for one brand bot in dev, linked accounts landing
in `customers`. Stage 2: operator inbox for the pilot tenant (entitlement question
answered by then). Stage 3: contact export and webhook cutover per bot, closed by the
BotFather token rotation — rollback before rotation is repointing the webhook back to
SendPulse, which keeps working until the account is closed. Stage 4: broadcasts under
ADR 0044 with paced delivery and block-rate monitoring.

## Implementation checklist

- [ ] Conversation/contact-link model and history under the stated PII posture (fields, 12-month default retention) — with ADR 0029's retention/erasure gap and volume sizing named as pre-work
- [ ] Flow YAML schema (workflow, states, six block types), versioning, and engine
- [ ] The observed welcome series reproduced as the first flow document
- [ ] Operator inbox in the operations app, capability-gated per location, ADR 0027-audited
- [ ] SendPulse contact export → `customers` with consent flags; mid-flow-state caveat in the cutover runbook
- [ ] Webhook cutover runbook (per bot, reversible) ending in BotFather token rotation + secret-reference update
- [ ] Broadcast path through ADR 0044 + ADR 0020 consent, paced within per-bot throughput, block-rate monitored

## Exit criteria

A customer who sends `/start` to a pilot brand bot receives the welcome series from the
platform's flow engine, their tapped feedback lands against their customer record, an
operator answers them from the operations inbox — and the SendPulse account for that
bot is closed after the token rotation, with no customer noticing the cutover except,
at worst, a mid-flow customer who starts the three-message welcome again.

## References

- ADR 0058 — the Telegram channel plumbing, inbound authentication, and test harness this shares
- ADR 0044 / 0020 — campaigns and consent for the broadcast path
- ADR 0029 — the PII regime, and its named retention/erasure gap
- The observed SendPulse welcome series (JizBiz, 2026-08-30) — the sizing benchmark
