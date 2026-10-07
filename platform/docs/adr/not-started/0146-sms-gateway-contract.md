# ADR 0146: SMS gateway contract

- Decision status: Accepted
- Implementation status: Not started — no SMS leaves the platform for any purpose
  other than a customer's sign-in code, and nothing records whether any SMS
  arrived. What exists is two unrelated paths and a table nothing fills.
  *Verification codes* go through a real adapter: `VasSmsGatewayAdapter`
  (`provider_type = SMSGW_VAS`, base URL approved by V0061 as
  `smsgw.vas.uz/api/v2`) behind `SmsGateway`, route `sms.verification.send.v1`,
  contract transcribed in `docs/providers/sms-gateway-vas.md`; it sends, it
  resolves an uncertain send through the provider's `/search`, and it ignores
  delivery receipts on purpose (`docs/routes/sms-verification.md`).
  *Notifications* (ADR 0020) go through `SmsGatewayAdapter`
  (`GENERIC_SMS`, `POST /provider/commands`, an `Idempotency-Key` header no real
  gateway documents), proved only against `FakeSmsGateway`; no approved provider
  environment carries `GENERIC_SMS`, so no production binding can use it, and
  `NotificationGateway` refuses a VAS binding on this route with
  `PROVIDER_ADAPTER_MISMATCH`. `NotificationGateway` also keys its adapters by
  channel alone (`Collectors.toMap(NotificationChannelAdapter::channel, …)`), so
  a second SMS adapter would fail at startup with a duplicate key.
  `notifications.delivery_status_events` (V0026: append-only, unique on the
  provider's event id, six normalised statuses) exists and is written only from
  the answer to a send or a status query (`NotificationDispatchService.record`),
  never from an inbound receipt. `CampaignMessagePort.isWired("SMS")`
  is false everywhere — the only implementation, `CampaignTelegramDeliveryService`,
  answers true for `MESSAGING_APP` alone — so an approved SMS campaign refuses to
  expand and a courier broadcast (`marketing.courier_broadcasts`, V0307) is
  refused at send with "No ADR 0020 delivery path is wired for SMS yet". Built
  and kept: template moderation state and its withholding (ADR 0091,
  V0204/V0208), consent and suppression (ADR 0020, ADR 0044), the segment
  estimator (`SmsSegments`), quiet hours.
- Date proposed: 2026-10-01
- Date decided: 2026-10-07
- Deciders: proposed by Claude (wave batch 17); Ayubkhon Abbosov (platform owner)
  decides
- Depends on: ADR 0005, ADR 0007, ADR 0020, ADR 0026, ADR 0028, ADR 0029,
  ADR 0033, ADR 0044, ADR 0091
- Supersedes / Superseded by: — (does not reopen ADR 0020's channel model or
  ADR 0091's moderation gate; replaces the *placeholder* contract inside
  `SmsGatewayAdapter`, which ADR 0020 itself called deliberately generic)
- Open inputs: each is closed on its proposed default if the owner accepts the
  record as written; those that name someone else stay with them.
  - **Which gateway accounts exist and what each may carry** (platform owner,
    business). Known: the VAS contract has been read and its endpoint approved.
    Not known: whether the pilot tenant holds an Eskiz or Playmobile account, the
    price per segment on any of them, and whether a VAS account may carry
    marketing and courier traffic as well as codes. Proposed default: VAS carries
    verification and transactional SMS; marketing and courier SMS stay refused
    until this is answered in writing.
  - **What a real VAS receipt looks like** (integration, with the provider).
    The document's example shows the callback's `key` arriving empty. Proposed
    default: the receipt endpoint ships behind Decision 4's weaker
    authentication only after one real callback has been captured against a
    controlled account. Until then VAS has no receipt source at all: a
    recipient's state stops at what the send and, for an uncertain send, the
    resolver reported ("handed to the operator"), and `NO_RECEIPT` is not
    derived, because with nothing listening the absence of a receipt says
    nothing. Capturing the callback is part of the controlled-account step in
    the rollout. If it cannot be captured or proves unusable, the "poll every
    message" row in the alternatives is reopened (its revisit trigger is then
    met) with a bounded design, as a new decision.
  - **Whether the provider signs callbacks, from which addresses it sends, and
    whether it retries** (integration). Unknown for VAS. Proposed default:
    assume none of the three and rely on the edge allowlist plus Decision 4's
    attempt-match rule.
  - **The Eskiz and Playmobile contracts** (integration). Neither has been read
    into this repository. Facts quoted below about them come from public client
    libraries and the parity matrix and are not a contract. Proposed default:
    each is transcribed under `docs/providers/` and given a route descriptor
    before any code, and neither is started before VAS receipts ship.
  - **Direction of VAS's `weight` priority** (integration). Proposed default:
    never sent, as today.
  - **Sender-name approval for marketing versus transactional** (business, with
    the gateway). Proposed default: one registered sender per tenant, and
    marketing sends only after its wording has passed ADR 0091's gate.
  - **How long without a receipt before a message is reported as "no receipt"**
    (product). Proposed default: 24 hours, a setting.

