# External facts for the money wave (split tender, wallet, billing)

**Last executed:** never. Each section below is one fact that exists outside this repository. The code
that needs it is built against a fake or an unbound installation, so the platform runs without it and does
exactly nothing with it until it arrives. A section says **what exists** now, **where the value goes** when
it is known, and **how to prove the connection** once it has been put there. Nothing here is guessed into a
default: where a number or a code is unknown the adapter fails closed and the section names the setting that
will carry it.

Companion runbooks, which hold the steps: [`../connect-card-merchant-account.md`](../connect-card-merchant-account.md)
(the card account) and [`../connect-einvoicing-operator.md`](../connect-einvoicing-operator.md) (Didox and
Faktura.uz). Provider notes: [`../../providers/click-card-token.md`](../../providers/click-card-token.md),
[`../../providers/didox.md`](../../providers/didox.md), [`../../providers/faktura-uz.md`](../../providers/faktura-uz.md).

| # | Fact | Owner | Blocks |
|---|---|---|---|
| 1 | A Click merchant account for HorecaOS, and its service id | Owner, finance | Any real card charge |
| 2 | Sandbox credentials | Owner, with Click | The sandbox walk |
| 3 | Click's written confirmation of off-server tokenisation | Owner, with Click | Adding a card from the console |
| 4 | Which card provider | Owner | Nothing now (Click is built); a second provider is a second adapter |
| 5 | The sandbox walk | Engineer with 1 to 3 | Turning card billing on |
| 6 | Real bank details, and how prepaid money is taxed | Finance | A first real invoice for prepaid money; tax on any statement |
| 7 | Didox and Faktura.uz accounts | Owner | Any e-invoice leaving as a draft |
| 8 | Finance's line codes and VAT | Finance | A first e-invoice that is not deleted again |
| 9 | Operator webhooks (state callbacks) | Owner, with each operator | Nothing: state is polled |

## 1. Click merchant account and service id

**What exists.** `ClickCardProviderAdapter` (provider type `CLICK_CARD`) speaks Click's card-token calls through
the Camel route `cardbilling.provider-api.v1`; two approved environments, `click_card_production` and
`click_card_sandbox`, both `https://api.click.uz/v2/merchant` (V0689); a platform card installation that can be
declared and activated; and a fake HTTP provider that stands in for Click on a laptop. No installation is
seeded and none is active: with none active a CARD tenant is collected like an INVOICE tenant and the wallet
says card payments are not available.

**Where the value goes.** Click issues four values at registration: `merchant_id`, `service_id`,
`merchant_user_id` and `secret_key`.

- `service_id` is a public identifier. It is the installation's `externalAccountReference`.
- `merchant_user_id` and `secret_key` are the credential. They go in OpenBao as one JSON object, category
  `provider_payment`, owner `platform`:

  ```sh
  bao kv put horecaos/production/provider_payment/platform/click-card \
    value='{"merchantUserId": "<merchant_user_id>", "secretKey": "<secret_key>"}'
  ```

  and the installation names `horecaos:production:provider_payment:platform:click-card` as its
  `secretReference`. The same secret derives the enrolment-session signing key, so no second secret exists.
- `merchant_id` is not used by the card-token calls. Record it with the account; nothing reads it.

**How to prove the connection.** Declare the installation in `DRAFT` (the request is in
`connect-card-merchant-account.md`, step 2) and read it back: `GET /api/v1/control-plane/billing/card-installations`
shows it with `secretConfigured: true` and no reference. Activation is refused without a reference. After
activation, a test tenant's wallet panel stops saying card payments are unavailable, and an enrolment returns a
tokenise URL on `api.click.uz` rather than an error. An unreadable credential answers `PROVIDER_NOT_CONNECTED`,
never a card decline.

## 2. Sandbox credentials

**What exists.** Click's documentation names one host and no sandbox host, so `click_card_sandbox` is the same
URL with `is_production = false`. The fake models the calls and invents every `error_code` other than 0, and says
so.

