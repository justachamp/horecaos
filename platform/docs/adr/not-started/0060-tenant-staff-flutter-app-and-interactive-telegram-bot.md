# ADR 0060: Service runs from a pocket — the staff Flutter app and the interactive Telegram bot

- Decision status: Accepted
- Implementation status: Not started — this record only. The operations backend it
  fronts largely exists (order actions, counts, the availability listing and toggle,
  grants); the kitchen (ADR 0041), stats (ADR 0043), and staff-invitation (ADR 0009)
  halves are Partial to the depth their own records state.
- Date proposed: 2026-08-30
- Date decided: 2026-08-30
- Deciders: platform owner (directed both surfaces and the no-POS tenant focus), Claude
  (architecture)
- Depends on: 0025, 0035, 0039, 0041, 0043, 0045, 0055, 0058, 0059
- Supersedes / Superseded by: —
- Open inputs: none

## Context

The operations Angular application assumes a screen at the pass: a browser, a tablet, a
laptop. The tenants this platform courts first often have none of those — a small café
in this market runs on the owner's phone, has no POS and will not buy one, and its
staff already live in Telegram. For such a tenant, "the operations app is down the hall"
means orders time out unanswered: the process stops exactly where ADR 0002's acceptance
model says a human must act. The owner's directive names the fix: the acceptance and
fulfilment loop must run from a pocket — a staff Flutter app for the full experience,
and an interactive Telegram staff bot as the floor below it, so that a tenant with
nothing installed at all can still keep service moving.

The backend for this mostly exists already: server-supplied order `actions[]`,
approve/reject with first-decision-wins, state advances, the counts endpoint, the
86-list read model and toggle, capability-scoped grants. What is missing is the two
pocket-sized fronts and the staff-identity link that lets Telegram act as one.

## Decision

Two staff surfaces, one backend, aimed at tenants who run without POS integration:

1. **A staff Flutter application** — a second Flutter app in the monorepo, distinct
   from the on-hold customer app (ADR 0055) but sharing its extracted design
   foundations. Scope, in priority order: order acceptance and fulfilment (the board's
   core loop: incoming orders, approve/reject with reasons, state advances, order
   detail with the same masked-PII/reveal semantics as the web app); cooking process
   management (kitchen tickets per ADR 0041 as far as that record is built — the app
   consumes, never reinvents); the stop list (the 86 listing and toggle); stats (the
   supervisor views, consuming ADR 0043's metric layer and therefore gated by its
   day-close caller like every other stats consumer); and user management (inviting
   staff and assigning their grants through the existing ADR 0025 surfaces). It speaks
   the `operations` OpenAPI group (ADR 0057) — generated client, once the known
   schema-collision bug is fixed — and authenticates like the web operations app.
2. **An interactive staff Telegram bot** — ADR 0058's operations channel grows hands.
   The order notification carries inline Approve/Reject buttons; a callback taps
   through to the same operations endpoints; stop-list toggles and a stats query
   command work the same way. Interaction model: notification-plus-action, not menu
   navigation — the bot is the floor of the experience, deliberately thin, and
   anything richer deep-links into the Flutter app (or the web app).
3. **Staff identity linking** — a staff member links their Telegram account to their
   platform principal through a handshake mirroring ADR 0058/0059's customer linking,
   but with the opposite trust posture: every bot-initiated action is authorized
   against the linked principal's own grants (ADR 0025) at the moment of the tap —
   the bot holds no authority of its own, a revoked staff member's buttons go dead
   with their grant, and an unlinked Telegram account can read nothing. Linked-staff
   action callbacks are idempotent (the decision-id discipline the web board already
   uses) because Telegram retries callbacks.