**To accept as written:** say "accept 0146". Every open input above is then
closed on its proposed default.

**Decision record, 2026-10-07.** Accepted by Ayubkhon Abbosov (platform owner) with the instruction "lets finish all" over every record still Proposed on this date. Every open input above is closed on the default this record proposes for it; an input that names a person other than the owner, or an external fact (a licence term, a provider capability, a tax treatment, an account that does not exist yet), stays with that owner as written and implementation proceeds without it, marking what waits. Implementation of what this record decides and has not yet built starts in operations batch 19 and 20 (2026-10-07).

## Context

Gap-map row `6.4a` says a marketer who chooses SMS gets a campaign that drafts,
estimates and passes approval and then cannot launch, and that per-recipient
delivery receipts "have nowhere to come from". Row `6.4b` says a dispatcher can
draft and target a courier SMS and the send is refused visibly. Both give the same
reason: "a real SMS gateway contract — `SmsGatewayAdapter` is deliberately generic
because 'no SMS contract exists yet'". Half of that is stale. A contract has been
read: VAS's, in full, five pages of it, and the platform sends customers' sign-in
codes through it. What is true is that the *notification* side never learned what
that read taught, and that nothing in the platform has an opinion yet about what
"a gateway contract" must say.

**What the VAS document teaches that the generic adapter assumed away.**

- *There is no idempotency key.* `/send` takes no key and the document does not say
  whether `seq` deduplicates. The generic adapter sends an `Idempotency-Key`
  header and its status route answers "I have never seen this key". A real gateway
  has neither. Uncertainty is resolved by *searching the destination for the day
  and comparing text* — the only correlator the API offers.
- *Credentials travel in every body.* A request body is therefore a secret; the
  generic adapter's rule that "the recipient and the body are on the request and
  nowhere else" is necessary and not enough.
- *The batch envelope lies.* `status.code = 0` can wrap per-message failures; each
  entry must be inspected, and `id: 0` means nothing was accepted.
- *Receipts are best effort.* CDMA subscribers produce none; absence is never
  evidence of failure. The callback authenticates with a `login`/`key` pair whose
  example shows `key` empty.
- *Cost is in segments, and the provider reports them* (`parts`). The platform
  estimates them (`SmsSegments`, ADR 0044) and never compares.
- *There is no sandbox.* Every example is a live account; pre-production runs
  against ADR 0007's controlled fake.

