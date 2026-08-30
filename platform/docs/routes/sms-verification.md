# Route descriptor: `sms.verification.send.v1`

Required by ADR 0007. A production route may not ship without one of these; see
`docs/routes/README.md` for the format. The provider is transcribed in
`docs/providers/sms-gateway-vas.md`, and where this file and that one disagree,
that one wins.

| Field | Value |
|---|---|
| Route IDs | `sms.verification.send.v1`, `sms.verification.search.v1`, `sms.verification.dead-letter` |
| Version | 1 |
| Owning module | `integration` (route, gateway and adapter), commanded by `customers` through `customers.spi.VerificationCodeTransport` (ADR 0015) |
| Owner | Ayubkhon Abbosov (platform architecture) |
| Input contract | `SmsVerificationOperation` v1 (in-process command record), built from `VerificationCodeTransport.VerificationMessage` v1 |
| Output contract | `ProviderOutcome` v1, narrowed to `VerificationCodeTransport.Outcome` v1 at the transport |
| Source | `direct:sms.verification.send`; the resolver is entered at `direct:sms.verification.search` |
| Destination | `smsgw.vas.uz/api/v2` over HTTPS — `POST /send` and `POST /search` — selected by the ADR 0026 binding that holds `SEND_SMS` **and** declares `provider_type = SMSGW_VAS` |
| Service identity | The tenant's own partner account on the gateway: `login` from ADR 0026 configuration, `key` from ADR 0028. There is no service account and no token exchange; the credential travels in the body of every request |
| Secret reference type | `horecaos:{env}:provider_notification:{owner}:{id}` (ADR 0028), resolved at call time, refreshed once past the cache on `13 wrong key` |
| Connect timeout | 5s (`ProviderHttpClient`). A connect-phase failure is the only one that proves nothing left this process |
| Total timeout | 15s per call, applied to the whole exchange including the body, and used for both operations. The provider documents no timeout and does not say whether a request that times out was accepted, which is why the uncertain path below is real rather than theoretical |
| Retry classification | **None on the send, ever.** `/send` has no idempotency key, so a redelivery is a second SMS to a real person. The one exception is a `13 wrong key`, which is the provider answering *instead of* sending, and which is retried exactly once after a fresh ADR 0028 read. `sms.verification.search.v1` retries twice at 1s doubling, because a search creates nothing. `1 spam` and `27 server side error` are classified but **not** retried on a timer here — see the outcome table |
| Idempotency key | **None. The provider documents none anywhere** — not on `/send`, and `seq` on `/send_msgs` is echoed without any statement that it deduplicates. No `Idempotency-Key` header is sent either, because a header the provider ignores is read as a guarantee by the next person. Uncertainty is resolved by `/search` instead |
| Circuit breaker | **None.** The same reason the notification route gives, sharpened: a breaker here would stop *sign-in* for every tenant sharing the trip, and the call is already bounded by a 15s deadline and by the customer's per-destination issuance budget. There is also nothing to fail over to — one gateway is bound. Revisit when a second gateway exists |
| Dead-letter destination | `sms.verification.dead-letter` → an `UNCERTAIN` outcome, which the transport reports as `UNAVAILABLE`, which makes `CustomerVerificationService` withdraw the challenge. Nothing durable is queued: a code nobody can prove was sent is not work to retry later |
| PII classification | The destination is a phone number and the text **is** the live one-time code (ADR 0029, ADR 0015). `VerificationMessage`, `SmsVerificationOperation` and both `SmsGateBody` records override `toString` to print neither, because Camel writes exchange bodies into route logs and into the messages of the exceptions it wraps. The request body is never logged at any level on any path. `/search` returns the message *text*, so its response is handled in memory and only a message id and a delivery-state name are ever put on an outcome |
| Expected volume | Pilot: under 2,000 codes/day/tenant, bounded above by five per destination per hour and six issuances per caller per minute |
| SLO | p95 under 3s for a send, under 2s for a search. A customer is looking at a form while this runs |
| Runbook | `docs/routes/sms-verification.md#runbook` |
| Dashboard | Metric `horecaos.sms.verification.calls`, tagged `step` (`send`, `resolve`, `dead_letter`), `status`, `reason` — all three bounded by the enums that produce them |

## Why there is no retry and what replaces it