4. **PII discipline unchanged** (ADR 0058's rule): group messages stay masked; even a
   linked staff member's 1:1 bot chat carries the masked projection, with reveal
   remaining an audited in-app action — Telegram is a transport we do not own, and
   the phone number of a customer does not transit it.
5. **The no-POS tenant is the design center**: the acceptance loop must be fully
   operable — accept, reject, advance, 86 — with only Telegram installed; the Flutter
   app adds depth, never gates the floor.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| A Telegram Mini App version of the operations web app instead of a native staff app | Tempting single codebase, but the fulfilment loop needs push, reliability on weak networks, and eventually device features (printing, sound alerts) a Mini App serves poorly; the bot already covers the zero-install floor | The Mini App route may still carry the stats/read-only views later — cheap to add atop 0059's machinery |
| Responsive-web-only (the existing Angular app on a phone browser) | No push without the app being open, and "open a browser tab" is exactly the step the no-POS tenant skips; it stays available but is not the answer | Never as the floor |
| Extend the customer Flutter app with a staff mode | One binary with two trust postures and two audiences invites the exact capability confusion ADR 0025 exists to prevent; app-store review also treats them differently | Never |
| Bot-only, no Flutter app | The floor without the ceiling: kitchen management, stats and user management are cramped in chat UI, and the owner directed both surfaces | — |
| Wait for POS integrations to cover these tenants | The tenants in question will never buy the POS; that is the premise | — |

## Consequences

### Positive

- The smallest tenant can run service with nothing but Telegram — the acceptance loop
  survives having no hardware at all, which is the stated point.
- Both surfaces consume endpoints that already exist and already enforce capabilities,
  so the backend cost is concentrated in staff identity linking and bot callbacks.
- The staff app finally gives the `operations` OpenAPI group a generated-client
  consumer, forcing the schema-collision fix that blocks adoption everywhere.

### Negative

- A second Flutter application to build and maintain while the first sits on hold —
  the monorepo's app count grows faster than its team.
- Bot-actionable orders make Telegram availability part of service operations for
  bot-only tenants; an outage degrades them to the web app they were promised they
  would not need.
- Stats views inherit ADR 0043's day-close blocker; shipping them early ships empty
  numbers (same honesty rule as ADR 0058's digests).

### Accepted trade-offs

The bot's thin, notification-plus-action shape will disappoint anyone expecting a full
chat-driven POS; accepted — depth belongs to the app, and the bot's job is that the
process never stops.

## Specification

Deferred to implementation, with these fixtures: the staff app lives beside the
customer app in the monorepo sharing extracted design packages; it consumes the
`operations` contract group via generated client; staff Telegram linking is a
capability-gated handshake issued from inside an authenticated staff session (never
self-service from Telegram alone); bot callbacks carry signed, expiring payloads that
resolve to (principal, order, action, decision-id) server-side; every callback path is
the same application service the web board calls, never a parallel one; kitchen and
stats screens track ADRs 0041/0043 rather than leading them.

## Rollout and rollback

Bot first (it is the floor and the smaller build), behind per-tenant enablement;
Flutter app by feature slice in the order listed, pilot-tenant cohort first. Rollback
per surface is disablement; the web operations app remains the constant.

## Implementation checklist

- [ ] Staff Telegram identity link (handshake, storage, revocation follows the grant)
- [ ] Inline-action callbacks: approve/reject/advance with decision-id idempotency
- [ ] Stop-list toggle and stats query commands in the bot
- [ ] Staff Flutter app shell + auth + the acceptance/fulfilment loop
- [ ] Kitchen ticket views (to ADR 0041's built depth)
- [ ] Stop-list screen; stats screens (gated on ADR 0043's day-close caller)
- [ ] User management: invites and grant assignment over existing surfaces
- [ ] Generated `operations` client adopted (schema-collision fix is a prerequisite)

## Exit criteria

A pilot tenant with no POS and no computer accepts, advances, and completes a real
order entirely from a phone — once through the Telegram bot alone, once through the
staff Flutter app — with every action authorized against the acting staff member's own
grant, and a revoked member's next tap refused.

## References

- ADR 0058 / 0059 — the Telegram plumbing and conversation model this stands on
- ADR 0039 / 0041 / 0043 — the operator, kitchen, and stats capabilities it fronts
- ADR 0035 / 0055 — the frontend platform and the on-hold customer app it lives beside
- platform/docs/operations-spec/ — the UI truths the app inherits where applicable