**What the platform already decided and this record keeps.** ADR 0020 owns intent,
consent, template, render, attempt and status; business modules never call a
provider. ADR 0007 says a send is never blindly retried and an uncertain send
resolves through the provider. ADR 0026 binds a tenant to an installation and a
provider type from a platform-approved catalogue (V0061 is that catalogue's one SMS
row). ADR 0028 makes the key a reference. ADR 0029 keeps the recipient and the
rendered body off rows: `notifications.notifications.rendered_content_hash` is a
SHA-256 of the rendered message "rather than the text". ADR 0091 withholds a
wording the gateway has not approved. ADR 0044 separates consent (legal
permission) from suppression (a deliverability fact, hard bounces included).

**Four structural facts make "add Eskiz" a larger change than it sounds.**

1. Adapters are keyed by channel, so the registry holds one SMS adapter. The
   second gateway is a startup failure, not a configuration.
2. The notification route requires the bound installation's `provider_type` to
   equal the wired adapter's, so a tenant bound to VAS for sign-in codes cannot
   send an order confirmation, and a tenant bound to anything else cannot send a
   sign-in code. Two purposes need two accounts today for no reason except that
   the code was written twice.
3. `CampaignMessagePort` is a single bean with a global `isWired(channel)`. SMS
   needs a second implementation, and "wired" must mean "this brand has a working
   SMS binding", not "this build has an adapter".
4. `queryStatus(String providerIdempotencyKey, …)` is the resolution contract, and
   VAS cannot answer it. The resolver needs the destination and the text, neither
   of which the notifications module keeps.

**What Eskiz and Playmobile are, as far as can be said.** The Delever parity
matrix lists both as SMS providers configured identically (login, secret, sender)
and ADR 0034 and ADR 0044 assume Eskiz by name. Public client libraries show Eskiz
as a token-authenticated API with single and batch send, a per-send `callback_url`,
a status lookup, a sender list, a balance and a template API; Playmobile as HTTP
Basic with a native `messages` array and push delivery webhooks. That is enough to
say the *shapes differ* — token versus body credential, push versus pull, a message
id on send versus none — and not enough to build either.

## Decision

**Generalise the contract the platform has actually read, make one adapter per
gateway serve every SMS purpose, and treat delivery receipts as authenticated,
monotonic, best-effort evidence that never causes a resend.**

1. **VAS is the first gateway; the contract is its generalisation.** One
   commercial relationship carries sign-in codes and transactional notifications
   from the first release. Marketing and courier SMS wait on the owner's answer to
   the first open input. Eskiz and Playmobile conform to the same contract later,
   each after its documentation is transcribed.

2. **The contract has six obligations**, stated once so each adapter and each
   route descriptor can be reviewed against them:
   - `send`: one message; returns `ProviderOutcome` and, when the provider gives
     one, its message id (`external_message_id`, already on the attempt).
   - `resolve`: given what the platform has — the provider id if known, else the
     destination and the day — answer *sent, not sent, or unknown*, never by
     sending. Unknown is a first-class answer.
   - `receipt`: normalise a provider status into the six existing
     `normalized_status` values, keeping the provider's word verbatim in
     `provider_status`.
   - `segments`: report the segments the provider billed, when it says.
   - `account`: say whether the installation is configured (login, sender,
     credential reference) without calling out, so a missing sender is a
     configuration finding and not a customer-facing error.
   - `taxonomy`: a table, in the provider's `docs/providers/` file, from every
     provider code to an ADR 0007 outcome and a bounded reason code. A code not in
     the table is `UNCERTAIN`, never `SUCCESS`.

3. **One adapter per gateway, registered by `(channel, providerType)`.** The
   registry stops keying by channel. The VAS adapter implements the notification
   interface and the verification entry point over the same HTTP client, code
   table and credential handling, so a single `SMSGW_VAS` binding serves both. The
   generic adapter and its `/provider/commands` shape move to test scope as the
   controlled fake ADR 0007 asks for; it is no longer a production bean.

