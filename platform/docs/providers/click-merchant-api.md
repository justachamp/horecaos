# CLICK — merchant integration contract

Working notes, precise enough to implement against without opening a browser.

**Sources actually read** (all fetched 2026-08-22):

| Source | How | Status |
|---|---|---|
| `https://docs.click.uz/merchant-api` (Общее) | headless browser | read |
| `https://docs.click.uz/merchant-api/requests` (Запросы) | headless browser | read |
| `https://docs.click.uz/merchant-api/errors` (Ошибки) | headless browser | read |
| `https://docs.click.uz/merchant-api/click-pass` | headless browser | read |
| `https://docs.click.uz/merchant-api/fiscalization` | headless browser | read |
| `https://docs.click.uz/shop-api`, `/shop-api/requests`, `/shop-api/errors` | headless browser | read |
| `https://docs.click.uz/click-button`, `/click-pay-by-card` | headless browser | read |
| `https://docs.click.uz/additional/telegram-payments`, `/additional/mobile`, `/additional/shop-split`, `/additional/get-info`, `/additional/example` | headless browser | read |
| `https://docs.click.uz/en/merchant-api/requests` | headless browser | read (cross-check of the RU page; identical content) |
| `github.com/click-llc/click-integration-django` | cloned, full source read | read |
| `github.com/click-llc/click-integration-php` | cloned, full source read | read — this is the *other* official reference, linked from `/additional/example`; used as a tiebreaker |

**Fetch caveat.** `docs.click.uz` is a Docusaurus SPA whose nginx serves the same
18,784-byte shell for every path — `curl` and plain HTTP fetching return only the
navigation chrome, no content. Everything above was obtained by rendering the
routes in a real browser and reading the `<main>` text. If you re-derive these
notes, do not use `curl`; you will silently get an empty page and think the docs
say nothing. No page failed to load, so there are no gaps attributable to fetch
failure — the gaps listed in §12 are gaps in the documentation itself.

**Terminology note.** CLICK's own docs never say "merchant calls CLICK" vs "CLICK
calls merchant". They split by product name, and the split is confusing because
the *merchant* implements SHOP API while CLICK operates MERCHANT API. Get this
backwards and you will build the wrong half. See §1.

---

## 1. The two directions

There are two entirely separate protocols with different transports, different
auth, different signatures and different error vocabularies.

### Direction A — CLICK → you ("SHOP API")

CLICK POSTs to **your** server. You implement these endpoints; you give CLICK
their URLs during onboarding (the URLs are configured in the CLICK merchant
cabinet — the docs do not specify a self-service API for registering them).

* Transport: `POST`, `Content-Type: application/x-www-form-urlencoded`
* Your response: `application/json`
* Auth: none. The **only** authentication is the `sign_string` MD5 (§3.2).
* Error vocabulary: `error` / `error_note`, small negative integers `-1 … -9` (§5.1)
* Two calls: **Prepare** (`action=0`) and **Complete** (`action=1`)

This is the direction that actually credits an order. If you implement only
direction B you will never learn that a customer paid.

### Direction B — you → CLICK ("MERCHANT API")

You call `https://api.click.uz/v2/merchant/...`.

* Transport: `POST`/`GET`/`DELETE`/`PUT` JSON (XML also accepted)
* Auth: the `Auth:` header, SHA-1 based (§3.1)
* Error vocabulary: `error_code` / `error_note`, large negative integers (§5.3)
* Used for: creating invoices (push-to-phone), checking invoice/payment status,
  reversals, card tokens, CLICK Pass (QR), fiscalization.

### Direction C — the browser/app redirect ("Web payment" / payment link)

Not an API. A `GET` to `https://my.click.uz/services/pay/` with query params
(§7.7). The user pays on CLICK's page; **the money reaches your ledger through
direction A**, not through the redirect. `return_url` is a UX affordance only —
never credit an order because the browser came back to `return_url`.

### Direction D — "Advanced Shop" / "Split Shop" (a *different* CLICK→you protocol)

Documented at `/additional/get-info` and `/additional/shop-split`. Same
direction as A (CLICK calls you) but a completely different wire format: JSON
body, a `params` object instead of flat fields, actions `0/1/2/3/4`
(Getinfo/Prepare/Confirm/Check/Compare), `click_paydoc_id` + `attempt_trans_id`
as the transaction key, and a different signature (§3.3). This is the
utility-billing-style integration. **Do not mix it with SHOP API.** If your
contract is an e-commerce/marketplace one you almost certainly want SHOP API
(direction A). Documented here in §8 for completeness because it is easy to land
on that page and implement the wrong protocol.

---

## 2. Credentials

Issued at registration (`/merchant-api/requests`, `/merchant-api/click-pass`):

| Name | Used in |
|---|---|
| `merchant_id` | payment link / redirect (direction C) only |
| `service_id` | every direction; a merchant may have several services |
| `merchant_user_id` | the `Auth:` header of direction B; optional in direction C |
| `secret_key` | both signatures. Confidential. |

`service_id` scopes almost everything: CLICK Pass confirmation mode is a
per-`service_id` setting, fiscalization is per-`service_id`, and status lookups
take `service_id` in the path.

---

## 3. Signatures — spelled out

### 3.1 Direction B: the `Auth` header

```
Auth: <merchant_user_id>:<digest>:<timestamp>
```

Algorithm:

1. `timestamp` = current UNIX time in **seconds**, 10 digits, as a decimal string.
2. `digest` = `lowercase_hex( SHA1( timestamp_string ++ secret_key ) )`
   — string concatenation, no separator, no HMAC, secret **appended** after the
   timestamp.
3. Join `merchant_user_id`, `digest`, `timestamp` with `:`.

Worked example (values invented by me for unit-testing; **not** from the docs):

```
merchant_user_id = 3333
secret_key       = "SECRET123"
timestamp        = 1712345678
digest_input     = "1712345678SECRET123"
SHA1             = 4d3f62489dbc19114297581bcfa0d906f84df0cd
Auth: 3333:4d3f62489dbc19114297581bcfa0d906f84df0cd:1712345678
```

Confirmed identically on three pages (`/merchant-api/requests`,
`/merchant-api/click-pass`, `/merchant-api/fiscalization`) and in the PHP
reference (`click/models/Payments.php::init_provider`):

```php
'Auth' => $this->provider['user_id'] . ':' .
          sha1($this->helper->timestamp . $this->provider['secret_key']) . ':' .
          $this->helper->timestamp
```

The Django reference (`click/__init__.py::ApiHelper.__init__`) does the same with
`hashlib.sha1`. All three agree. **High confidence.**

The docs do not state a validity window for `timestamp`, nor whether CLICK
rejects skewed clocks. Treat it as short-lived and compute it per request.

### 3.2 Direction A: `sign_string` on Prepare and Complete

`sign_string` is a **lowercase hex MD5** of a bare concatenation (no separators)
of the following fields, in exactly this order:

**Prepare (`action=0`):**

```
md5( click_trans_id ++ service_id ++ SECRET_KEY ++ merchant_trans_id
     ++ amount ++ action ++ sign_time )
```

**Complete (`action=1`):**

```
md5( click_trans_id ++ service_id ++ SECRET_KEY ++ merchant_trans_id
     ++ merchant_prepare_id ++ amount ++ action ++ sign_time )
```

