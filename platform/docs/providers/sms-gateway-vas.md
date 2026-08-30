# SMS Gate API — smsgw.vas.uz

Transcribed from `sms_gate_doc_v4.4.pdf` (in this directory). Where this file and
the PDF disagree, the PDF wins; where the PDF is silent, this file says so
rather than guessing.

The document is five pages of content and three of embedded font data. It
describes four operations and one inbound callback, and nothing else — there is
no authentication endpoint, no balance query, no sender-name registration API,
and no documented rate limit beyond the anti-spam rule below.

## Common

    Base URL   https://smsgw.vas.uz/api/v2
    Method     POST, for every operation
    Auth       `login` and `key` in the request itself, on every call

There is no token exchange and no session. The credential travels in the body of
every request, which has three consequences for this platform:

- `key` is an ADR 0028 secret **reference**, resolved at call time and never
  stored on a row, in configuration, or in a log. It is "the secret key generated
  in web interface", so rotation is an act performed in the provider's console
  and then in OpenBao — there is no API to rotate it.
- A request body containing the credential must never be logged, not even on
  failure. The existing `ProviderHttpClient` truncates failure bodies to 500
  characters; that is about the *response*. The request body needs its own rule.
- Retrying is safe with respect to authentication (there is no nonce or
  timestamp to go stale), and unsafe with respect to delivery — see idempotency.

## Send one message

    POST /send

| Name | Type | Optional | Description |
|---|---|---|---|
| `login` | String | no | |
| `key` | String | no | Secret key generated in web interface |
| `sender` | String | no | Sender name |
| `phone` | String | no | Receiver's phone number |
| `text` | String | no | Sending message |
| `weight` | Int | yes | Priority `[0,10]`, default `10` |

```json
{ "status": { "code": 0, "description": "success" }, "id": "5981980", "parts": 1 }
```

`id` is the provider's message identifier and the key the delivery callback
arrives under. `parts` is how many SMS segments the text became, which is what
the message costs.

`weight` is a priority and the document does not say which end is urgent. It
defaults to 10 and every example that sets it uses 5. **Do not send it** until
somebody confirms the direction with the provider: a verification code sent at
the wrong priority is a code that arrives after its own expiry.

## Send many messages

    POST /send_msgs

```json
{
  "login": "admin",
  "key": "…",
  "sender": "16888",
  "weight": 5,
  "messages": [
    { "seq": "1000000", "phone": "998998190085", "text": "test" }
  ]
}
```

The response answers **per message**, and this is the part worth reading
carefully:

```json
{
  "messages": [
    { "seq": "1000000", "id": 306332, "code": 0,  "parts": 1 },
    { "seq": "1000002", "id": 0,      "code": 18, "parts": 0 },
    { "seq": "seqtest", "id": 0,      "code": 14, "parts": 0 }
  ],
  "status": { "code": 0, "description": "success" }
}
```

The envelope `status.code` is `0` — **success** — while individual messages
failed. A caller that reads only the envelope concludes every message was sent.
Each entry must be inspected on its own, and `id: 0` means nothing was accepted.

`seq` is the caller's own correlation value, echoed back. The document's example
includes an empty `seq` and the provider still accepts the message, returning an
entry with `"seq": ""` — so `seq` cannot be relied on as a key unless we always
set it.

## Search sent messages

    POST /search

| Name | Type | Optional | Description |
|---|---|---|---|
| `login` | String | no | |
| `key` | String | no | |
| `phone` | String | no | Receiver phone number |
| `date` | long | yes | Unix timestamp; defaults to the current day |

```json
{
  "status": { "code": 0, "description": "success" },
  "data": [ { "id": 723923, "msg": "test", "send_dt": 1498756188, "status": 4 } ]
}
```

This is the **uncertainty resolver**. A send whose response was lost can be
resolved by searching the destination for the day rather than by sending again —
which matters because there is no idempotency key on `/send` (see below).

Note that it returns the message **text** (`msg`). A verification code is
therefore retrievable from the provider for at least a day, by anyone holding
the credential. That is a property of the provider, not of us, and it is a reason
the code we store is a keyed MAC rather than the code itself.

## Delivery feedback (inbound)

> Send sms status back to partner. **CDMA not supported.**

The provider POSTs to an endpoint we host:

```json
{ "login": "admin", "key": "", "id": 555555, "code": 4, "description": "DLVRD" }
```

| Code | Meaning | Terminal |
|---|---|---|
| 0 | Created | no |
| 1 | Sending | no |
| 2 | Fail | yes |
| 3 | Sent | no — handed to the operator, not confirmed |
| 4 | Delivered | yes |
| 5 | Rejected | yes |
| 6 | Unknown | yes, and unresolved |
| 7 | InBlackList | yes |

Two things to design around. **CDMA is not supported**, so for some subscribers
no callback will ever arrive and the message's last known state is `Sent` —
absence of a receipt is not evidence of failure. And the callback carries
`login` and `key`, i.e. the provider authenticates *itself* to us with the same
credential pair; the example shows `key` empty, so what actually arrives must be
confirmed against a real callback before the endpoint trusts either field.

## Status and error codes

