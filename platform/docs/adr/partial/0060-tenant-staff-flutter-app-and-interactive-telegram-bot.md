# ADR 0060: Service runs from a pocket — the staff Flutter app and the interactive Telegram bot

- Decision status: Accepted
- Implementation status: Partial — the interactive Telegram bot half is built and
  callable end to end (wave 6): staff Telegram identity linking (`/link` in a 1:1
  chat, `integration.telegram_staff_links`, V0105), `BotCallbackAuthorizer` with
  opaque callback tokens (`integration.bot_action_tokens`, V0106) re-checked live
  through `AuthorizationService.require` on every tap and audited per ADR 0027 with
  the real staff actor, inline Approve/Reject with immediate ack, keyboard
  stripping and lost-race settlement through `ordering`'s `OrderDecisionPort`,
  typed `/86` and `/stats` commands with multi-tenant DM disambiguation, and the
  stop-list audit gap closed on both the web and bot channels
  (`InventoryService.setAvailabilityAudited`) — entitlement-gated behind
  `integration.telegram_bot.interactive_enabled`. The staff Flutter app half —
  shell, kitchen views, stop-list and stats screens, user management, generated
  operations-client adoption — has not been started. `GrantChanged`-driven
  proactive keyboard cleanup remains the stated v2 enhancement.
- Date proposed: 2026-08-30
- Date decided: 2026-08-30
- Deciders: platform owner (directed both surfaces and the no-POS tenant focus), Claude
  (architecture; deep-reviewed 2026-08-30 against Telegram mechanics and the
  authorization/audit code, and amended)
- Depends on: 0025, 0027, 0033, 0035, 0039, 0041, 0043, 0045, 0055, 0058, 0059
- Supersedes / Superseded by: —
- Open inputs: none — the owner resolved gating on 2026-08-31: entitlement-gated
  from day one under the shared Telegram entitlement key family (ADR 0021).

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
pocket-sized fronts, the staff-identity link that lets Telegram act as one — and, per
review, a real authorization mechanism at the callback boundary, because capability
enforcement today lives only in the web layer's interceptor.

## Decision