The **only** difference is `merchant_prepare_id`, present for Complete, absent
(not empty-string-padded, just absent) for Prepare. Both reference
implementations express this as one formula with a conditional empty string,
which is the same thing:

```php
// click-integration-php, click/models/BasicPaymentsErrors.php
$sign_string = md5(
    $request['click_trans_id'] .
    $request['service_id'] .
    $this->provider['secret_key'] .
    $request['merchant_trans_id'] .
    ($request['action'] == 1 ? $request['merchant_prepare_id'] : '') .
    $request['amount'] .
    $request['action'] .
    $request['sign_time']
);
```

```python
# click-integration-django, click/utils.py
merchant_prepare_id = request.POST.get('merchant_prepare_id', None) if action != None and action == '1' else ''
signString = '{}{}{}{}{}{}{}{}'.format(
    click_trans_id, service_id, click_secret_key(), order_id,
    merchant_prepare_id, amount, action, sign_time
)
encoder = hashlib.md5(signString.encode('utf-8'))
```

Docs, PHP and Django all agree. **High confidence.**

Notes that decide whether your verification actually works:

* **Concatenate the raw received strings, not reparsed values.** `amount` arrives
  as form text; whether CLICK sends `1000`, `1000.0` or `1000.00` changes the
  MD5. Both reference implementations concatenate the un-coerced request value
  (PHP `$_POST`, Django `request.POST.get`). Do the same: read the raw form
  value, use it verbatim in the digest, and only *then* parse it as a decimal
  for the amount comparison. Reformatting the amount before hashing is the
  single most common cause of a spurious `-1 SIGN CHECK FAILED!`.
* `click_paydoc_id` is **not** part of the signature, despite being present in
  the request.
* `error` and `error_note` are **not** part of the signature.
* Encoding is UTF-8; the hash is the 32-char lowercase hex digest.
* Compare in constant time.

Worked example (invented by me, verified by computation — use as a unit test):

```
secret_key          = "SECRET123"
click_trans_id      = "3737503"
service_id          = "12345"
merchant_trans_id   = "order_9001"
amount              = "1000.00"
sign_time           = "2026-08-22 14:03:11"

Prepare  input  = "373750312345SECRET123order_90011000.0002026-08-22 14:03:11"
Prepare  md5    = 9f73df6a589039c3afde3e2039720e46

merchant_prepare_id = "778"
sign_time           = "2026-08-22 14:03:19"
Complete input  = "373750312345SECRET123order_90017781000.0012026-08-22 14:03:19"
Complete md5    = a55a9138d88a9f9ee6fa86bece724e0b
```

(The docs contain **no** worked example with concrete values. These are mine.)

### 3.3 Direction D: `sign_string` for Advanced/Split Shop

```
md5( click_paydoc_id ++ attempt_trans_id ++ service_id ++ SECRET_KEY
     ++ params ++ action ++ sign_time )
```

where `params` means "all the values of the `params` object, concatenated in the
order they were transmitted" (docs: «все значения пар объекта `params` в
переданном порядке»). Same formula for Prepare (`action=1`), Confirm
(`action=2`) and Check (`action=3`).

This is fragile by construction — it depends on JSON key order surviving your
parser. If you implement direction D, parse the body preserving order (in Python:
`json.loads` on CPython 3.7+ preserves insertion order; do **not** re-serialise
through a dict that sorts keys). Neither reference implementation covers
direction D, so there is no code to check the prose against. **Medium
confidence; verify against a real request before going live.**

### 3.4 The payment-link signature — a real contradiction

The current `/click-button` page describes the payment link as an **unsigned**
`GET` to `https://my.click.uz/services/pay/` with
`merchant_id, service_id, transaction_param, amount, return_url, card_type`
(and optional `merchant_user_id`). No `SIGN_TIME`, no `SIGN_STRING`.

The Django reference (`click/forms.py::PaymentButtonForm`) instead builds a
**signed** POST-style form to a *different* URL, `https://my.click.uz/pay/`, with
uppercase field names and:

```python
self.sign_time = strftime('%Y-%m-%d')          # date only, no time
def sign_string(self):
    string = '{sign_time}{secret_key}{merchant_service_id}{merchant_trans_id}{amount}'
```

i.e. `md5(sign_time ++ secret_key ++ service_id ++ merchant_trans_id ++ amount)`
with fields `MERCHANT_TRANS_AMOUNT, MERCHANT_ID, MERCHANT_USER_ID,
MERCHANT_SERVICE_ID, MERCHANT_TRANS_ID, MERCHANT_TRANS_NOTE,
MERCHANT_USER_EMAIL, SIGN_TIME, SIGN_STRING, RETURN_URL`.

**Which I believe:** the docs. Reasons: (a) `/click-pay-by-card` and
`/additional/mobile` — two independent current pages — both use the unsigned
`my.click.uz/services/pay/` form with the same lowercase parameter names, and the
Android/iOS deeplink examples are plainly unsigned; (b) the PHP reference, which
is the newer of the two (it targets `api.click.uz/v2`, Django targets `v1`),
contains no payment-button form at all; (c) the Django form is the only artefact
anywhere referencing `my.click.uz/pay/` or `SIGN_STRING` in this shape, and
`django-payments` integration code in that repo is otherwise visibly stale.

Practical consequence: the redirect is unauthenticated, so **anyone can construct
a payment link for your `service_id` with an arbitrary `amount` and
`transaction_param`.** Never trust the amount that comes back; the amount you
enforce is the one you check in Prepare (§4.2, `-2`).

---

## 4. Direction A in full — Prepare and Complete

### 4.1 Wire format

Request from CLICK:

```
POST /your/prepare/url HTTP/1.1
Content-Type: application/x-www-form-urlencoded

click_trans_id=3737503&service_id=12345&click_paydoc_id=987654321
&merchant_trans_id=order_9001&amount=1000.00&action=0&error=0
&error_note=Success&sign_time=2026-08-22+14%3A03%3A11&sign_string=9f73...
```

Your response: `Content-Type: application/json`, HTTP 200, body as below.
(The docs never say what HTTP status to use; both references emit 200 with the
error carried in the JSON `error` field. Always return 200 — a non-200 is an
undocumented case and will be treated as a transport failure and retried.)

### 4.2 Prepare — `action = 0`

**Request fields** (all present unless noted):

| # | Field | Type | Req. | Meaning |
|---|---|---|---|---|
| 1 | `click_trans_id` | bigint | yes | Transaction (attempt) id in CLICK |
| 2 | `service_id` | int | yes | Your service id — validate it matches yours |
| 3 | `click_paydoc_id` | bigint | yes | Payment-document number in CLICK. Not signed. Store it: it is what CLICK support quotes. |
| 4 | `merchant_trans_id` | varchar | yes | Your order id / account / login. Same value as `transaction_param` from the payment link, or `merchant_trans_id` from `invoice/create`. |
| 5 | `amount` | float (sent as text) | yes | Payment amount **in som** (see §9) |
| 6 | `action` | int | yes | `0` |
| 7 | `error` | int | yes | Status code from CLICK's side. `0` = ok, `< 0` = CLICK-side failure |
| 8 | `error_note` | varchar | yes | Human text for `error` |
| 9 | `sign_time` | varchar | yes | `YYYY-MM-DD HH:mm:ss` |
| 10 | `sign_string` | varchar | yes | MD5, §3.2 |