4. **Receipts are accepted by an endpoint that can only advance what we sent.**
   `POST /providers/sms/{installationId}/receipts` is `permitAll` on the filter
   chain and authenticates inside the endpoint, as the Click and Telegram
   endpoints do. A receipt is applied only if (a) it carries the provider's
   message id (or, for a gateway that echoes ours, our reference) of an attempt
   made under that installation, (b) it advances the attempt's state and never
   regresses a terminal one, and (c) it passes whatever authentication the
   gateway offers: a per-installation callback secret held as an ADR 0028
   reference and compared in constant time where the provider can carry one, and
   otherwise the provider's published source addresses allowed at the edge. A
   receipt for an unknown id is dropped and counted; a callback creates no data.
   Receipts are written through the ADR 0005 inbox, so a duplicate is a no-op, and
   into `delivery_status_events` with a synthesised event id where the provider
   gives none. Until a real VAS callback has been captured (open input 2), the
   endpoint answers 404 for VAS and no receipt is read for it by any means;
   Decision 5 says what a recipient shows meanwhile.

5. **Absence of a receipt is a state, not a failure, and only where a receipt can
   arrive.** For a provider type whose receipt endpoint is enabled (Decision 4), an
   attempt the provider accepted and never reported on becomes `NO_RECEIPT` after
   the configured window and is reported as "handed to the operator, no receipt".
   For a provider type whose endpoint is not enabled, which is VAS until a real
   callback has been captured, no attempt becomes `NO_RECEIPT`: the sweeper skips
   the type, and the attempt stays as the send answered it ("accepted" and "handed
   to the operator"). Nothing resends on either. A pull sweeper runs only for
   attempts the provider reported as unknown or that are uncertain, because every
   pull decrypts a number (ADR 0029, a recorded purpose) and chasing "delivered"
   for every message would turn a best effort signal into a standing PII workload.
   When that sweeper resolves an uncertain attempt, the gateway's answer carries the
   message's own state (`/search` returns `status`, which
   `VasSmsGatewayAdapter.found` already reads), and that state is recorded as a
   status event like any other. It is a by-product of resolving, not a source of
   receipts, and it covers only the attempts that needed resolving.

6. **A hard bounce becomes a suppression, narrowly.** A receipt or search result
   that says the receiver is blacklisted or the number unroutable writes an ADR
   0044 suppression for that destination's lookup hash with a bounded reason. It
   is a deliverability fact, not consent, and it can only be raised by a receipt
   that matched an attempt of ours.

7. **Cost is compared, not assumed.** The segments the provider reports are
   stored on the attempt and checked against `SmsSegments`' estimate; a mismatch
   is a metric, because the estimator is the thing a tenant's campaign ceiling is
   enforced against.

8. **Campaigns and courier broadcasts use the same path.** `CampaignMessagePort`
   becomes one router over per-channel delivery services (Telegram exists; SMS is
   added), and `isWired` takes the scope: a channel is wired for a brand when that
   brand has an active binding whose provider type has an adapter and whose
   sender is configured. The courier broadcast's refusal reads the same answer.
   Marketing SMS to a number requires, unchanged, consent and no suppression; the
   gateway never relaxes either.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| Eskiz first, because it is the market name and Delever's default | No Eskiz contract has been read, and a first adapter written from a client library encodes a guess. VAS is in hand, tested, and already carries sign-in | A pilot tenant's only account is Eskiz, or the owner supplies the Eskiz documentation first |
