# CLICK — card-token calls for HorecaOS's own billing

Working note for `ClickCardProviderAdapter` (ADR 0095, ADR 0007). It covers the four calls the
`CardProviderAdapter` port needs from a provider: **tokenise**, **charge**, **status** and
**revoke**. It sits beside [`click-merchant-api.md`](click-merchant-api.md), which is the full
read of Click's published documentation (section 7.5 is the card-token block); this note says
what the adapter does with it and what it still cannot know. **Nothing contractual was read into
the repository.** No agreement, no credential and no merchant identifier is quoted here: HorecaOS
has no Click merchant account of its own yet (ADR 0095's open input).

**Source.** `https://docs.click.uz/en/merchant-api/requests`, rendered in a browser and read on
2026-10-14 (the site is a single-page app: plain `curl` returns only the navigation shell, see the
fetch caveat in `click-merchant-api.md`). Everything under "What the documentation says" is from
that page. Everything under "What it does not say" is a gap in the documentation, not a guess to
build on.

**Why Click and not Payme.** ADR 0095 names "Click or Payme" and the information architecture names
"Click and Atmos". The owner's batch-21 decision was Click for the first provider. Click publishes a
card-token flow whose first call is made by the cardholder's browser, which is what ADR 0028 and the
`CardEnrolment` contract need (the card number never reaches HorecaOS). Payme's Subscribe API is
declared out of scope in `payme-merchant-api.md`, and no note exists for Atmos: a second adapter is a
second class implementing the same port, not a change to this one.

## What the documentation says

All four calls are on `https://api.click.uz/v2/merchant`, JSON in and out, `error_code: 0` meaning the
API call worked. Credentials are the four values issued at registration: `merchant_id`,
`service_id`, `merchant_user_id` and `secret_key`; `secret_key` is confidential. Three of the four calls
carry `Auth: merchant_user_id:digest:timestamp` where `digest = sha1(timestamp + secret_key)` and
`timestamp` is ten-digit Unix seconds.

| Port method | Click call | Who sends it | Auth |
|---|---|---|---|
| `begin` | none: the adapter opens a session reference and tells the browser where and how to tokenise | HorecaOS | n/a |
| tokenise, browser half | `POST /card_token/request` `{service_id, card_number, expire_date "MMYY", temporary}` answers `{card_token, phone_number (masked), temporary}` | **the cardholder's browser** | **none** in the published example (it carries only the public `service_id`) |
| `confirm` | `POST /card_token/verify` `{service_id, card_token, sms_code}` answers `{card_number (masked, "8600 55** **** 3244")}` | HorecaOS server | `Auth` |
| `charge` | `POST /card_token/payment` `{service_id, card_token, amount, transaction_parameter}` answers `{payment_id, payment_status}` | HorecaOS server | `Auth` |
| `status` | `GET /payment/status_by_mti/:service_id/:merchant_trans_id/YYYY-MM-DD` answers `{payment_id, merchant_trans_id}`, then `GET /payment/status/:service_id/:payment_id` answers `{payment_id, payment_status}` | HorecaOS server | `Auth` |
| `revoke` | `DELETE /card_token/:service_id/:card_token` | HorecaOS server | `Auth` |

`temporary` is `1` for a one-time token that Click deletes after a payment and `0` for a persistent
one. A recurring charge needs a persistent token, so the adapter always sends `0`. `expire_date` is
`MMYY`. The amount of `card_token/payment` is in **som** (whole numbers; UZS is transacted in whole
som and ADR 0018 stores whole som), never tiyin: the factor-of-100 trap in `click-merchant-api.md`
section 9 applies to fiscalisation only.

## How the adapter uses it

**Tokenise.** The card number is typed into HorecaOS's own page and posted by the browser straight to
`card_token/request`; the page never sends it to a HorecaOS endpoint, keeps it in memory only until
the answer arrives, and clears it. HorecaOS's server sees a card token and the code the cardholder's
bank texts, and nothing else (`CardEnrolment`). `begin` therefore returns, as the non-sensitive client
parameters, the `service_id` (a public identifier), the tokenise URL taken from the approved provider
environment, `temporary = 0`, and the `handoff` kind `BROWSER_TOKENISE`. The session reference is
signed by the server and carries the tenant and an expiry, so a token minted for one tenant's session
cannot be confirmed by another.

**Confirm.** The browser sends the session reference, the card token, the bank's code and the card's
expiry month and year (`MMYY`: not the number, and the console already shows it). The adapter calls
`card_token/verify`, takes the last four digits from the masked number Click returns, and answers
`Enrolled` with the card token as the reference to charge later. Click's `verify` does not return the
brand or the expiry, so the brand is reported only when the leading digits of the masked number name a
Click-served scheme the adapter knows (`8600` Uzcard, `9860` Humo) and the expiry is the one the
cardholder entered. A wrong code answers `error_code != 0`; the session survives so the cardholder can
retry, as the fake does.

**Charge.** `card_token/payment` has **no idempotency key** and moves money. The port's contract is
that a repeated key is one charge, so the adapter keeps it itself:

1. Before any charge it asks `status_by_mti` for the key, which it sends as `transaction_parameter`,
   over today's date and the two before it in Uzbekistan time (UTC+5), because the date segment's
   meaning is undocumented (see below).
2. A payment Click reports as paid answers `Succeeded` with Click's `payment_id` and **no new charge**.
   One Click reports as in processing is an exception, so the caller leaves the attempt `PENDING`.
3. Only when Click holds nothing under the key is `card_token/payment` sent. A timeout, a connection
   reset after the request was written, a 5xx or an unreadable body on it is an exception too (`Uncertain`
   in ADR 0007's terms): the attempt stays `PENDING` and the next pass asks again first.
4. A refusal Click states (`error_code != 0`) is `Failed` with a short reason that never echoes the token
   or the number.

**Status.** The port's `status(key)` is the same lookup as step 1 and answers `Succeeded` only when Click
says the payment is paid. It must keep answering after `revoke`: `status_by_mti` and `payment/status`
are payment records of the merchant account and do not depend on the card token, which is what the port
requires ("a charge outlives its card").

**Revoke.** `DELETE /card_token/:service_id/:card_token`. `error_code != 0` is `Failed` and logged by
reason code only; the reference is already gone from HorecaOS either way.

## What it does not say

These are the questions the first sandbox walk answers; none is guessed in the adapter, and each is an
external fact (`docs/runbooks/external-connections/w12-money.md`).

1. **Whether the browser may call `card_token/request` for HorecaOS's `service_id`.** The published
   example carries no `Auth` header while the page also says every request must; the previous reading of
   this note called that a typo. It is built as the example shows, and it also needs Click to allow the
   call cross-origin from HorecaOS's console and to confirm in writing that a page of ours handling a
   card number for one request is within its rules. If Click says no, the handoff changes (a hosted
   page) and the adapter's `begin` and the console's handoff change with it; the other three calls do not.
2. **Whether `transaction_parameter` is what `status_by_mti` searches.** The documentation names
   `merchant_trans_id` there and `transaction_parameter` on the charge. The adapter assumes they are the
   same identifier; the sandbox must show one charge found by its own key. If they are not, the safe
   fallback is to refuse any retry of an unanswered charge and leave it `PENDING` for a person. Until the
   answer for "no such payment" is known, the adapter reads only HTTP 404 and a success with no payment as
   "nothing held"; any other `error_code` is "cannot say" unless the installation lists it in
   `notFoundErrorCodes`, so no charge is sent on a guess.
3. **The `YYYY-MM-DD` of `status_by_mti`**: which date and which timezone. Today and the two days before,
   in UTC+5, is the net the adapter casts by default (`lookbackDays`, an installation setting up to 31),
   and a payment found on any of them beats a lookup that failed on another. A charge stranded for longer
   than the reach is read as "nothing held" and would be charged again, which is why the reach is the
   account's to widen before a long suspension and why the platform refuses to suspend an account while
   charges asked through it are unresolved (`UNRESOLVED_CARD_CHARGES`).
4. **The `payment_status` of a card-token charge.** Click's own examples show `1` beside "Success";
   `click-merchant-api.md` and `ClickPaymentStatus` treat only `2` as money. The adapter does too, and an
   unknown positive value is "in processing". The sandbox confirms which value a synchronous card charge
   answers.
5. **The `error_code` table.** Not published (`click-merchant-api.md` Q1). A non-zero code is a decline
   with the code's number as the reason; nothing is classified as retryable on a guess.
6. **Whether `verify` can be repeated for a wrong code**, and how many times, and how long a persistent
   token lives before Click removes it.
7. **Clock-skew tolerance of `Auth`**, rate limits and the source addresses of Click's responses.

## What the fake models

`FakeClickCardHttpProvider` is a JDK `HttpServer` that answers the four calls above in the shapes the
page shows, on a real socket at the URL an approved environment names, so the adapter, the Camel route,
the egress check and the secret resolution all run as in production. It can lose an answer after the
money moved, time out, answer 500, answer malformed JSON and replay a duplicate key, selected by the
`service_id` or the card token a test uses, never by a switch in production code (ADR 0007). It knows
nothing Click does not publish: its `error_code` values other than `0` are invented placeholders and are
labelled so.