**Response fields:**

| # | Field | Type | Req. | Meaning |
|---|---|---|---|---|
| 1 | `click_trans_id` | bigint | yes | Echo of the request |
| 2 | `merchant_trans_id` | varchar | yes | Echo of the request |
| 3 | `merchant_prepare_id` | int | yes on success | Your billing-side transaction id. CLICK returns this to you in Complete. |
| 4 | `error` | int | yes | `0` = success, else §4.4 |
| 5 | `error_note` | varchar | yes | Description |

Documented Prepare-time obligations: verify the order/account exists and can be
fulfilled; verify the amount; and **for e-commerce, reserve the goods** so the
same stock is not sold twice while CLICK charges the card.

Both references also return `merchant_confirm_id` on the Prepare response even
though the docs' Prepare response table does not list it. Harmless; CLICK ignores
unknown fields. `/shop-api/errors` describes the success response as carrying
"`merchant_prepare_id` **or** `merchant_confirm_id`", which is where that comes
from.

### 4.3 Complete — `action = 1`

**Request fields:** as Prepare, plus:

| # | Field | Type | Req. | Meaning |
|---|---|---|---|---|
| 5 | `merchant_prepare_id` | int | yes | Exactly the value you returned from Prepare |

and `action` is `1`. Note the ordinal shift: in the Complete request table
`amount` is #6, `action` #7, `error` #8, `error_note` #9, `sign_time` #10,
`sign_string` #11.

`sign_time` on the Complete request is documented only as "Дата платежа" with no
format restated; assume the same `YYYY-MM-DD HH:mm:ss` as Prepare.

**Response fields:**

| # | Field | Type | Req. | Meaning |
|---|---|---|---|---|
| 1 | `click_trans_id` | bigint | yes | Echo |
| 2 | `merchant_trans_id` | varchar | yes | Echo |
| 3 | `merchant_confirm_id` | int | yes on success | Your completion-transaction id |
| 4 | `error` | int | yes | `0` = success |
| 5 | `error_note` | varchar | yes | Description |

**The most important paragraph in the whole SHOP API doc**, translated:

> If Prepare succeeded and the card was successfully charged, the response to
> Complete cannot be an error — except when the payment was already confirmed
> (`error = -4`) or when this is a repeat attempt to confirm a previously
> cancelled payment (`error = -9`).
>
> If the goods/service cannot be delivered *after* funds were successfully
> debited, the merchant's billing must answer Complete **"success"** and then
> initiate a reversal itself (`payment/reversal`, §7.4).

So: never signal a business failure through Complete. Answer `0`, then reverse.
If you answer with an error, CLICK retries; after several failures the payment
goes to manual investigation by CLICK support (docs say so explicitly).

### 4.4 The `error` field on the request (CLICK → you)

`/shop-api/errors` states: a **negative** `error` in the incoming request means
CLICK's side failed. You must then void the payment in your billing and answer
`-9`.

Both references implement this. Django folds it into one check
(`if order.status == REJECTED or int(error) < 0: return -9`); PHP does it in
`complete()` after the fact and additionally guards against clobbering `-4`/`-9`.

---

## 5. Error codes

### 5.1 Direction A — codes you return to CLICK

Canonical table from `/shop-api/errors`, with the retry classification I
derive — the classification is **mine**, the docs never classify.

| `error` | `error_note` | Meaning | Retry class |
|---|---|---|---|
| `0` | `Success` | Operation succeeded | terminal-success |
| `-1` | `SIGN CHECK FAILED!` | Signature mismatch | **terminal** for that request; CLICK may retry, but it will fail identically until config is fixed. Alert on it — a burst means either a key rotation you missed or someone probing. |
| `-2` | `Incorrect parameter amount` | Amount does not match the order | **terminal**. Do not credit. |
| `-3` | `Action not found` | `action` was neither 0 nor 1 | **terminal** |
| `-4` | `Already paid` | Transaction was already confirmed | **terminal, and it means success**. Treat as idempotent replay (§6). Do not double-credit; do not treat as failure. |
| `-5` | `User does not exist` (Advanced Shop wording: `User does not exist by params`) | No such order/account for `merchant_trans_id` | **terminal** if your order genuinely does not exist; **uncertain** if you have a read-replica lag or the order is created asynchronously — see §12 Q3 |
| `-6` | `Transaction does not exist` | `merchant_prepare_id` not found | **terminal** |
| `-7` | `Failed to update user` | Your billing failed to apply the change (e.g. balance update) | **retryable** — this is the one code that means "transient, come back". |
| `-8` | `Error in request from click` | Malformed/incomplete request from CLICK | **terminal** |
| `-9` | `Transaction cancelled` | Transaction was previously cancelled | **terminal**. Also the code you must return when the incoming `error` was negative. |

Codes for the **uncertain** bucket: there are none in this table, which is the
point of it — direction A is a callback, so *you* never have an uncertain
outcome here. The uncertainty lives entirely in direction B (§5.3).

`/shop-api/errors` and `/additional/shop-split` give slightly different wordings
for `-5` and richer descriptions for `-4`/`-6`/`-9`; the Split-Shop page is the
more explicit one and is quoted above where it differs.

Return the note strings **verbatim**. CLICK's own reference implementations do,
and `error_note` shows up in CLICK-side support tooling.

### 5.2 Ordering of the checks

The docs do not specify the order in which to evaluate the failure conditions,
and the two reference implementations **disagree**:

| Order | PHP (`BasicPaymentsErrors::request_check`) | Django (`click_webhook_errors`) |
|---|---|---|
| 1 | `-8` missing fields | `-8` missing fields |
| 2 | `-1` signature | `-1` signature |
| 3 | `-3` action | `-3` action |
| 4 | `-5` order lookup | `-5` order lookup |
| 5 | `-6` prepare-id lookup (action 1 only) | `-2` amount |
| 6 | `-4` already paid | `-4` already paid |
| 7 | `-2` amount | `-6` prepare-id check (action 1 only) |
| 8 | `-9` cancelled | `-9` cancelled |