| Keep the generic adapter and map vendors onto it with configuration | It is a request shape nobody agreed. A mapping layer cannot express body-borne credentials, a `search` resolver, a batch envelope that lies, or receipts, so it grows into an adapter DSL | Never |
| Two adapters per gateway, one for codes and one for notifications (today) | Duplicates the code table and credential handling, and forces two accounts for one gateway because the route compares provider types | Never |
| Accept VAS callbacks on `login` alone, as the document implies | `login` is an account name, not a secret; anyone who guesses it could mark any message delivered or blacklisted. The route descriptor rejects it for this reason | A real callback is captured and carries something verifiable |
| Retry a message the receipt says failed | A retry is a second message and a second charge; a failed receipt is often terminal (blacklist, bad number) | A gateway documents idempotent resend or a failure class that is provably pre-send |
| Mark absent receipts as failed after a timeout | CDMA subscribers never produce one; it would report successful deliveries as failures and trigger the retry above | A gateway guarantees a receipt for every message |
| Poll the provider for every message's final state | Each lookup decrypts a number; at pilot volumes that is thousands of purpose-recorded decrypts per day for a signal the provider offers by push | A provider offers neither push nor a bulk status read |
| Read final state by `/search` for every accepted VAS message until a callback exists | The same standing PII workload as the row above, for a window the record cannot date, and every `/search` returns the text of each message sent to that number that day. It is the honest fallback if the callback cannot be captured, and then it is that row's revisit trigger | The callback cannot be captured or proves unusable, or the owner decides per-recipient delivered state is worth the cost before then; the design is then one lookup per message after a settle delay, audited |
| An SMS aggregator abstraction from outside the region | Domestic gateways register sender names with the operators and are paid in UZS; ADR 0034 treats them as the domestic processors | A tenant sells outside Uzbekistan |
| Build email and push contracts in the same record | Row `6.4a` names them, but they have no provider named at all (email has the ADR 0097 SMTP relay for staff mail only) and a different failure model | A provider is named for either |

## Consequences

### Positive

- A tenant needs one gateway account for sign-in codes and order messages, and
  the platform stops carrying a placeholder contract nobody can bind.
- Rows `6.4a` (SMS) and `6.4b` can launch the moment the owner answers which account
  may carry them. A recipient shows "handed to the operator" from the first
  message, and delivered, failed or "no receipt" once the receipt endpoint is
  enabled for the gateway.
- A second gateway becomes an adapter, a catalogue row, a transcription and a
  route descriptor, not a startup failure.
- The hard-bounce suppression ADR 0044 already models gets its first producer.

### Negative

- Receipt authentication is weaker than the platform's usual standard on
  gateways that cannot carry a secret; the compensating controls (edge allowlist,
  attempt match, monotonic updates, no data creation) limit the damage of a
  forgery to a wrong status on one of our own attempts, and a forged blacklist
  receipt could suppress a customer's marketing SMS.
- Until a real VAS callback has been captured and the endpoint enabled, a campaign or
  courier broadcast over SMS can say only "handed to the operator" per recipient,
  successful deliveries included, and the record cannot date the capture: it needs
  a controlled account and the provider's cooperation, and it names neither a date
  nor an owner for them. That is the price of not polling every message, accepted in
  Decision 5; open input 2 says what reopens it.
- Resolving an uncertain send by text comparison needs the rendered text at
  resolution time, and the platform keeps only its hash. The comparison is
  therefore hash against hash, which assumes the gateway returns the text
  byte-for-byte; a gateway that normalises whitespace or truncates turns a sent
  message into "unconfirmed".
- A router and a scoped `isWired` change the marketing port's signature and every
  caller of it.
- The first release serves exactly one gateway, so the contract is generalised
  from one example. Eskiz or Playmobile will find the places it generalised
  wrongly.

### Accepted trade-offs

- Marketing SMS is not unblocked by this record; it is unblocked by an answer
  from the owner and the gateway.
- "No receipt" is reported honestly and will look worse than a number that
  pretends otherwise.
- A provider outage or a lost response costs one fresh message to the customer,
  never a duplicate, because uncertainty never resends.

## Specification

### The registry and the interface

```text
NotificationChannelAdapter  (extended)
  providerType(), channel()
  send(dispatch, call)         -> ProviderOutcome  (+ externalMessageId, segments?)
  resolve(attempt, call)       -> Sent | NotSent | Unknown      replaces queryStatus's key lookup
  normalise(rawReceipt)        -> ReceiptEvent(providerMessageId, normalisedStatus, providerStatus, occurredAt?)
  describeAccount(binding)     -> AccountReadiness(complete, missingFields[])

NotificationGateway   adaptersByChannelAndType : Map<(channel, providerType), adapter>
```