| Code | Meaning |
|---|---|
| 0 | success |
| 1 | spam — **50 sms/hour per partner for one phone number** |
| 10 | login required |
| 11 | key required |
| 12 | partner not found |
| 13 | wrong key |
| 14 | phone required |
| 15 | sender required |
| 16 | wrong sender |
| 17 | phone does not match pattern |
| 18 | text required |
| 19 | text too long |
| 20 | receiver in blacklist |
| 21 | unknown operator |
| 22 | distribution name required |
| 23 | distribution not created |
| 24 | distribution_id is required |
| 25 | distribution not found |
| 26 | distribution is expired |
| 27 | server side error |

### How these map onto ADR 0007's outcome model

- **Rejected, do not retry**: 10–19, 21–26. These are our fault or the
  destination's and will fail identically on a second attempt. `13 wrong key` in
  particular should raise loudly rather than retry — it means a rotation
  happened in the console and not in OpenBao.
- **Rejected, and a product fact**: `20 receiver in blacklist`. A customer on the
  operator's blacklist cannot receive a verification code, so they can never sign
  in by phone. That is not an error to retry; it is a state a person has to be
  told about.
- **Retryable**: `27 server side error`.
- **Retryable with a delay, not immediately**: `1 spam`. Fifty per hour per
  number per partner is a provider-side ceiling; our own OTP budget is five per
  number per hour, so we should never approach it — if we see code 1, either our
  limiter is not doing its job or another system shares this account.
- **Uncertain**: a lost or unparseable response to `/send`. There is no
  idempotency key, so a blind retry sends a second message. Resolve with
  `/search` on the destination number for the day, exactly as the Click adapter
  resolves an uncertain payment with a status query rather than resending.

## What the document does not say

Recorded because a gap that is written down is a question somebody can ask,
while a gap that is assumed becomes a defect:

- **No idempotency key** on `/send` or `/send_msgs`. `seq` is echoed but nothing
  says the provider deduplicates on it.
- **No documented timeout or retry guidance**, and no statement of whether a
  request that times out was accepted.
- **No sender-name registration API.** `sender` is a string the account is
  presumably provisioned with; an unregistered value returns `16 wrong sender`.
- **No rate limit other than the anti-spam rule**, and no documented burst
  ceiling for `/send_msgs`.
- **No callback retry policy** and no signature on the inbound callback beyond
  the credential pair.
- **`weight` direction is undefined** — see above.
- **No test or sandbox environment** is mentioned. Every example uses what looks
  like a live account, so contract testing has to be against a controlled fake
  (ADR 0007's `ControlledFakeProvider` pattern), not against the provider.

## What a tenant must supply

Three inputs, and nothing else — there is no URL to type, because the endpoint
comes from the platform-owned catalogue and a tenant only names it (ADR 0026,
[0026](../adr/built/0026-provider-installations-bindings-and-secret-references.md)).
That is what closes the request-forgery path at the model, and it is why the
whole feature was unreachable until `integration.provider_environments` carried
the row below.

| Input | Where it goes | Notes |
|---|---|---|
| `login` | `integration.installations.non_sensitive_config` → `{"login": "…"}` | The partner account name. Not a secret, and useless on its own. A binding may override it in `integration.bindings.configuration_override`, narrower wins (ADR 0030) |
| `sender` | the same object → `{"sender": "…"}` | The registered sender string, typically a short code. **There is no registration API**, so a wrong value is learned at call time as `16 wrong sender` and nowhere earlier. This is the field a multi-brand tenant most often overrides per brand |
| `key` | **OpenBao**, referenced by `integration.installations.secret_reference` | Never a column, never configuration, never a log line (ADR 0028). Reference shape `qoida:{environment}:provider_notification:{owner}:{id}`; the KV v2 secret at `{mount}/data/{environment}/provider_notification/{owner}/{id}` holds the key under the field name **`value`**, which is the only field the resolver reads |

Everything else is platform-side and fixed:

    environment_code    smsgw_vas_production   (the only approved row; V0061)
    provider_category   NOTIFICATION
    provider_type       SMSGW_VAS              (VasSmsGatewayAdapter refuses any
                                                other value by name, because
                                                SEND_SMS is shared with ADR 0020)
    capability          SEND_SMS, enabled and primary for the binding's scope

**There is no non-production environment and there must not be one.** The
document names no sandbox and every example in it uses what looks like a live
account, so a staging row would have to point at the production host — a
`is_production = false` code whose messages are charged and delivered to real
subscribers. Pre-production runs against ADR 0007's `ControlledFakeProvider`
instead; see the rollout section of
[the route descriptor](../routes/sms-verification.md).

**Rotation.** The key is rotated in the provider's web console and then written
to OpenBao under the *same* reference — the reference never changes. There is no
rotation API. A stale value surfaces as `13 wrong key`, which the gateway retries
exactly once past its cache before refusing; the runbook for that is in the route
descriptor.

**How these rows get written today.** By SQL, not through the installation API.
`POST /tenants/{id}/provider-installations` carries the environment, the type and
the secret reference but has no field for `non_sensitive_config`, so `login` and
`sender` cannot be supplied through it; it also creates the installation `DRAFT`
with no endpoint that makes it `ACTIVE`, and binding activation requires a
successful connection check, which only the POS discovery flow currently writes.
Until that is closed, provisioning an SMS gateway is an operator database change,
and it is worth saying so in the place an operator looks rather than leaving it
to be discovered against a customer's sign-in screen.