I would follow **PHP's** order, with one change: check `-4 Already paid` before
`-2 Incorrect parameter amount`. Rationale: a replayed Complete for an
already-credited order must report `-4` (which CLICK understands as "fine,
settled") rather than accidentally reporting `-2` because you have since adjusted
the order total. Signature must be checked before any database lookup, which both
agree on.

### 5.3 Direction B — `error_code`

`/merchant-api/errors` documents **only HTTP status codes**, not the JSON
`error_code` values:

| HTTP | Meaning |
|---|---|
| 200 | OK |
| 201 | OK |
| 400 | Bad Request |
| 401 | Not Authorized |
| 403 | Forbidden |
| 404 | Not Found |
| 406 | Not Acceptable (bad request format) |
| 410 | Gone |
| 500 | Internal Server Error |
| 502 | "Service Unavailable — сервис недоступен или находится на обновлении" (sic: 502 described as Service Unavailable) |

Retry classification (mine):

* `200/201` — read `error_code` from the body; the HTTP status tells you nothing
  about payment success.
* `400/401/403/404/406/410` — **terminal**. Configuration or programming error.
  Never retry a `card_token/payment` on a 4xx.
* `500/502` and any transport error (timeout, connection reset, TLS failure) on a
  **mutating** call (`invoice/create`, `card_token/payment`,
  `click_pass/payment`, `payment/reversal`) — **UNCERTAIN**. The charge may have
  gone through. Do **not** blind-retry. Resolve by querying
  `payment/status_by_mti` (§6.3) using the `merchant_trans_id` you sent, and only
  retry if it reports no payment. This is the double-charge trap.
* `500/502` on a **read** call (`payment/status`, `invoice/status`,
  `ofd_data` GET) — **retryable** with backoff.

**The `error_code` value table is not published anywhere I could reach.** The
docs show only `error_code: 0` / `"Success"` in every example. What can be
established from the references:

* `error_code == 0` means success everywhere (both references branch on exactly
  this).
* The PHP reference returns a locally-invented `-31300 "Payment in processing"`;
  the Django reference invents `-1000`, `-5001`, `-5002` and `-1 * http_status`.
  **None of these are CLICK codes** — they are the sample apps' own codes. Do not
  put them in a mapping table.
* Real CLICK `error_code` values (e.g. for "insufficient funds", "card blocked",
  "invalid OTP") are **not documented**. See §12 Q1.

### 5.4 Status codes (distinct from error codes)

**Payment status** (`payment_status`, from `/merchant-api/click-pass` and
`/click-pay-by-card`):

| Value | Meaning | Class |
|---|---|---|
| `< 0` | Error; detail in `error_note` | terminal-failure |
| `0` | Payment created | in-flight |
| `1` | In processing | in-flight — **not yet money** |
| `2` | Successfully paid | terminal-success |

Note carefully: several MERCHANT API examples show `"payment_status": 1` in a
response whose `error_note` is `"Success"`. `error_code: 0` means *the API call*
succeeded; `payment_status: 2` means *the money moved*. Confusing these is a way
to credit an unpaid order. The full enumeration of the `< 0` payment statuses is
not published (§12 Q1).

**Invoice status** (`invoice_status`): the only documented value is `-99`
= «Удалён» / "Deleted". Both references implement the same three-way branch, and
their agreement is the best evidence available for the semantics:

| Value | Meaning (inferred from both references, **not** from prose) |
|---|---|
| `> 0` | paid / confirmed |
| `-99` | invoice deleted / rejected |
| other `< 0` | error state |

```python
# Django click/__init__.py
if _json['status'] > 0:      CONFIRMED
elif _json['status'] == -99: REJECTED
elif _json['status'] < 0:    ERROR
```

Both references read this field as **`status`**; the documentation calls it
**`invoice_status`**. Discrepancy — see §12 Q2.

---

## 6. Idempotency

### 6.1 What CLICK keys a payment on

* **CLICK's identifiers:** `click_paydoc_id` is the payment document.
  `click_trans_id` is the *attempt*. The Advanced Shop page states the rule
  explicitly for that protocol — "a repeat attempt has a new id;
  `click_paydoc_id` and `attempt_trans_id` together form the unique value" — and
  the same relationship holds in SHOP API between `click_paydoc_id` and
  `click_trans_id`, though SHOP API never says so in as many words. **Medium
  confidence** on that transfer of the rule.
* **Your identifier:** `merchant_trans_id`. This is the string you put in
  `transaction_param` on the payment link, or in `merchant_trans_id` on
  `invoice/create`, and it comes back in every Prepare and Complete. It is the
  join key between your order and CLICK's payment. It is also the lookup key for
  `payment/status_by_mti`.
* **The Prepare↔Complete link:** `merchant_prepare_id` — a value *you* mint in
  Prepare and CLICK hands back in Complete.

### 6.2 What actually happens on a repeat

**This is not documented.** The docs never use the word "idempotent" and never
describe repeated-delivery semantics for Prepare or Complete. What can be
established:

* CLICK **does** retry: `/shop-api/requests` says that after several error
  responses "the payment may be moved to manual investigation by CLICK technical
  support", which only makes sense if retries occur.
* The error table implies replay handling is expected: `-4 Already paid` exists
  precisely to answer a second Complete for a settled transaction, and `-9` to
  answer a second Complete for a cancelled one. The doc's own escape clause —
  "the response to Complete cannot be an error, except `-4` and `-9`" — is a
  description of the two replay outcomes.
* **Repeated Prepare with the same `merchant_trans_id`:** the docs do not state
  whether this is idempotent. Both references make it idempotent *by
  construction* — they look the order up by `merchant_trans_id` and return that
  order's primary key as `merchant_prepare_id`, so a second Prepare yields the
  same `merchant_prepare_id` and no new row:
  ```php
  $payment = $this->model->find_by_merchant_trans_id($request['merchant_trans_id']);
  $merchant_prepare_id = $payment['id'];
  ```
  Neither creates a transaction record keyed on `click_trans_id`. I believe the
  references: minting a fresh `merchant_prepare_id` per Prepare would break
  Complete, because Complete carries exactly one `merchant_prepare_id` and there
  would be no way to know which Prepare it belonged to.

**Implementation rule, derived rather than documented:**

1. `merchant_prepare_id` must be a **deterministic function of the order**, not a
   fresh id per Prepare call. Making it your order's primary key (as both
   references do) is the safe choice.
2. Credit the order in Complete **inside a transaction, guarded by the order's
   own state**, not by "have I seen this `click_trans_id`". Then a replay
   naturally lands on `-4`.
3. Persist `click_trans_id` and `click_paydoc_id` alongside the credit for
   reconciliation and support.

### 6.3 Idempotency on your outbound calls (direction B)

There is **no idempotency-key header** anywhere in MERCHANT API. The only
recovery mechanism is a status query:

```
GET /v2/merchant/payment/status_by_mti/:service_id/:merchant_trans_id/YYYY-MM-DD
```

which resolves *your* id to CLICK's `payment_id`. Note the trailing date path
segment — the docs give no explanation of it beyond the format; presumably the
payment date, to scope the search. It is documented as part of the path, so send
it. (The PHP reference builds this URL **without** the date segment *and* with
the wrong HTTP verb — `DELETE` instead of `GET` — in
`on_checking_with_merchant_trans_id`. That is a bug in the sample, not an
alternative API; the docs and the English docs agree it is `GET` with the date.)

Consequence for the retry policy: choose `merchant_trans_id` values that are
unique per payment attempt-set and are recoverable from your own database, so
that after any uncertain outcome you can ask CLICK what happened rather than
retrying blindly.

---

## 7. Direction B — the endpoints

Base: `https://api.click.uz/v2/merchant/`
Headers on every call: `Accept`, `Content-Type`, `Auth` (§3.1).
Bodies: `application/json` (or `application/xml`).

> **Version discrepancy.** The Django reference hardcodes
> `https://api.click.uz/v1/merchant` and paths like `/invoice/status/...`. The
> docs and the PHP reference both use **v2**. I believe **v2**: it is on three
> current doc pages in both languages and in the newer sample. Also, Django calls
> `POST /card_token/payment` to verify an SMS code, where docs and PHP use
> `POST /card_token/verify` — Django's is simply wrong (its
> `payment_with_token()` method is also plainly broken; it references `response`
> before assigning it, so it never issues the HTTP request at all).

### 7.1 Create invoice — push a payment request to a phone

```
POST /v2/merchant/invoice/create
{
  "service_id": 12345,
  "amount": 10000,
  "phone_number": "998901234567",
  "merchant_trans_id": "order_123"
}
```

