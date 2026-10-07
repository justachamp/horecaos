# Didox (e-invoicing operator)

ADR 0096's first adapter, `DidoxEInvoicingOperator`. Read this before changing it, and before
trusting any claim here about Didox's behaviour: **HorecaOS has no Didox account**, so nothing in
this adapter has been run against Didox. The adapter is built against the operator's partner API
as its published partner SDK (npm package `didox`, version 1.0.6, read on 2026-10-07) calls it, and
tested against a fake that answers the way that SDK's types say Didox does.

## What is used

| Purpose | Call | Notes |
|---|---|---|
| Sign in | `POST /v1/auth/{taxId}/password/{locale}` body `{"password": ...}` | Header `Partner-Authorization: <partner token>`. Answers a `token`, valid 360 minutes (the SDK says so); the adapter caches it 300 minutes per installation, in memory only |
| Create an invoice draft | `POST /v1/documents/002/create` | Headers `user-key: <token>` and `Partner-Authorization`. `002` is the invoice (счёт-фактура) document type. The body is the SDK `InvoiceBuilder`'s output, below |
| Read one | `GET /v1/documents/{id}` | `404` is read as "Didox holds no such document" |
| Find by number | `GET /v2/documents?owner=1&doctype=002&name={number}&page=1&limit=10` | `owner=1` is outgoing documents; `name` filters on the document number (the SDK maps its `number` filter to `name`). Resolves a create whose answer was lost |
| Base URLs | `https://api-partners.didox.uz` (production), `https://stage.goodsign.biz` (the SDK's non-production host) | Both are rows of the approved-endpoint catalogue (`V0516`); nothing is called at any other host |

## The document

`DidoxEInvoicingOperator.payload` maps the provider-neutral `EInvoiceDocument` to the SDK's invoice
payload: `FacturaDoc` (our number and date, ISO `yyyy-MM-dd`), `ContractDoc`, `SellerTin` / `Seller`
and `BuyerTin` / `Buyer` (name, VAT registration code, bank account, MFO, address), and
`ProductList` with one product per statement line: `OrdNo`, `Name`, `CatalogCode` / `CatalogName`
(the ИКПУ classification), `PackageCode` / `PackageName`, `Count`, `DeliverySum` (net), `VatRate`
(a percentage), `VatSum`, `DeliverySumWithVat`, `WithoutVat`. Money is a decimal in sums
(150000 tiyin is `1500.00`). `Origin` is omitted: the classification, not the platform, decides it.

**Two mapping choices to check against a live account first.**

* The SDK sends `Summa` as the line's gross (`DeliverySumWithVat`), not as a unit price; this
  adapter does the same because it is the only published mapping.
* `CatalogName` is not in the SDK's builder output but is in its README's product fields; it is sent.

## State

Didox reports a numeric status. `DidoxStatus` reads only the codes the SDK's `DocumentStatus`
table makes unambiguous: `0` draft, `1` / `6` / `7` sent (sent, partly signed, waiting for the
signature), `2` signed, `3` refused, `4` cancelled. **Every other code reads as `UNKNOWN`** with the
raw code stored beside it, so a wrong or missing mapping is visible and fixable in one place.

A document created through the API is a draft: HorecaOS never holds a signing key, so a person at
HorecaOS signs and sends it from Didox's own product, and the buyer signs there. The next state
refresh shows `SENT`, then `SIGNED` or `REFUSED`.

## What is not known (and so is not guessed)

* **The answers.** The SDK types them `any`. The adapter looks for the document id under `_id`,
  `id`, `documentId`, `document_id`, `doc_id`, `uuid` (and under `data` / `document`) and for the
  status under `status`, `doc_status`, `docStatus`, `statusId`; a create answered with no readable
  id is `Uncertain` (resolved by the number lookup), and a state with no readable status is
  `Unavailable`, never a default.
* **A callback.** If Didox pushes state changes, nothing here listens: the state is polled (every 15
  minutes for documents not yet signed, refused or cancelled).
* **Cancelling.** No cancel call is made; cancelling needs the operator's signers. A cancelled
  document is seen by the next refresh.
* **Idempotency.** None is documented on a create. A create is never repeated; see
  `docs/routes/einvoicing-operator-api.md`.

## Connecting HorecaOS's account

1. Obtain the partner token and a login for HorecaOS's own taxpayer number from Didox.
2. Put one JSON object in the secrets manager (ADR 0028, category `provider_einvoicing`):
   `{"partnerToken": "...", "password": "..."}` with `bao kv put`, and note its reference.
3. Control plane > E-invoicing > Didox: enter the reference, the seller's taxpayer number and name
   (and address, VAT registration code, bank account and MFO as they should read on the invoice) and
   the login language, then activate.
4. Send one statement to a test buyer on `didox_stage` before production (a second installation row
   pointing at `didox_stage` is a deliberate act: the seeded one names production).
