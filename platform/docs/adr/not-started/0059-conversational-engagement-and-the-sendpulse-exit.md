# ADR 0059: Conversations belong to the platform — flows, contacts, and the operator inbox

- Decision status: Accepted
- Implementation status: Not started — this record only. It builds on ADR 0058's
  Telegram plumbing (bot installations, chat bindings, the customer linking handshake),
  which is itself Not started.
- Date proposed: 2026-08-30
- Date decided: 2026-08-30
- Deciders: platform owner (directed the feature and the Telegram-first scope), Claude
  (architecture and the scope discipline)
- Depends on: 0020, 0025, 0026, 0028, 0029, 0044, 0051, 0058
- Supersedes / Superseded by: —
- Open inputs: who authors flows in practice — platform staff on tenants' behalf, or
  tenants themselves — which alone decides whether a visual flow builder is ever built
  (owner; revisit after the first three tenants run flows); the trigger for an
  Instagram/Meta adapter (owner; revisit only on a real tenant demand, because Meta's
  app-review overhead is paid up front)

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
- **Flows are declarative, versioned, per-brand configuration** — a flow document with
  a deliberately small block vocabulary (message, buttons, input-to-field, delay,
  condition, operator handoff), authored as config through the control-plane and
  executed by a flow engine in the platform. **No visual builder in v1**; the open
  input above decides if one is ever built.
- **The operator inbox lives in the operations app**: staff read and answer customer
  conversations for their location/brand there, with takeover from and return to the
  flow engine. Capability-gated (ADR 0025); conversation history is customer PII and
  follows ADR 0029 in storage and in reveal semantics.
- **Broadcasts and segments are ADR 0044 marketing** running over this channel under
  ADR 0020 consent — not a separate blast tool. Telegram rate limits shape batching,
  as ADR 0058 already requires.
- **The core is channel-neutral; only the Telegram adapter is built.** Instagram/Meta
  and others become adapters behind the same conversation/contact/flow model when a
  real demand triggers the open input — nothing in v1 may import a channel SDK type
  into the core (the standing hexagonal rule).
- **SendPulse is exited, not integrated.** No bridge phase: the bot is owned in
  BotFather, so cutover is repointing its webhook to the platform — atomic per bot and
  reversible by the same call — after contacts are exported through SendPulse's API
  into `customers` with consent flags carried over.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| Stay on SendPulse | The bill scales with the contact base by design; tenants' customer PII lives outside ADR 0029; the capability cannot be resold to tenants | Never as the end state; it remains the fallback during cutover by webhook repoint |
| A different vendor (Manychat, Chatfuel, …) | Same per-contact economics and the same data-outside-the-platform problem, plus a second migration later | — |
| Build the visual flow builder in v1 | The builder is the vendor's product, not this platform's; the observed most-used flow is six blocks of configuration; a GUI multiplies scope for an author who may not exist | The open input resolves to "tenants author their own flows" |
| Multi-channel from day one | Meta app review and API surface are paid before any tenant asks; the channel-neutral core keeps the door open at near-zero cost | A tenant demand names a second channel |
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
- Until the open input resolves, flow authoring is a platform-staff task — a support
  load the vendor used to carry.

### Accepted trade-offs

Config-as-flows trades SendPulse's drag-and-drop convenience for ownership and price;
accepted explicitly, with the GUI question parked behind a named trigger rather than
denied.

## Specification

Deferred to implementation, with these fixtures: a `conversations` concern (owning
conversation state, channel identity links, and message history under ADR 0029 rules)
whose module home is decided at implementation against the module map — `customers`,
`notifications`, or a new module, with the cycle lessons of the onboarding handlers in
mind; the flow document schema and engine (idempotent block execution, at-least-once
inbound updates deduplicated per ADR 0032 discipline); the Telegram adapter shared with
ADR 0058's; inbox endpoints under the operations surface with per-location capability
scoping; the SendPulse contact-export mapping with consent provenance.

## Rollout and rollback

Stage 1: flow engine + welcome flow for one brand bot in dev, linked accounts landing
in `customers`. Stage 2: operator inbox for the pilot tenant. Stage 3: contact export
and webhook cutover per bot — rollback is repointing the webhook back to SendPulse,
which keeps working until the account is closed. Stage 4: broadcasts under ADR 0044.

## Implementation checklist

- [ ] Conversation/contact-link model and history under ADR 0029
- [ ] Flow document schema, versioning, and engine (six block types)
- [ ] The observed welcome series reproduced as the first flow document
- [ ] Operator inbox in the operations app, capability-gated per location
- [ ] SendPulse contact export → `customers` with consent flags
- [ ] Webhook cutover runbook (per bot, reversible)
- [ ] Broadcast path through ADR 0044 + ADR 0020 consent

## Exit criteria

A customer who sends `/start` to a pilot brand bot receives the welcome series from the
platform's flow engine, their tapped feedback lands against their customer record, an
operator answers them from the operations inbox — and the SendPulse account for that
bot is closed without any customer noticing the cutover.

## References

- ADR 0058 — the Telegram channel plumbing this builds on
- ADR 0044 / 0020 — campaigns and consent for the broadcast path
- The observed SendPulse welcome series (JizBiz, 2026-08-30) — the sizing benchmark