Two staff surfaces, one backend, aimed at tenants who run without POS integration.
**The web operations board remains the authoritative surface; the bot and the app are
consumers of the same application services and the same event stream, never parallel
sources of truth.**

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
   The order notification carries inline Approve/Reject buttons; a tap flows through
   the same application services the web board calls; stop-list toggles and a stats
   query command work as typed commands. Interaction model: notification-plus-action,
   not menu navigation — the bot is the floor of the experience, deliberately thin,
   and anything richer deep-links into the Flutter app (or the web app). Mechanics the
   review pinned down: `callback_data` is capped at 64 bytes, so a button carries only
   an **opaque short token** indexing a server-side action record (order, action,
   decision-id, expiry) — nothing signed travels in the button; the callback is
   **acknowledged immediately** (`answerCallbackQuery` has a tight deadline and must
   not wait on the mutation) with the outcome delivered as a message edit or
   follow-up; on the **first successful decision the inline keyboard is stripped**
   (`editMessageReplyMarkup`), and a late tapper is answered with the settling
   decision and its actor, mirroring the web board's lost-race rendering; the
   operations bot ships with **privacy mode disabled** (ADR 0058's provisioning rule)
   or typed group commands never arrive; and a typed command from an **anonymous
   group admin** cannot resolve to a principal and is politely refused with a pointer
   to the buttons or to un-hiding — button taps still resolve the real tapper and are
   unaffected. Button labels and command replies are template content under ADR
   0058's uz/ru/en localization model.
3. **Staff identity linking — the mechanism, named**: from an authenticated staff
   session in the app (or web board), the staff member generates a one-time short
   code and sends `/link <code>` to the bot; the server resolves the pending link and
   binds the Telegram account to the principal. (Telegram's Login Widget and Mini App
   `initData` apply only inside WebViews and cannot carry a native app's session —
   hence the code handshake; the code is short and opaque per the same 64-char
   deep-link constraint ADR 0044 documents.) One Telegram account may hold **multiple
   per-tenant links**: order callbacks self-scope through their action record, and an
   ambiguous DM command (stats, stop list) is answered with a tenant picker unless
   the principal holds exactly one active grant.
4. **Authorization is a named mechanism, not an assumption.** Capability enforcement
   today lives exclusively in the web layer's `@RequiresCapability` interceptor — the
   application services enforce nothing themselves — so "calling the same service"
   would silently be a bypass. The bot boundary therefore gets a
   **`BotCallbackAuthorizer`**: it resolves the action token to the linked principal
   and calls the same `AuthorizationService.require(subject, capability, scope)` the
   interceptor uses, before invoking the shared application service; and it records
   the ADR 0027 audit fact with the real staff actor (the ordering audit recorder
   already takes explicit actor identity, so bot-actor parity is a call-site
   discipline, not new machinery). The bot holds no authority of its own; a revoked
   staff member's next tap is refused. **v1 revocation is check-at-tap** — stale
   buttons remain visible on old messages but dead; proactively stripping keyboards
   on `GrantChanged` (the pattern `RealtimeStreamMaintenance` already uses to close
   SSE streams on revoke) is a stated enhancement, not an implicit promise.
   One inherited gap named rather than absorbed: **the stop-list toggle is not an ADR
   0027 audit fact today on ANY channel** — wiring the catalog authoring service into
   the audit recorder is a checklist item here, because a bot must not launch on an
   unaudited mutation.
5. **PII discipline unchanged** (ADR 0058's rule): group messages stay masked; even a
   linked staff member's 1:1 bot chat carries the masked projection, with reveal
   remaining an audited in-app action — Telegram is a transport we do not own, and
   the phone number of a customer does not transit it.
6. **The no-POS tenant is the design center**: the acceptance loop must be fully
   operable — accept, reject, advance, 86 — with only Telegram installed; the Flutter
   app adds depth, never gates the floor.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| A Telegram Mini App version of the operations web app instead of a native staff app | Tempting single codebase, but the fulfilment loop needs push, reliability on weak networks, and eventually device features (printing, sound alerts) a Mini App serves poorly; the bot already covers the zero-install floor | The Mini App route may still carry the stats/read-only views later — cheap to add atop 0059's machinery |
| Responsive-web-only (the existing Angular app on a phone browser) | No push without the app being open, and "open a browser tab" is exactly the step the no-POS tenant skips; it stays available but is not the answer | Never as the floor |
| Extend the customer Flutter app with a staff mode | One binary with two trust postures and two audiences invites the exact capability confusion ADR 0025 exists to prevent; app-store review also treats them differently | Never |
| Bot-only, no Flutter app | The floor without the ceiling: kitchen management, stats and user management are cramped in chat UI, and the owner directed both surfaces | — |
| Signed authority embedded in callback payloads | The common bot pattern, and exactly backwards for revocation — authority must be re-checked against the live grant at tap time, and 64 bytes could not carry it anyway | Never |
| Wait for POS integrations to cover these tenants | The tenants in question will never buy the POS; that is the premise | — |

## Consequences

### Positive

- The smallest tenant can run service with nothing but Telegram — the acceptance loop
  survives having no hardware at all, which is the stated point.
- Both surfaces consume endpoints that already exist and already enforce capabilities
  at the web layer, so the new backend cost concentrates in the callback authorizer,
  identity linking, and the audit-parity wiring.
- The staff app finally gives the `operations` OpenAPI group a generated-client
  consumer, forcing the schema-collision fix that blocks adoption everywhere.

### Negative

- A second Flutter application to build and maintain while the first sits on hold —
  the monorepo's app count grows faster than its team.
- Bot-actionable orders make Telegram availability part of service operations for
  bot-only tenants; an outage degrades them to the web app they were promised they
  would not need (ADR 0058's platform-wide breaker and non-Telegram alerting apply).
- Stats views inherit ADR 0043's day-close blocker; shipping them early ships empty
  numbers (same honesty rule as ADR 0058's digests).

### Accepted trade-offs

The bot's thin, notification-plus-action shape will disappoint anyone expecting a full
chat-driven POS; accepted — depth belongs to the app, and the bot's job is that the
process never stops. Check-at-tap revocation leaves dead-but-visible buttons on old
messages in v1; accepted, with proactive cleanup named as the enhancement.

## Specification

Deferred to implementation, with these fixtures: the staff app lives beside the
customer app in the monorepo sharing extracted design packages; it consumes the
`operations` contract group via generated client; staff linking is the authenticated
one-time-code `/link` handshake above, stored per (telegram account, tenant) with
revocation following the grant; bot callbacks are opaque tokens resolved server-side
through `BotCallbackAuthorizer` → `AuthorizationService.require` → the same
application service the web board calls, with decision-id idempotency and ADR 0027
audit carrying the bot actor; callback acks are immediate and outcomes are edits;
keyboards are stripped on first decision; kitchen and stats screens track ADRs
0041/0043 rather than leading them; the shared `FakeTelegramBotApi` harness (ADR 0058)
covers the callback taxonomy.

## Rollout and rollback

Bot first (it is the floor and the smaller build), behind per-tenant enablement and the
resolved entitlement answer; Flutter app by feature slice in the order listed,
pilot-tenant cohort first. Rollback per surface is disablement; the web operations app
remains the constant.

## Implementation checklist

- [ ] Staff Telegram identity link (one-time-code handshake, per-tenant links, revocation follows the grant)
- [ ] `BotCallbackAuthorizer`: token indirection (≤64-byte opaque callback data → server-side action record), live-grant `require`, ADR 0027 audit with bot actor
- [ ] Inline-action flow: immediate ack, edit-on-outcome, keyboard stripped on first decision, lost-race answer with settling actor
- [ ] Stop-list toggle and stats query commands in the bot (tenant disambiguation for multi-link principals)
- [ ] Audit the stop-list toggle on every channel (wire catalog authoring into the audit recorder — pre-existing gap, closed here)
- [ ] Staff Flutter app shell + auth + the acceptance/fulfilment loop
- [ ] Kitchen ticket views (to ADR 0041's built depth)
- [ ] Stop-list screen; stats screens (gated on ADR 0043's day-close caller)
- [ ] User management: invites and grant assignment over existing surfaces
- [ ] Generated `operations` client adopted (schema-collision fix is a prerequisite)
- [ ] `GrantChanged`-driven stale-keyboard cleanup (stated enhancement, after v1)

## Exit criteria

A pilot tenant with no POS and no computer accepts, advances, and completes a real
order entirely from a phone — once through the Telegram bot alone, once through the
staff Flutter app — with every action authorized against the acting staff member's own
grant at tap time and recorded as an audit fact naming them; a revoked member's next
tap refused; a second tap on a decided order answered with who decided it; and the
86-toggle auditable on web and bot alike.

## References

- ADR 0058 / 0059 — the Telegram plumbing, provisioning rules, and conversation model this stands on
- ADR 0039 / 0041 / 0043 — the operator, kitchen, and stats capabilities it fronts
- ADR 0025 / 0027 — the authorization and audit models the callback boundary re-enters
- ADR 0035 / 0055 — the frontend platform and the on-hold customer app it lives beside
- platform/docs/operations-spec/ — the UI truths the app inherits where applicable
