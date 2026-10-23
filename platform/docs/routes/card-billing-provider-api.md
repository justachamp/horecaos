# Route descriptor: `cardbilling.provider-api.v1`

Required by ADR 0007. A production route may not ship without one of these; see
`docs/routes/README.md` for the format. This is the route behind ADR 0095's card-provider adapter
(Click's card-token calls, `docs/providers/click-card-token.md`): the only way HorecaOS code talks to a
card provider for **its own** billing account. The tenants' customers' payments are a different route
(`payment-merchant-api.md`) with a different owner and different credentials.

| Field | Value |
|---|---|
| Route IDs | `cardbilling.provider-api.v1`, `cardbilling.provider-api.dead-letter` |
| Version | 1 |
| Owning module | `integration` (route, gateway and the Click adapter); commanded by `commercial`, which owns the port `CardProviderAdapter` (in `commercial.api`), decides what is charged and records what became of it. `commercial` never speaks a provider's protocol and a provider's types never leave `integration.camel.cardbilling` |
| Owner | Ayubkhon Abbosov (platform architecture) |
| Input contract | `CardBillingApiCall` v1 (in-process command record), built by a `CardProviderAdapter` from the provider-neutral `CardAccount` and the adapter's own port arguments |
| Output contract | `ProviderOutcome` v1, which the adapter reads into `CardEnrolment.ConfirmOutcome` / `RevokeOutcome`, `CardCharger.Outcome` / `StatusOutcome`, or a `CardChargeUnansweredException` for an attempt it cannot say anything final about |
| Source | `direct:cardbilling.provider-api` |
| Destination | A card provider's HTTPS API: Click (`api.click.uz`, root `/v2/merchant`). Selected by the ADR 0095 platform card installation (`commercial.platform_card_installations`) and bounded by the environment's `egress_allowlist`: the gateway refuses any host that environment does not name, before the secret is resolved. **No Click merchant account exists yet**: no installation is seeded, the adapter is never called until one is declared and activated, and until then every card tenant is collected like an invoice tenant |
| Service identity | HorecaOS's own merchant account, one per provider (a platform installation, no tenant). The credential is one JSON object, `{"merchantUserId": "...", "secretKey": "..."}`, behind the secret reference; the account's public `service_id` is the installation's `external_account_reference` |
| Secret reference type | `horecaos:{env}:provider_payment:platform:{id}` (ADR 0028), written by an operator with `bao kv put`; never through the API. The adapter also derives its enrolment-session signing key from the same secret, so no second secret is stored |
| Connect timeout | 5s |
| Total timeout | 30s per call (the gateway default). A card authorisation can take its time; a timeout is a reconciliation, not a retry, so waiting a little longer for an answer that would settle the question is cheaper than giving up on one |
| Retry classification | **None in-route, on purpose.** `card_token/payment` has no idempotency key, so a bounded redelivery of a charge would be a bounded number of extra charges. The route classifies and returns. The adapter decides: a 4xx is a refusal and is never retried; anything that may have reached Click on a charge (a timeout or reset after the request was written, a 5xx, an unreadable body) is a `CardChargeUnansweredException` and the attempt stays `PENDING`; the one repeat the gateway makes is a read of the credential past the cache after a 401, because a 401 means Click refused before it acted |
| Idempotency key | **None documented by Click.** The port's key (the id of the `commercial.card_top_ups` or `commercial.card_charge_attempts` row, committed before the provider is asked) is sent as the charge's `transaction_parameter`, and the adapter keeps the "one key is one charge" contract itself: before any charge it reads `payment/status_by_mti` for the key over today and the days before it (the installation's `lookbackDays`, default 2) and charges only a key Click provably holds nothing under. A payment found paid is returned as the attempt's answer with no second charge; one still processing, or a lookup that cannot be read, is `PENDING` |
| Circuit breaker | Sliding window 10, minimum 5 calls, 50% failure rate, 30s open, 3 half-open probes, **one breaker per installation** (HorecaOS's account with one provider). Only `RETRYABLE` counts as a failure; a decline, a refusal or an unknown outcome is not evidence the provider is down |
| Dead-letter destination | `cardbilling.provider-api.dead-letter` produces an `UNCERTAIN` `UNCLASSIFIED` outcome (not retryable: there is no evidence Click was untouched), which the adapter turns into `CardChargeUnansweredException`, and the platform records on the attempt, which stays `PENDING` until `status` answers |
| PII classification | A card token (a reference into Click's vault, never logged), a last four and a masked number Click returns (the last four is kept, the rest dropped on the spot), an amount, and HorecaOS's own attempt id. **The card number never reaches HorecaOS:** the cardholder's browser sends it to Click's `card_token/request` and this route never carries it. The body is never logged at any level, the MDC holds the provider type and correlation id only, no installation id, token or payment id is a metric tag, and a failure reason is a code (`CLICK_ERROR_n`, `PAYMENT_IN_PROCESSING`) and never Click's free text |
| Expected volume | A card top-up is the tenant's act and a CARD tenant is charged once per monthly statement: tens a month at pilot scale. Each charge costs up to `lookbackDays + 1` status reads before it and one after |
| SLO | A charge answers within the 30s total timeout or stays `PENDING` and is resolved by the settlement sweep within minutes (`WalletCardSettlementSweeper`, every five minutes). Nothing in the platform depends on the provider answering inline |
| Runbook | `docs/routes/card-billing-provider-api.md#runbook`, and `docs/runbooks/connect-card-merchant-account.md` for connecting the account |
| Dashboard | Metrics `horecaos.cardbilling.route` (tags `event`, `provider`, `operation`, `status`) and `horecaos.cardbilling.circuit.not_closed` -- none labelled by installation, tenant, card or payment |

## What the platform concludes after a call

| Call | Click answer | What the adapter returns |
|---|---|---|
| Verify (`confirm`) | `error_code` 0 and a masked number | `Enrolled` with the card token as the reference to charge, the last four from the mask, the scheme where the unmasked digits name one, and the expiry the cardholder entered |
| Verify | `error_code` not 0 | `Refused("CLICK_ERROR_n")`; the session survives so the cardholder can retry |
| Verify | transport failure, 5xx, unreadable | `Refused("PROVIDER_UNAVAILABLE")`; nothing is on file |
| Verify | credential refused (401) or no usable account | `Refused("PROVIDER_NOT_CONNECTED")` |
| Charge | a payment found paid under the key, or `error_code` 0 and `payment_status` 2 | `Succeeded(payment_id)`; a found payment is not charged again |
| Charge | `error_code` not 0 and no payment | `Failed("CLICK_ERROR_n")` |
| Charge | an HTTP 4xx | `Failed("PROVIDER_REJECTED")` |
| Charge | `payment_status` negative on a found payment | `Failed("PAYMENT_DECLINED")` |
| Charge | processing (`0`, `1`, anything undocumented), a 5xx, a timeout, a lost answer, an unreadable body, a lookup that cannot be read | `CardChargeUnansweredException`; the attempt stays `PENDING` |
| Charge | no credential, an unapproved host, or the credential refused | `NotConfigured` (final for a fresh attempt; for one already asked the caller leaves it `PENDING`) |
| Status | a payment found paid | `Succeeded(payment_id)` |
| Status | anything else | `NotSucceeded`, which the caller answers by replaying the charge, which asks again first |
| Revoke | `error_code` 0 | `Revoked` |
| Revoke | anything else | `Failed(reason)`; the reference is already gone from HorecaOS either way |

## Runbook

**"Card payments are not available" (`CARDS_NOT_AVAILABLE`) for every tenant.** No installation is
`ACTIVE`, or the active one names a provider type this build has no adapter for, or a test double
outside a local run. Control plane > Billing setup lists the installations. Nothing is charged and no
card is added until one is active; a CARD tenant is collected like an invoice one.

**A top-up stays `PENDING` for more than a few minutes.** Click did not answer, or answered "processing".
The settlement sweep asks `status` for the same key first and records the money if it was taken; it never
charges a second time while Click can be asked. If it stays `PENDING` for a day, compare Click's cabinet
with `commercial.card_top_ups` by row id, which is the `transaction_parameter`.

**`PROVIDER_NOT_CONNECTED` on a new card, or every charge ending `NOT_CONFIGURED`.** The credential is
refused (401) or missing. Rotate it at Click, `bao kv put` the new JSON under the same reference, and try
again; no identifier changes. A refused credential never marks a card declined.

**`PAYMENT_LOOKUP_UNAVAILABLE` on every charge.** Click answered the status read in a way the adapter will
not read as "nothing held": a non-zero `error_code` that is not in the installation's `notFoundErrorCodes`.
This is the safe direction. Read the code Click returns for an unknown `merchant_trans_id` in the sandbox
and declare it on the installation's configuration; until then no charge is sent.

**A charge older than the lookback.** An attempt stranded for longer than `lookbackDays` + 1 days (a
suspension, an outage) is read as "nothing held" and would be charged again. Before a suspension that may
last, declare a wider `lookbackDays` (up to 31) on the installation; the unresolved-charges guard on
suspension (`UNRESOLVED_CARD_CHARGES`) exists for the same reason.

**The circuit is open.** `horecaos.cardbilling.circuit.not_closed` is above zero. Click is failing; charges
and enrolments answer "unavailable" and stay `PENDING` without calling. It half-opens after 30 seconds on
its own.
