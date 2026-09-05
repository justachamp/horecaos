# ADR 0064: Voice joins the channels — IP telephony, operator presence, and the screen-pop

- Decision status: Accepted — drafted at the owner's direction 2026-09-02 and
  accepted by the owner the same day, after the provider-neutrality amendment;
  the open inputs below gate the build's start, not the decision.
- Implementation status: Partial — VOICE provider category, secret references,
  and event ingestion with the webhook-dedup/outbox discipline are built; both
  named adapter kinds exist (a hosted SIP/PBX webhook and an Asterisk-class AMI
  client) and are proven against a fake PBX in the ADR 0007 genre, never
  against a live provider account, because none exists (see the 2026-09-05
  resolution below). Operator presence (ONLINE/PAUSED/WRAP_UP/OFFLINE, audited,
  channel-neutral) is built and reachable from the operations app. The
  screen-pop is built: caller resolution against customer identity, a masked
  display by default, an audited full-number reveal gating the
  create-customer prefill, and recent orders on a resolved caller's card — all
  reachable from IA §1.6 (Call centre). Call-to-order provenance is built as a
  write-once field the operations app sets once it holds both ids. Call facts
  land in a new `reporting.fact_call_hour` table through the same
  `DayCloseService.close()` transaction every other ADR 0043 fact uses; the
  owner-facing read is a dedicated endpoint under the existing
  `REPORTING_READ` capability, not yet a signed metric in `MetricRegistry`
  (see Consequences). Not built: call audio or recording of any kind (out of
  scope by owner direction); a routing adapter that actually drives a live
  PBX queue's agent login/logout from presence (`CONSUME_PRESENCE` is declared
  as a capability code neither shipped adapter implements); the recut
  divergence check does not yet cover call facts the way it covers order
  facts. An operator can mark themselves on line, have a real inbound call
  (via the fake PBX) pop a caller's card before they answer, claim it, create
  an unknown caller as a customer with their number correctly prefilled, and
  see a missed call the next business day in the branch's call stats — the
  whole loop the exit criteria describe, minus the "real inbound call" itself.
- Date proposed: 2026-09-02
- Date decided: 2026-09-02
- Deciders: platform owner (direction, provider-neutrality, acceptance,
  2026-09-05 first-adapter resolution), Claude (architecture)