`resolve` takes the attempt's provider id when it has one and otherwise the
destination (decrypted for this call only, purpose recorded) and the
`rendered_content_hash` to compare against. It never throws for a provider
failure and never sends.

### VAS status mapping (from `docs/providers/sms-gateway-vas.md`)

| VAS code | Meaning | `normalized_status` | Terminal |
|---|---|---|---|
| 0 | Created | `ACCEPTED` | no |
| 1 | Sending | `DISPATCHED` | no |
| 3 | Sent (handed to the operator) | `DISPATCHED` | no |
| 4 | Delivered | `DELIVERED` | yes |
| 2 | Fail | `FAILED` | yes |
| 5 | Rejected | `FAILED` | yes |
| 7 | InBlackList | `FAILED`, plus a suppression (Decision 6) | yes |
| 6 | Unknown | `UNKNOWN` | yes, unresolved |

`NO_RECEIPT` is not a provider status. It is derived (Decision 5) and stored
on the attempt, not as a status event.

### Persistence (additive; numbers reserved by the wave that builds it)

```text
notifications.delivery_attempts   (columns added)
  provider_segments  integer null      -- what the provider billed; null = not reported
  receipt_state      varchar(16) null  -- NO_RECEIPT | null; derived, set by the sweeper
integration.provider_environments     one row per approved gateway endpoint (V0061 pattern)
integration.installations             callback secret = an ADR 0028 reference, not a column
```

Grants as `V0035`. `delivery_status_events` is unchanged and stays insert-only.
Retention follows ADR 0020's open input.

### Endpoints (ADR 0031)

```text
POST /providers/sms/{installationId}/receipts       permitAll on the chain; authenticated inside;
                                                    answers 404 for a provider type without receipts
GET  /api/v1/operations/tenants/{tenantId}/brands/{brandId}/campaigns/{campaignId}/recipients
                                                    already exists; the response gains the receipt
                                                    state and the segments billed
```

No new staff capability: the recipients read keeps its existing `campaign.author`
declaration at brand scope.

### Observability

Counters `horecaos.sms.receipts` (provider, outcome: `applied`, `duplicate`,
`unknown_message`, `regressed`, `unauthenticated`), `horecaos.sms.segments.mismatch`,
`horecaos.sms.no_receipt`. Labels are bounded enums; no number, no text, no message
id. The access log for the receipts path records no body.

### Testing

- Contract suite per adapter against the controlled fake: accepted-then-lost
  reply resolved by `resolve` without a second send; a batch envelope with a
  per-message failure; a code missing from the taxonomy is `UNCERTAIN`; `13 wrong
  key` refreshes the secret once and does not resend.
- Receipts: a forged id is dropped; a duplicate is a no-op; an out-of-order
  `DELIVERED` then `SENT` does not regress; a callback with no matching attempt
  creates nothing; a blacklist receipt for another tenant's attempt is ignored.
- `NO_RECEIPT`: with the receipt endpoint not enabled for VAS, an accepted attempt
  stays "handed to the operator" however old (a controlled clock); with it enabled,
  an attempt with no receipt after the window becomes `NO_RECEIPT`, and one with a
  receipt does not. The sweeper never touches a provider type without receipts.
- The registry accepts two SMS adapters; a VAS binding sends an order
  confirmation (the case that returns `PROVIDER_ADAPTER_MISMATCH` today).
- `CampaignMessagePort`: a brand with a binding is wired for SMS and one without
  is not; the courier broadcast sends to a fake and records the count.
- No number, body or credential reaches a log appender on any path (the ADR 0029
  canary pattern).

## Rollout and rollback