| Field | Type | Req. | Meaning |
|---|---|---|---|
| `service_id` | integer | yes | Service id |
| `amount` | float | yes | Payment amount (som — §9) |
| `phone_number` | string | yes | Recipient, `998XXYYYYYYY` — 12 digits, no `+` in the example |
| `merchant_trans_id` | string | yes | Your order id — this is what comes back in Prepare/Complete |

Response:

| Field | Type | Meaning |
|---|---|---|
| `error_code` | integer | `0` = accepted |
| `error_note` | string | description |
| `invoice_id` | bigint | CLICK's invoice id |

A created invoice is **not** a payment. The user still has to accept it in the
CLICK app. You learn about the actual payment through direction A.

### 7.2 Invoice status

```
GET /v2/merchant/invoice/status/:service_id/:invoice_id
```

Response: `error_code`, `error_note`, `invoice_status` (bigint),
`invoice_status_note` (string). Example shows `-99` / "Deleted". See §5.4 for the
naming discrepancy and the inferred value semantics.

### 7.3 Payment status

```
GET /v2/merchant/payment/status/:service_id/:payment_id
```

Response: `error_code`, `error_note`, `payment_id` (bigint), `payment_status`
(int, §5.4).

```
GET /v2/merchant/payment/status_by_mti/:service_id/:merchant_trans_id/YYYY-MM-DD
```

Response: `error_code`, `error_note`, `payment_id` (bigint), `merchant_trans_id`
(string). The response table for this call is not given separately in the docs —
only the example body above. Notably the example does **not** include
`payment_status`, so this call resolves your id to a CLICK `payment_id`, after
which you call `/payment/status` for the state. Treat that as the flow.

### 7.4 Reversal

```
DELETE /v2/merchant/payment/reversal/:service_id/:payment_id
```

Response: `error_code`, `error_note`, `payment_id`.

Documented conditions (identical on `/merchant-api/requests` and
`/merchant-api/click-pass`):

* the payment must have completed successfully;
* only payments of the **current reporting month** can be reversed;
* payments from the previous month can be reversed **only on the first day of the
  current month**;
* payment must have been made with an online card;
* the reversal may still be **rejected by UZCARD**.

There is no partial reversal in the documented API. Amount is not a parameter.

### 7.5 Card tokens

```
POST /v2/merchant/card_token/request
{ "service_id": 12345, "card_number": "8600123412341234",
  "expire_date": "0526", "temporary": 1 }
```

| Field | Type | Req. | Meaning |
|---|---|---|---|
| `service_id` | integer | yes | |
| `card_number` | string | yes | 16 digits |
| `expire_date` | string | yes | **`MMYY`** (`"0526"` = May 2026) |
| `temporary` | integer | yes | `1` = one-time token, auto-deleted after payment; `0` = persistent |

Response: `error_code`, `error_note`, `card_token` (string, UUID-shaped),
`phone_number` (string, masked, e.g. `"+998*******97"`), `temporary` (**boolean**
in the response, though it is an **integer** in the request — the docs' own
examples show `1` going in and `true` coming out).

Note both the RU and EN `card_token/request` code blocks omit the `Auth:` header
line, while every neighbouring call shows it. I read this as a docs typo, not a
public endpoint — send `Auth:`.

```
POST /v2/merchant/card_token/verify
{ "service_id": 12345, "card_token": "token", "sms_code": 123456 }
```

`sms_code` is shown **unquoted** (integer) in the doc example. Response:
`error_code`, `error_note`, `card_number` (masked, e.g. `"8600 55** **** 3244"`).

```
POST /v2/merchant/card_token/payment
{ "service_id": 12345, "card_token": "token",
  "amount": 10000, "transaction_parameter": "order_123" }
```

**Field-name trap:** the order-id field here is `transaction_parameter`, *not*
`merchant_trans_id` and *not* `transaction_param`. Three different names for the
same concept across three surfaces:

| Surface | Field name for your order id |
|---|---|
| Payment link / deeplink | `transaction_param` |
| `invoice/create` | `merchant_trans_id` |
| `card_token/payment` | `transaction_parameter` |
| Prepare / Complete callbacks | `merchant_trans_id` |
| CLICK Pass | `transaction_id` |

Response: `error_code`, `error_note`, `payment_id` (bigint), `payment_status`
(int).

This is the call with the worst uncertainty profile: it moves money and has no
idempotency key. Persist `transaction_parameter` **before** issuing it, and on
timeout resolve via `status_by_mti` rather than retrying.

```
DELETE /v2/merchant/card_token/:service_id/:card_token
```

Response: `error_code`, `error_note`.

### 7.6 CLICK Pass (QR at the till)

```
POST /v2/merchant/click_pass/payment
{ "service_id": 12345, "otp_data": "1234567415821", "amount": 500,
  "cashbox_code": "KASSA-1", "transaction_id": "12345" }
```

| Field | Type | Req. | Meaning |
|---|---|---|---|
| `service_id` | integer | yes | |
| `otp_data` | string | yes | Contents of the scanned QR code |
| `amount` | float | yes | Amount |
| `cashbox_code` | string | **optional** | Till identifier |
| `transaction_id` | string | **optional** | Your transaction id |

Response: `error_code`, `error_note`, `payment_id` (bigint), `payment_status`
(int), `confirm_mode` (bit), `card_type` (string: `private` | `corporate`),
`processing_type` (string: `UZCARD` | `HUMO` | `WALLET`), `card_number` (string,
masked), `phone_number` (string).

Confirmation mode, enabled per `service_id`:

```
PUT    /v2/merchant/click_pass/confirmation/:service_id   → enable
DELETE /v2/merchant/click_pass/confirmation/:service_id   → disable
POST   /v2/merchant/click_pass/confirm
       { "service_id": 12345, "payment_id": 1234567 }
```

**Unconfirmed payments are auto-cancelled after 30 seconds.** That is a hard
deadline on your till software, and it is the tightest timing constraint in the
whole integration.

Status and reversal for CLICK Pass use the same `/payment/status/...` and
`/payment/reversal/...` endpoints as §7.3/§7.4.

### 7.7 Payment link and in-page card form (direction C)

```
https://my.click.uz/services/pay/?service_id={service_id}&merchant_id={merchant_id}
  &amount={amount}&transaction_param={transaction_param}
  &return_url={return_url}&card_type={card_type}
```