**Where the value goes.** A test `service_id` and its credential, in a second OpenBao secret
(`horecaos/production/provider_payment/platform/click-card-sandbox`) and a second installation declared with
`environmentCode: click_card_sandbox`. If Click names a separate sandbox host, a migration moves
`click_card_sandbox.base_url`; nothing else changes. A sandbox installation is never the active one in production.

**How to prove the connection.** The same read as in 1, on the sandbox installation; then section 5.

## 3. Click's written confirmation of off-server tokenisation

**What exists.** The wallet console takes the card number into a field of HorecaOS's own page and posts it from
the browser to Click's `card_token/request`; it never goes to a HorecaOS endpoint, is held in memory until the
answer arrives and is cleared (`card-tokeniser.ts`). The platform's session reference is signed and carries the
tenant and an expiry. The published example of that call carries no `Auth` header while the same page says
every request must, and nothing published says Click allows it from a page we serve.

**Where the value goes.** Click's answer, in writing, to four questions: may the call be made from the
console's origin with the public `service_id` and no `Auth` header; is the call allowed cross-origin from that
origin; is a page of ours handling a card number for one request within Click's rules, and what card-data scope
does Click put it in; does a persistent token (`temporary: 0`) expire, and when. File it with the account and
quote it in `docs/providers/click-card-token.md`, "What it does not say", item 1.

**How to prove the connection.** In the sandbox walk (5), the browser's network panel shows the POST to
`api.click.uz/v2/merchant/card_token/request` answered from the console's origin, and a search of the
application logs, the database and the outbox for the test card's number finds nothing. If Click says no, the
handoff becomes a hosted page: `begin` in `ClickCardProviderAdapter` and the panel's `card-tokeniser.ts`
change, and the other three calls do not.

## 4. Which provider is chosen

**What exists.** The owner's batch-21 decision was Click for the first provider (ADR 0095 named Click or
Payme; the information architecture names Click and Atmos). Payme's Subscribe API is declared out of scope in
`payme-merchant-api.md` and no note exists for Atmos. A second provider is a second class implementing
`CardProviderAdapter` with its own `providerType()`, its own route and its own environments; it is not a change to
this one.

**Where the value goes.** The installation's `providerType` (`CLICK_CARD`). At most one installation is
`ACTIVE`, so choosing is activating.

**How to prove the connection.** `GET /api/v1/control-plane/billing/card-installations` lists exactly one
`ACTIVE` installation and its `providerType` is the provider the owner chose.

## 5. The sandbox walk

**What exists.** Everything the walk exercises, against fakes: the contract suite `CardProviderContract` runs
through the fake and through the real adapter on a socket (`FakeCardProviderTests`,
`ClickCardProviderAdapterTests`), and `ClickCardWalletFlowTests` drives enrolment, a top-up and a lost answer
through the wallet services. No sandbox has been called.

**Where the value goes.** The results, into `docs/providers/click-card-token.md` ("What it does not say") and
into the installation's configuration: `notFoundErrorCodes` (the `error_code` Click returns for an unknown
`merchant_trans_id`) and, if the date segment of `status_by_mti` turns out to mean something narrower,
`lookbackDays`.

**How to prove the connection.** With a test tenant on the sandbox installation:

1. Add a card in Finance > Wallet. Expect an SMS code, then a card on file showing a last four and a brand.
2. Top up 1 000 som by card. Expect the ledger to hold one `TOP_UP` carrying Click's `payment_id`, and Click's
   cabinet to show exactly one charge whose `merchant_trans_id` is the top-up's row id.
3. Let the settlement sweep run (or call it) after the top-up and confirm Click's cabinet still shows one charge: the sweep asks `status_by_mti` for the same key and must not charge again.
4. Record, from the real answers, each of the seven things listed under "What it does not say", above all the
   `payment_status` of a card charge, the `error_code` for an unknown transaction, and which date `status_by_mti`
   wants. Set `notFoundErrorCodes` from it. Until it is set the adapter sends no charge it cannot rule out, which
   is the safe direction and looks like `PAYMENT_LOOKUP_UNAVAILABLE`.