Registry and adapter first: VAS carries the order-confirmation SMS to the fake and
then to one controlled account, with the generic adapter demoted. At that step the
provider is asked to send callbacks for the controlled account (the document does not
say how a callback address is registered, so that is the first question to put to
them) and the first real callback is captured and transcribed into
`docs/providers/sms-gateway-vas.md`. Then the scoped
`isWired` and the router, which makes row `6.4b` testable end to end. Then pull
resolution of uncertain sends. Then the receipt endpoint, once a real callback has
been captured, and in the same release the `NO_RECEIPT` sweeper for the provider
types whose receipts it enables. Marketing SMS last, behind the owner's answer; it
does not wait on receipts, and its recipient view says "handed to the operator"
until they exist. Each step is inert until a binding of the right provider type
exists. Rollback is
unbinding: the channel returns to refused with a visible reason, and no message
is queued behind it.

## Implementation checklist

- [ ] Owner answers (or accepts the defaults for) the open inputs above.
- [ ] Registry keyed by `(channel, providerType)`; duplicate-channel startup
      failure removed and tested.
- [ ] VAS adapter implements the notification interface; one binding serves codes
      and notifications; `GENERIC_SMS` moved to test scope.
- [ ] `resolve` by provider id, else destination and hash; `normalise`;
      `describeAccount`.
- [ ] Flyway: `provider_segments`, `receipt_state`; granted.
- [ ] The unknown-state pull sweeper (it records the state `/search` returns as a
      status event).
- [ ] The `NO_RECEIPT` sweeper, with the window as a setting, enabled per provider
      type together with its receipt endpoint and skipping every type without one.
- [ ] The first real VAS callback captured on the controlled account and
      transcribed.
- [ ] Receipt endpoint, constant-time secret check where available, edge
      allowlist entry in `deploy/infra/caddy/Caddyfile`, inbox write; enabled only
      after a captured VAS callback.
- [ ] Suppression writer for blacklist receipts.
- [ ] `CampaignMessagePort` router and scoped `isWired`; SMS delivery service;
      courier broadcast reads the same.
- [ ] Update `docs/providers/sms-gateway-vas.md`, `docs/routes/sms-verification.md`
      and `docs/routes/notification-send.md`; add a route descriptor for receipts.
- [ ] Eskiz and Playmobile transcriptions under `docs/providers/` (separate,
      later).
- [ ] Update ADR 0020's status line (the first real provider) and ADR 0044's.
- [ ] Tests listed under Testing, each seen failing first.

## Exit criteria

A tenant bound to the VAS gateway receives an order confirmation by SMS on the
same binding that carries its customers' sign-in codes; the campaign recipient row
shows "handed to the operator" for every message that left and, once the receipt
endpoint is enabled for the gateway, delivered, failed, or "no receipt"; a courier
broadcast reaches a controlled account and records its count; a second gateway
can be registered without touching the first; and no message is ever sent twice
because a response was lost.

## References

- ADR 0005, ADR 0007, ADR 0020, ADR 0026, ADR 0028, ADR 0029, ADR 0033, ADR 0034
  (processors), ADR 0044 (SMS cost, suppression), ADR 0091
- `platform/docs/operations-gap-map.md` rows `6.4a`, `6.4b`
- `platform/docs/providers/sms-gateway-vas.md`, `sms_gate_doc_v4.4.pdf`;
  `platform/docs/routes/sms-verification.md`, `notification-send.md`
- `platform/docs/delever-parity-matrix.md` (Eskiz and Playmobile provider rows,
  provider-preapproved templates)
- `SmsGatewayAdapter`, `VasSmsGatewayAdapter`, `SmsGateway`, `NotificationGateway`,
  `NotificationChannelAdapter`, `CamelNotificationTransport`,
  `CampaignMessagePort`, `CampaignTelegramDeliveryService`,
  `CourierBroadcastService`, `SmsSegments`; `V0026`, `V0061`, `V0204`, `V0208`,
  `V0307`; `SecurityConfiguration` (the Click and Telegram `permitAll` pattern)