`/send` takes a `login`, a `key`, a `sender`, a `phone` and a `text`. It takes no
key we could repeat it under, and the document says nothing about deduplication.
A redelivery is therefore not "the same request again", it is a second SMS to a
real person's phone and a second charge — and on a verification code it is worse
than that, because the customer now holds two messages and one dead challenge.

What replaces it is `/search`, which answers by destination for a day and returns
what was sent, text included. An uncertain send branches straight into it in the
same exchange, and the message we are looking for is the one whose text carries
the code we were trying to send — the only correlator this API offers. That
comparison happens in memory in `VasSmsGatewayAdapter` and neither side of it is
written anywhere.

**Not finding it is not proof it was never sent.** The `date` parameter names a
day in a timezone the document does not state, so a message either side of a
boundary can be absent from a search that is working perfectly. The answer is
`SMS_SEND_UNCONFIRMED`, the challenge is withdrawn, and the customer asks again
and gets a *fresh* code. That is one wasted SMS in the bad case, which is the
trade `VerificationCodeTransport` documents itself as accepting.

## Why the delivery-receipt callback is not built

The provider POSTs delivery status to an endpoint we host, authenticating with
the same `login`/`key` pair — and **the document's own example shows `key`
arriving empty**:

```json
{ "login": "admin", "key": "", "id": 555555, "code": 4, "description": "DLVRD" }
```

That leaves two options and both are wrong today. An endpoint that accepts the
callback on `login` alone is unauthenticated in everything but name: `login` is
an account name, not a secret, and anyone who guesses it could mark any message
id delivered, failed, or blacklisted. An endpoint that requires a non-empty `key`
would reject every real callback if the example is accurate — dead code that
looks live, which is the failure mode this platform's guards exist to prevent.

Nothing in the send path needs it, either. CDMA subscribers produce no callback
at all, so absence of a receipt is never evidence of failure, and no logic here
may conclude "not delivered" from silence. The real feedback signal for a
verification code is the customer entering it.

**So the send path ships alone.** The endpoint is built after a real callback has
been observed against a controlled account, and what it authenticates on is
whatever actually arrives — verified, not assumed. Until then
`SmsGateDeliveryState` is read only from `/search`, on a call we make, over a
connection we authenticated.

## Outcome policy

Provider codes are in `docs/providers/sms-gateway-vas.md`; the mapping lives in
`SmsGateCode` and this table says what the route does with the result.

| Outcome | Reason code | What happens | Why |
|---|---|---|---|
| `0 success` with an `id` | — | Accepted | The gateway took it |
| `0 success` with no `id` or `id: 0` | `SMS_ACCEPTED_WITHOUT_ID` | Uncertain → `/search` | The bulk response uses `id: 0` for "nothing was accepted", so a success without one contradicts itself |
| `20 receiver in blacklist` | `SMS_RECEIVER_BLACKLISTED` | Refused, with its own code | **A product fact, not an error.** This customer can never receive a code and therefore can never sign in by phone. It reaches the storefront as an ADR 0031 problem property so a person can be told, rather than being folded into "try again" |
| `13 wrong key` | `PROVIDER_AUTHENTICATION` | One fresh ADR 0028 read, then refused and logged at ERROR | The key was rotated in the provider's console and not in OpenBao. There is no rotation API, so nothing but a person fixes this, and retrying cannot |
| `1 spam` | `SMS_SPAM_LIMIT` | Refused, logged at ERROR, **no backoff** | The provider allows 50/hour per number per partner; our own OTP budget is 5. This code is unreachable by a working limiter, so it means the limiter is broken or something else shares this account. A delay would turn the alarm into patient background traffic |
| `16 wrong sender` | `SMS_SENDER_NOT_REGISTERED` | Refused, logged at ERROR | There is no sender-registration API. Every code for this tenant fails until somebody fixes the configuration |
| `10`, `11`, `12`, `15`, or missing account config | `SMS_ACCOUNT_MISCONFIGURED` | Refused, logged at ERROR | Tenant-wide, and no amount of waiting fixes it |
| `17 phone pattern`, `21 unknown operator` | `SMS_DESTINATION_UNROUTABLE` | Refused | The destination, not us. The same answer on a second attempt |
| `19 text too long` | `SMS_TEXT_TOO_LONG` | Refused, logged at ERROR | Our template grew past a segment. A code fault |
| `27 server side error` | `SMS_PROVIDER_ERROR` | Retryable classification, no automatic retry | The document calls it retryable; the route still does not repeat a send, and the customer asking again is the retry |
| Lost, timed-out or unreadable response | `READ_TIMEOUT`, `RESPONSE_UNREADABLE`, `SMS_RESPONSE_UNREADABLE` | Uncertain → `/search` | The provider may have sent. Never resend |
| `/search` finds it, state `7 InBlackList` | `SMS_RECEIVER_BLACKLISTED` | Refused | Same product fact, learned late |
| `/search` finds it, state `2 Fail` / `5 Rejected` | `SMS_DELIVERY_FAILED` | Unavailable | The gateway took it and states it will not arrive |
| `/search` finds it, state `0`/`1`/`3`/`4`/`6` | — | Accepted | `3 Sent` is handed to the operator and `6 Unknown` is terminal-and-unresolved. Neither is a failure, and CDMA subscribers never report anything better |
| `/search` finds nothing, or itself fails | `SMS_SEND_UNCONFIRMED`, `SMS_SEARCH_REFUSED` | Unavailable; challenge withdrawn | Not proof of absence — the day's timezone is undocumented — so the customer gets a fresh challenge, never a repeat of this one |