5. Remove the card and confirm `status` still answers for the earlier charge.

## 6. Real bank details and the tax treatment of prepaid money

**What exists.** A placeholder bank-details row; no invoice is issued while it stands. Replacing it is two
people's act. Amounts in the wallet, on prepayment invoices and on statements are before tax; the wallet
computes no tax (ADR 0095 decision 7, ADR 0088).

**Where the value goes.**

- *Bank details.* Finance proposes them with `POST /api/v1/platform-admin/commercial/billing/bank-details`
  (beneficiary, bank name, account, MFO, taxpayer number, reason); the first call answers `AWAITING_APPROVAL` and
  the identical call after a different person approves writes them. Invoices already issued keep their own.
- *Tax.* Finance states whether money received in advance is taxed when it arrives or when it is used, at what
  rate, and whether statement amounts are net or gross of VAT. The statement is a frozen document, so this is
  stated once and applied from a stated date, never retrofitted. It belongs in a new ADR that supersedes
  ADR 0095's decision 7, and in the line classification (section 8).

**How to prove the connection.** `GET /api/v1/control-plane/billing/bank-details` answers `configured: true`;
a test tenant's prepayment invoice then carries the real details. For tax, finance reviews one issued test
statement and one prepayment invoice against the stated rule and signs it off in the ADR.

## 7. Didox and Faktura.uz accounts

**What exists.** Both adapters, both fakes, the approved endpoints and two installations seeded unbound and
`DRAFT` (V0516). The merchant can read the state of their own e-invoice (Finance > Subscription). Nothing is
sent until an account is bound and activated.

**Where the value goes.** Didox: `{"partnerToken": "...", "password": "..."}`. Faktura.uz:
`{"username": "...", "password": "...", "clientId": "...", "clientSecret": "..."}`. Each in OpenBao under
`horecaos/production/provider_einvoicing/platform/<operator>`, bound with
`PUT /api/v1/platform-admin/commercial/einvoicing/installations/{id}` together with the seller's identity.
The full steps are in `connect-einvoicing-operator.md`.

**How to prove the connection.** Activation answers `connected: true`. Sending one statement for a test tenant
leaves one draft at the operator, numbered with the statement number, which "Refresh state" reads as `DRAFT`.
The draft is deleted at the operator and released here. There is no non-production host for Faktura.uz, so its
first walk is a real draft in the live account; Didox's first walk needs a stage installation, which is a
developer's migration.

## 8. Finance's line codes and VAT

**What exists.** One classification row per statement line kind (`PLAN`, `MODULE`, `OVERAGE`, `EARLY_EXIT`,
`DEPOSIT`), seeded `provisional` with engineering's guesses (V0516): an ИКПУ code, a unit code and 12% VAT added
on top. The seller's VAT registration code decides whether VAT is charged at all.

**Where the value goes.** `PUT /api/v1/platform-admin/commercial/einvoicing/line-classifications/{lineKind}`
with `confirmed: true`, by finance, one row at a time. The row then records who confirmed it and when.

**How to prove the connection.** `GET /api/v1/control-plane/einvoicing/line-classifications` shows no row
`provisional`. The first draft after that, read in the operator's product, carries the stated codes and the
stated VAT line by line.

## 9. Operator webhooks

**What exists.** Nothing listens. State is polled every 15 minutes for documents not yet signed, refused or
cancelled, and on demand by "Refresh state". Faktura.uz publishes callbacks (`POST /api/WatchDocument/Create`);
Didox's are not documented.

**Where the value goes.** If either operator is asked for callbacks, their answer needs to say: the URL form
and method, how a call is authenticated (a signature scheme or a shared secret, which would be a secret
reference), the source addresses, retry behaviour, and the payload. A receiver is a new route with its own
descriptor and its own approved inbound path, not an addition to the polling sweep.

**How to prove the connection.** Not applicable today. Polling is the connection; the freshness is at most one
sweep interval (15 minutes) and the sweep's metric is `horecaos.einvoicing.route`. A callback, when built, is
proved by sending a test document and seeing its state change inside a minute without a refresh.