- Depends on: 0004, 0023, 0025, 0026, 0027, 0028, 0029, 0033, 0043, 0059
- Supersedes / Superseded by: —
- Open inputs: whether call audio is recorded and the legal posture if so
  (owner; not built, and this record does not decide it); numbering (owner;
  per-brand DIDs vs a shared line — until resolved, a VOICE installation with
  more than one active binding is served by whichever binding sorts first by
  priority, an installation-per-location topology rather than per-DID
  routing, per `VoiceInstallationLookup`'s own doc); the presence model's
  interaction with shift scheduling (owner). **Resolved 2026-09-05** (owner):
  which provider adapter to build first — the owner directed building both
  named adapter kinds now, a hosted SIP/PBX webhook and an Asterisk-class
  (self-hosted) event-socket client, rather than sequencing one ahead of the
  other. Neither has been verified against a real provider account, because
  the owner has not yet named a hosted vendor or supplied any credentials;
  both are proven only against the fake PBX this wave built.

## Context

Restaurants in this market take a meaningful share of orders by phone. The owner
directs that the platform later integrate IP telephony as a first-class channel,
with three concrete demands: staff can mark themselves **on line / paused** (and
the states between), an inbound call raises a **client card automatically**
instead of the operator searching by ear, and **tenant owners see telephony
statistics**. The platform already has the pieces this rides on: provider
installations with secret references (ADR 0026/0028), the operations app as the
staff surface, customer identity keyed by phone (ADR 0015), the conversations
model with an operator inbox (ADR 0059), audit (0027), and the reporting fact
layer (0043).

## Decision

- **Telephony is a provider-neutral core behind an adapter family, not a
  switch — and not a single integration.** (Owner-directed 2026-09-02: any
  number of providers must be integrable.) The platform never carries audio.
  The core owns a normalized call-event vocabulary — offered, answered, ended,
  missed, transferred, with caller number, line/DID, timestamps, and a
  provider-scoped call id for correlation — and everything downstream (the
  screen-pop, presence routing, ADR 0043 facts, call-to-order provenance)
  consumes only that vocabulary. Each PBX/provider is one adapter behind a
  `VOICE` category in ADR 0026's installations: the adapter translates that
  provider's webhooks or event-socket into core events and issues the
  provider's control calls where offered, authenticated per the installation's
  secret reference. Adapters declare their capabilities (can it consume
  presence for routing? does it support call control? push or poll?) the way
  provider capability reconciliation already models them, so the core degrades
  honestly per provider instead of assuming every PBX can do everything. No
  provider type, DTO, or `if (provider == X)` reaches the core — the same
  channel discipline payments and Telegram already enforce — and a tenant may
  hold multiple concurrent VOICE installations (a hosted PBX at one location,
  an Asterisk-class system at another).
- **Presence is a first-class operator state, channel-neutral.** An operator's
  availability (ONLINE / PAUSED / WRAP_UP / OFFLINE, with a reason on pause) is
  a small platform model owned by the operations surface — written by the staff
  themselves, audited (0027), readable by the routing adapter (so the PBX can
  skip paused operators where it supports it) and by the future ADR 0059 inbox
  assignment. It is deliberately not telephony-private: the same presence should
  eventually inform chat assignment.
- **The screen-pop is a lookup, not a guess.** On an inbound-call event the
  platform resolves the caller number against customer identity (ADR 0015's
  normalized phone; ADR 0029 rules on display) and pushes a client card into the
  operations app for the answering operator: name, recent orders, active order
  state, notes — the card the inbox and the order board already know how to
  render. Unknown numbers open a blank card with create-customer prefilled.
  Delivery to the browser uses the operations app's existing polling cadence
  first; if call-arrival latency demands push, that is the recorded revisit
  trigger for the deferred real-time gateway (ADR 0059's alternatives table) —
  not a license to build one prematurely.
- **Stats are ADR 0043 facts.** Call events land as reporting facts (offered,
  answered, missed, durations, per-operator, per-location, per-hour) through the
  same pipeline day-close uses; tenant owners read them in the reporting
  surfaces. No parallel stats store.
- **Order-taking stays the platform's**: a call that becomes an order is an
  ordinary operations-app order whose provenance records the call id — the same
  discipline every other channel follows.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| Softphone/WebRTC inside the operations app | Carries audio, codecs, and telephony reliability into this platform's scope for little pilot value; operators keep their handsets/softphones | A provider round demands it |
| Telephony-private presence | Would be rebuilt the day chat routing needs it; channel-neutral costs the same now | — |
| A stats dashboard fed by the PBX directly | Splits reporting truth across systems; ADR 0043 exists precisely to prevent this | Never |

## Implementation checklist

- [x] VOICE provider category, installation + secret reference, event ingestion with the inbox/outbox discipline
- [x] Operator presence model + operations-app control, audited, readable by adapters
- [x] Inbound-call resolution → client-card pop in the operations app (polling first)
- [x] Call facts into ADR 0043; owner-facing stats surface (a dedicated read endpoint under `REPORTING_READ`, not yet a signed `MetricRegistry` entry — see Implementation status above)
- [x] Call-to-order provenance
- [x] Fake PBX test double in the ADR 0007 genre (`FakeAsteriskAmiServer`, a real AMI-speaking TCP socket; the hosted-webhook side is proven by hand-constructing the normalized payload directly, since HorecaOS is the server on that side)

## Exit criteria

An operator marks themselves on line in the operations app; a real inbound call
to the pilot tenant pops the caller's card before the operator answers; a missed
call appears in the owner's stats with the operator roster of that moment; and a
phone order carries its call id from ring to fiscal receipt.