## Runbook

**Every code for one tenant fails, others are fine.** This route has no circuit
breaker by design, so an outage here is per binding. Look at the `reason` tag on
`horecaos.sms.verification.calls`. `SMS_ACCOUNT_MISCONFIGURED`,
`SMS_SENDER_NOT_REGISTERED` and `SMS_PROVIDER_UNSUPPORTED` are all ADR 0026
configuration on that tenant's installation or binding; `PROVIDER_AUTHENTICATION`
is the credential.

**`PROVIDER_AUTHENTICATION` after the automatic refresh.** The gateway already
read past the ADR 0028 secret cache once and got the same answer, so OpenBao's
copy is stale, not ours. The key was rotated in the provider's web console. There
is no API for this: rotate it in the console again if the current value is
unknown, write the new value to OpenBao under the installation's existing secret
reference — the reference does not change — and re-check. Nothing is queued
waiting; every customer who tried during the window was told the code could not
be sent and asked to try again.

**`SMS_SPAM_LIMIT` appears at all.** Treat as an incident, not as load. Our OTP
budget is five per number per hour and the provider's ceiling is fifty, so this
cannot happen while the limiter works. Check, in order: whether
`customers.verification.issue` rate limiting is actually running (ADR 0033's
strict per-minute policy fails *closed*, so a saturated limiter denies rather
than allows — if it is denying, you would see that instead), and whether another
system is sending on the same partner `login`. The second is the likelier one and
is invisible from here: ask the provider what else is on the account.

**Rising `SMS_SEND_UNCONFIRMED`.** The gateway is accepting sends and losing
replies, and the search cannot find them afterwards. **Do not re-send anything.**
Each of those messages may already be on a customer's phone. If the rate is more
than a trickle, disable the binding rather than accumulating unresolved sends —
customers get a clean "verification is unavailable" instead of a code that may or
may not have arrived and a challenge that was torn down anyway.

**`SMS_ROUTE_UNAVAILABLE`, or `/actuator/health` shows the route stopped.** The
route failed to build at startup and no provider was ever contacted. This is a
deploy problem, not a partner problem; see the "When `/actuator/health` reports a
route down" section of `docs/routes/README.md`. Do not restart to fix it — a
route that cannot build will not build on the second attempt, and the restart
discards the log line that says why.

**A customer says they never get a code.** Check `SMS_RECEIVER_BLACKLISTED`
first: an operator-side blacklist is permanent from our side and means that
person can never sign in by phone, so they need another route into their account
rather than another attempt. Otherwise, absence of a delivery receipt proves
nothing — CDMA subscribers never produce one — so do not read a message stuck at
`Sent` as a failure.

## Rollout

Per ADR 0007: ships with its bindings disabled, enabled for one test tenant and
one location against a controlled fake first (`ControlledFakeProvider` — the
provider documents no sandbox, and every example in it uses what looks like a
live account), then one real number, then widened. Rollback disables the binding,
after which `CustomerVerificationService` answers that verification is
unavailable and no challenge is opened.