| Param | Req. | Meaning |
|---|---|---|
| `merchant_id` | mandatory | Merchant id |
| `service_id` | mandatory | Service id |
| `transaction_param` | mandatory | Your order id — "corresponds to `merchant_trans_id` from SHOP API" (docs' own words) |
| `amount` | mandatory | Amount, **format `N.NN`** |
| `merchant_user_id` | optional | Your user id |
| `return_url` | optional | Where to send the user afterwards |
| `card_type` | optional | `uzcard` \| `humo` |

Same parameters as an HTML `<form method="get">`. Same URL as an Android/iOS
deeplink (CLICK SuperApp intercepts it; if not installed, the browser opens it).
With `return_url`, the CLICK app fires `Intent.ACTION_VIEW` on it and closes.

In-page card payment (`/click-pay-by-card`): load
`https://my.click.uz/pay/checkout.js`, either as a `<script>` inside your form
with `data-service-id`, `data-merchant-id`, `data-transaction-param`,
`data-merchant-user-id`, `data-amount`, `data-card-type`, `data-label`, or via
`createPaymentRequest({service_id, merchant_id, amount, transaction_param,
merchant_user_id, card_type}, callback)`. The form is auto-submitted afterwards
with an extra `status` parameter using the §5.4 payment-status values. Card data
never reaches you.

**In all of direction C, the returned `status` is a UX signal only.** Credit the
order from Complete.

---

## 8. Direction D — Advanced Shop / Split Shop (summary)

Included so you can recognise it, not implement it by accident.

JSON both ways, `Content-Type: application/json; charset=utf-8`.

| Action | Name | Notes |
|---|---|---|
| `0` | Getinfo | Optional. Unsigned. Given `service_id`, `action`, `params`, return `params` to display to the user, plus `error`/`error_note`. |
| `1` | Prepare | Signed (§3.3). Request: `click_paydoc_id`, `attempt_trans_id`, `service_id`, `action`, `params`, `sign_time`, `sign_string`. Response: `click_paydoc_id`, `attempt_trans_id`, `merchant_prepare_id` (**optional**), `params`, `error`, `error_note`. Split Shop adds `split: [{cntrg_id:int, amount:float}]`, whose amounts must sum to the payment amount. |
| `2` | Confirm | Signed. Adds `merchant_prepare_id` to the request. Response has `merchant_confirm_id` (optional). |
| `3` | Check | Signed. Response adds **`status`**: `0` = not yet processed (CLICK will retry), `1` = processing failed (CLICK will cancel the payment), `2` = processed successfully (CLICK marks the payment successful). |
| `4` | Compare | Unsigned. Request `service_id`, `action`, `from_date`, `till_date` (`YYYY-MM-DD HH:mm:ss`). Response: `requests` — a map keyed by `click_paydoc_id`, each `{click_paydoc_id, params}` — plus `error`, `error_note`. Reconciliation register. |

The `params` dictionary keys are drawn from a published vocabulary: `branch_id`,
`payment_account`, `payment_mfo`, `transit_account`, `transit_mfo`, `account`,
`act_num`, `address`, `amount`, `apart_num`, `birthday`, `caller_id`, `card_num`,
`category`, `contract`, `credit_id`, `cross_phone`, `date`, `email`, `full_name`,
`house_num`, `internet_package`, `invoice`, `login`, `order_num`, `phone_num`,
`property_id`, `receipt_num`, `region`, `security_code`, `service_type`, `TIN`.

Error codes are the same `-1 … -9` table as §5.1.

Note the action-number collision with SHOP API: in SHOP API Prepare is `0` and
Complete is `1`; in Advanced Shop Getinfo is `0`, Prepare is `1`, Confirm is `2`.
If one service can receive both shapes you must dispatch on body shape (form vs
JSON, presence of `params`), not on `action`.

---

## 9. Money and currency

**Everything is UZS. There is no currency field anywhere in the API.**

| Surface | Unit | Evidence |
|---|---|---|
| SHOP API `amount` (Prepare/Complete) | **som** | `/shop-api/requests` says explicitly «Сумма оплаты (в сумах)» |
| Payment link `amount` | **som**, formatted `N.NN` | `/click-button` says format `N.NN`; PHP example uses `number_format(1000, 2, '.', '')` → `"1000.00"` |
| `invoice/create` `amount` | **som** (typed `float`) | unit not stated; example `10000` alongside the som-denominated rest of the API |
| `card_token/payment` `amount` | **som** (typed `float`) | unit not stated |
| `click_pass/payment` `amount` | **som** (typed `float`) | unit not stated; example `500` |
| **Fiscalization** `Price`, `VAT`, `received_ecash`, `received_cash`, `received_card` | **TIYIN** (1 som = 100 tiyin) | `/merchant-api/fiscalization` says «(тийин)» on every one of these fields |

**This is the factor-of-100 trap.** The same logical amount is som in the payment
call and tiyin in the fiscalization call for that same payment. Example body from
the docs: `"received_card": 100000` — that is 1,000.00 som.

Rounding:

* SHOP API `amount` is a decimal string with (typically) two places. Both
  references compare against the order total with a **±0.01 tolerance**:
  ```php
  if (abs((float)$payment['total'] - (float)$request['amount']) > 0.01) → -2
  ```
  Copy that tolerance; do not compare floats for equality, and preferably parse
  to `Decimal`/minor units rather than `float`.
* Do all your own arithmetic in **tiyin integers** and convert at the boundary.
  Sending som, store tiyin ÷ 100 formatted to 2 places. Sending fiscal data, send
  tiyin directly.
* The docs never state a rounding rule for the som↔tiyin boundary, and never say
  whether CLICK accepts sub-som amounts in `invoice/create`. In practice UZS is
  transacted in whole som; assume amounts are integral som unless you have
  evidence otherwise.

**Django's amount check is wrong — do not copy it.** `click/utils.py` has:

```python
if abs(float(amount) - float(order.total) > 0.01):
```

The closing parenthesis is misplaced: this evaluates the comparison first, giving
a bool, then takes `abs()` of it. The result is that **underpayment passes the
check** (`amount - total` negative → `False` → `abs(False) == 0`), and only
overpayment by more than 0.01 is rejected. The PHP reference has the parentheses
correct. Where they disagree, believe PHP.

---

## 10. Fiscalization (ОФД / soliq)

Base `https://api.click.uz/v2/merchant/`, same `Auth` header (§3.1).

### 10.1 Submit item lines

```
POST /v2/merchant/payment/ofd_data/submit_items
{
  "service_id": 12345,
  "payment_id": 987654321,
  "items": [ ... ],
  "received_ecash": 0,
  "received_cash": 0,
  "received_card": 100000
}
```

| Field | Type | Req. | Meaning |
|---|---|---|---|
| `service_id` | integer | yes | |
| `payment_id` | long | yes | **CLICK's** `payment_id` — not your order id |
| `items` | `Item[]` | yes | Must contain at least one line |
| `received_ecash` | integer | yes | Amount paid in e-cash, **tiyin** |
| `received_cash` | integer | yes | Amount paid in cash, **tiyin** |
| `received_card` | integer | yes | Amount paid by card, **tiyin** |

`Item`:

| Field | Type | Req. | Meaning |
|---|---|---|---|
| `Name` | string(63) | **yes** | Product/service name, including unit of measure |
| `Barcode` | string(13) | no | Barcode |
| `Labels` | string[300] | no | Marking (`маркировка`) codes |
| `SPIC` | string(17) | **yes** | **ИКПУ / MXIK** — the tax-authority product classifier |
| `Units` | uint64 | no | Unit-of-measure code |
| `PackageCode` | string(20) | **yes** | Package code (`код упаковки`), the soliq package identifier that pairs with the MXIK |
| `GoodPrice` | uint64 | no | Unit price |
| `Price` | uint64 | **yes** | Line total, **tiyin** |
| `Amount` | uint64 | **yes** | Quantity |
| `VAT` | uint64 | **yes** | VAT amount, **tiyin** |
| `VATPercent` | int | **yes** | VAT rate, percent |
| `Discount` | uint64 | no | Discount |
| `Other` | uint64 | no | Other discounts |
| `CommissionInfo` | `CommissionInfo` | **yes** | Commission-receipt party |

`CommissionInfo`:

| Field | Type | Meaning |
|---|---|---|
| `TIN` | string(9) | ИНН (legal entity taxpayer id) |
| `PINFL` | string(14) | ПИНФЛ (individual taxpayer id) |

Docs: `CommissionInfo` must contain **either** `TIN` **or** `PINFL`. Both are
individually optional; the object is mandatory. Note the field names here are
**PascalCase**, unlike every other request body in the API, which is snake_case.

Response: `{"error_code": 0, "error_note": "Success"}`.

Field-name casing and the `Amount`/`Price`/`GoodPrice` types are quoted from the
docs. Note the odd typing: `Amount` is `uint64`, so **fractional quantities
appear to be unrepresentable**. Whether quantity is scaled (e.g. ×1000, as some
Uzbek OFD schemas do) is not stated. See §12 Q6.

### 10.2 Submit an already-fiscalised receipt

If you fiscalise through your own ОФД/КММ and just need to attach the receipt:

```
POST /v2/merchant/payment/ofd_data/submit_qrcode
{ "service_id": 12345, "payment_id": 987654321,
  "qrcode": "https://ofd.soliq.uz/epi?t=EZ000000000030&r=123456789&c=20221028171340&s=854971301623" }
```

| Field | Type | Req. | Meaning |
|---|---|---|---|
| `service_id` | integer | yes | |
| `payment_id` | long | yes | CLICK payment id |
| `qrcode` | string | yes | URL of the fiscal receipt |

Response: `{"error_code": 0, "error_note": "Success"}`.

### 10.3 Retrieve the fiscal evidence

```
GET /v2/merchant/payment/ofd_data/:service_id/:payment_id
```

Response — note the **camelCase**, unlike everything else:

| Field | Type | Meaning |
|---|---|---|
| `paymentId` | long | CLICK payment id |
| `qrCodeURL` | string | Link to the fiscal receipt on `ofd.soliq.uz` |

**What to store as evidence:** `paymentId` + `qrCodeURL`, keyed against your
order and your `merchant_trans_id`. The `qrCodeURL` embeds the terminal id (`t`),
receipt number (`r`), timestamp (`c`) and signature (`s`) — that URL *is* the
customer-presentable fiscal receipt. Store the URL string itself, not a rendered
QR image; the components are parseable if you later need the receipt number.

### 10.4 Timing relative to payment

* `submit_items` takes CLICK's `payment_id`, which only exists **after** a
  payment. So fiscalization is strictly **after** the payment, never before or
  during.
* **The docs do not state a deadline** — no "within N minutes", no statement of
  what happens if you never submit, no statement of whether a reversal
  (`payment/reversal`) requires or produces a corrective fiscal document.
* The docs also do not say whether `submit_items` is idempotent, or what a second
  submission for the same `payment_id` does.
* Practical sequencing that follows from the shapes: complete the payment → learn
  `payment_id` (from `card_token/payment`, `click_pass/payment`, or by resolving
  `merchant_trans_id` via `status_by_mti`) → `submit_items` → poll
  `GET ofd_data/:service_id/:payment_id` for the `qrCodeURL` → persist → show to
  the customer. The docs do not say how long the ОФД round-trip takes, so the GET
  must be treated as eventually-consistent and retried.

See §12 Q4–Q7.

---

## 11. Telegram payments

Source: `/additional/telegram-payments`. This is **Telegram's** Bot Payments API
with CLICK as the payment provider; CLICK's own API is not involved in the flow.

### 11.1 The provider token

* Created via **@BotFather**: `/mybots` → pick bot → *Settings → Payments* →
  choose **CLICK Uzbekistan**.
* **Test:** *Connect CLICK Terminal Test* → redirected to **@CLICKtest** →
  *Start* → «Авторизоваться».
* **Live:** *Connect CLICK Terminal Live* → redirected to **@CLICKTerminal** →
  *Start* → «Авторизоваться» → a browser authorisation form → log in with the
  supplier-cabinet credentials issued **by the bank** → pick the service. The
  token comes back containing the literal segment `:LIVE:`, e.g. `123:LIVE:XXXX`.
* A single bot may hold **several provider tokens** for different users, goods or
  services.
* The token is a live payment credential. Docs: do not share it.

### 11.2 Forming the invoice

* Use Telegram's **`sendInvoice`** method, passing the BotFather-issued token as
  **`provider_token`**.
* Invoices can be sent **only to a user who has written to the bot**. Groups and
  channels are rejected.
* The CLICK docs give no `provider_data` schema, no currency code, no
  `max_tip_amount` guidance, and no statement about which Telegram `currencies`
  entry applies. Everything beyond `provider_token` is plain Telegram Bot API
  (`prices` as `LabeledPrice[]` in the currency's minor unit, per Telegram's own
  contract). See §12 Q8.

### 11.3 How the result reaches you

Three steps, all over the Telegram Bot API — **no CLICK webhook is involved**:

1. `sendInvoice` — bot sends the invoice with a Pay button.
2. User presses **Pay** → Telegram delivers an `Update` containing
   **`pre_checkout_query`** with the full order information. **Your bot must
   answer with `answerPrecheckoutQuery` within 10 seconds** or the transaction is
   cancelled. This is your last chance to reject (e.g. out of stock) with a
   user-readable message.
3. On success Telegram delivers a message containing **`successful_payment`** from
   the user. The user gets a receipt they can reopen at any time.

So the fulfilment trigger is `successful_payment`, and the reservation trigger is
`pre_checkout_query` — structurally the same two-phase shape as SHOP API
Prepare/Complete, but on Telegram's transport with Telegram's field names.

### 11.4 Go-live checklist (docs' own)

Enable 2FA on the controlling Telegram account; implement `/terms` and
`/support`; tell users Telegram support does not handle purchases made through
your bot; have stable infrastructure with backups of payment data. Refund
handling and dispute liability are the bot owner's.

### 11.5 Commercial prerequisites (all CLICK acceptance, not just Telegram)

Legal entity or individual entrepreneur; a payment-acceptance contract with one
of the connected banks (Alokabank, Agrobank, Davr bank, Uzpromstroybank, Kishlok
Kurilish bank, Uzbek-Turkish bank, Universal bank, Savdogar bank, Trast bank,
Turkiston bank, Xalq banki, Mikrokredit bank, Orient Finance bank, Asia Alliance
Bank, Ipak Yuli bank — list stated to be growing). No subscription or connection
fee; the bank takes a percentage of CLICK-settled turnover. Documents required:
registration certificate, licence if applicable, director appointment order,
director's passport, founders' meeting minutes, charter (all pages), domain
contract, and a connection letter for Asia Alliance Bank. Enquiries:
+998 (71) 231-08-83.

---

## 12. Open questions — the integration-discovery list

Ask CLICK these before writing the retry policy. Each is something the docs do
not answer and that I refused to guess at.

**Q1 — The `error_code` enumeration for MERCHANT API is not published.**
`/merchant-api/errors` documents HTTP statuses only. Every JSON example shows
`error_code: 0`. There is no table of what "insufficient funds", "card blocked",
"wrong OTP", "invoice expired" or "service disabled" look like. Without it you
cannot classify direction-B failures as retryable vs terminal, and you cannot
show a useful message to a customer. **Ask for the full `error_code` /
`error_note` table**, and specifically which codes on `card_token/payment` and
`click_pass/payment` are safe to retry.

**Q2 — Invoice status field name and value set.** The docs call the field
`invoice_status` with a sibling `invoice_status_note`; both reference
implementations read `status`. Only `-99` (Deleted) is documented. **Ask: is the
field `status` or `invoice_status`, and what is the full value set?** Until
answered, read both keys and treat any positive value as paid, per the references.

**Q3 — Prepare/Complete replay semantics.** The docs never state whether a
repeated Prepare with the same `merchant_trans_id` is idempotent. The Django and
PHP references *imply* it is, by looking the order up by `merchant_trans_id`
first and returning that order's primary key as `merchant_prepare_id` rather than
creating a new transaction row. **Ask: does CLICK guarantee at-most-once Prepare?
How many times will it retry Complete, on what schedule, and with what timeout?
Does a retried Complete reuse `click_trans_id` or mint a new one?** (Advanced
Shop documents that a repeat attempt has a new `attempt_trans_id`; SHOP API does
not say the equivalent.)

**Q4 — Fiscalization deadline.** No time window is stated between payment and
`submit_items`. **Ask: what is the deadline, what happens if it is missed, and is
there a penalty or an automatic reversal?**

**Q5 — Fiscalization idempotency.** **Ask: what does a second `submit_items` for
the same `payment_id` do — reject, replace, or duplicate the receipt?** This
matters because `submit_items` is a mutating call with no idempotency key, so a
timeout leaves you in an uncertain state.

**Q6 — Fiscal `Amount` scaling.** `Amount` is typed `uint64`, which cannot
express 1.5 kg. **Ask whether quantity is scaled (×1000 is common in Uzbek OFD
schemas) and what `Units` codes are valid.** Also ask whether `received_ecash +
received_cash + received_card` must equal the sum of `Price` across `items`, and
whether `VAT` is included in `Price` or additional to it — neither is stated, and
both change the numbers you send.

**Q7 — Reversal and fiscalization.** **Ask whether `payment/reversal` requires a
corrective fiscal document, and if so through which call.** Nothing in
`/merchant-api/fiscalization` mentions refunds.

**Q8 — Telegram invoice details.** The CLICK page covers only token issuance and
the three-step flow. **Ask: what currency code and minor-unit convention does the
CLICK provider expect in `sendInvoice.prices`, what `provider_data` (if any) is
required — in particular whether item-level ИКПУ/MXIK must be passed through
`provider_data` for fiscalization — and how Telegram-originated payments appear
in the merchant cabinet and in `payment/status_by_mti`.**

**Q9 — Payment-link signing.** The `/click-button` page shows an unsigned link;
the Django reference builds a signed one against a different URL (§3.4). **Ask:
is `https://my.click.uz/pay/` with `SIGN_STRING` still supported, and is there
any way to make the redirect amount tamper-proof?** Until answered, assume the
redirect is unauthenticated and enforce the amount server-side in Prepare.

**Q10 — `status_by_mti` date segment.** The path carries a trailing `YYYY-MM-DD`
with no explanation. **Ask: which date is it (payment date? your submission
date?), what timezone, and what happens if the payment falls outside it?** This
matters because `status_by_mti` is the recovery path for an uncertain
`card_token/payment`, and getting the date wrong turns a recoverable state into
an apparent "no payment found" — which is exactly the condition under which you
would wrongly retry and double-charge.

**Q11 — `Auth` timestamp tolerance.** No validity window is documented. **Ask
the accepted clock skew**, so you know whether NTP drift can cause 401s.

**Q12 — Callback source IPs.** Nothing documents which addresses CLICK calls
Prepare/Complete from. **Ask for the IP allowlist** — the MD5 signature is the
only authentication on the endpoint that credits orders, and MD5 with a
shared-secret prefix is not a strong primitive.

---

## 13. Reference-implementation defects (do not copy)

Both samples are official; both contain bugs. Recorded because "the reference
does it this way" is otherwise a tempting argument.

**`click-integration-django`** (`click/utils.py`, `click/__init__.py`):

1. `abs(float(amount) - float(order.total) > 0.01)` — misplaced parenthesis;
   **underpayment passes the amount check**. See §9.
2. `isset(data, columns)` returns `False` on the first *present* column, so
   `isset(...)` is `True` only when **every** required field is missing.
   Consequently the `-8` check fires only for a completely empty body, not for a
   partial one. PHP's `is_not_possible_data` is correct.
3. `verify_card_token()` POSTs to `/card_token/payment` with an `sms_code` — the
   correct endpoint is `/card_token/verify`.
4. `payment_with_token()` reads `response.status_code` before `response` is ever
   assigned; the method cannot work — it never issues the request.
5. Endpoint base is `https://api.click.uz/v1/merchant`; current is **v2**.
6. `create_invoice`/`payment_with_token` read `self.payment.transactions_id`
   while `PaymentButtonForm` uses `self.payment.transaction_id` — one of the two
   is a typo.
7. `order_load` refuses any `payment_id > 1_000_000_000` for no stated reason.
8. Its `complete()` calls `order_load(order_id)` before validating the request,
   so a Complete for an unknown order raises a 404 from `get_object_or_404`
   instead of returning `-5`.

**`click-integration-php`**:

1. `on_checking_with_merchant_trans_id` issues **`DELETE`** to
   `payment/status_by_mti/{service_id}/{merchant_trans_id}` — wrong verb, and
   missing the documented trailing `YYYY-MM-DD`.
2. `on_invoice_created` references `$payment_id` in the error branch, which is
   never defined in that scope.
3. `Application::session()` compares a static shared token against the `Auth`
   header for its own admin endpoints — unrelated to CLICK's `Auth` scheme, and a
   trap if you mistake it for the real thing. Note it deliberately exempts
   `/prepare` and `/complete` from that check, which is correct: direction A has
   no `Auth` header, only the signature.
4. `on_invoice_checked` reads `$result['status']` while the docs name the field
   `invoice_status` (see Q2).

Where the two disagree on anything load-bearing — the signature construction, the
amount comparison, the API version, `card_token/verify` — **the PHP reference is
right and matches the docs.** Django's SHOP API signature construction is correct
and matches; everything else in Django should be treated as stale.

---

## 14. Implementation checklist

1. Implement Prepare and Complete first. Nothing else credits an order.
2. Verify `sign_string` on the **raw** form values before touching the database.
   Constant-time compare. Reject with `-1`.
3. Validate `service_id` matches yours.
4. Make `merchant_prepare_id` a deterministic function of the order.
5. Credit inside a DB transaction guarded by order state; a replay must fall out
   as `-4`, not as a second credit.
6. Never return an error from Complete for a business failure. Answer `0`, then
   `DELETE /payment/reversal/...`.
7. Always return HTTP 200 with the error in the JSON body.
8. Store `click_trans_id`, `click_paydoc_id`, `merchant_trans_id`, `payment_id`,
   `sign_time`, and the raw request body, per callback.
9. On any timeout or 5xx from a mutating direction-B call: **do not retry**.
   Query `status_by_mti`, then decide.
10. Do arithmetic in tiyin integers. Convert to som at the SHOP API / payment
    link / invoice boundary; send tiyin to fiscalization.
11. Fiscalise after the payment, persist `qrCodeURL` as the evidence of record.
12. Get answers to §12 Q1, Q3 and Q10 before the first real transaction.
