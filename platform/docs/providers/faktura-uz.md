# Faktura.uz (e-invoicing operator)

ADR 0096's second adapter, `FakturaEInvoicingOperator`. **HorecaOS has no Faktura.uz account**, so
nothing here has run against the operator. It is built from the operator's own published Swagger
document (`https://api.faktura.uz/swagger`, spec at `/swagger/docs/V1`, read on 2026-10-07), which
is considerably better than what Didox publishes, and tested against a fake that answers as that
document's models say.

## What is used

| Purpose | Call | Notes |
|---|---|---|
| Sign in | `POST https://account.faktura.uz/token` (form-encoded) | OAuth password grant: `grant_type=password`, `username`, `password`, `client_id`, `client_secret`. Answers `access_token` (bearer), `expires_in` (604799 s, 168 h). Cached in memory, a day short of its life |
| Create invoices | `POST /Api/Document/ImportDocumentRegister?companyInn={taxpayer number}` | Body `document_container`: `{"invoices": [...]}`. `Authorization: Bearer ...`. Answers `SuccessItems[]` (with `UniqueId`) and `ErrorItems[]` (with `ResultCode`, `Message`) |
| State | `POST /Api/GetDocumentStatus?companyInn=...` body `{"DocumentUniqueIds": [id]}` | `Data.DocumentStatuses[]` of `{UniqueId, Status, StatusDescription}`; `Success: false` with error code `1007` is "incorrect document uid" and is read as not found |
| State, booleans | `GET /Api/Document/GetDetails/{uid}?companyInn=...` | `isSent`, `isOwnerSigned`, `isContractorSigned` |
| Find by our id | `GET /Api/Document/GetDocumentByClientDocumentUid?clientId=...&companyInn=...` | `404` is read as "holds no such document". Resolves a create whose answer was lost |
| Hosts | `https://api.faktura.uz` (calls), `https://account.faktura.uz` (token) | One approved-endpoint row (`V0516`) names both hosts in its `egress_allowlist`; the gateway refuses any other. No non-production host is published |

## The document

One `invoices` item per statement: `id` (our identifier, 32 hex), `head` (a file name, versions, and
`sender.sender_info` / `receiver.receiver_info`: `INN`, `company_name`, `company_vat_code`, `address`,
`bank_details`), and `document`: number, date (ISO), contract number and date, `customer_system_id`,
`items[]` (description, `volume`, `unit_price`, `subtotal`, `excise`, `vat`, `subtotal_with_vat`,
`catalog` code and name, `measurement_unit` and its code, `origin` `3` = service),
`column_summary_values` and `column_summary_values_in_words`. Every amount is a decimal **string**
(that model's convention); the amounts in words are written out in Russian
(`RussianAmountInWords`) because the model marks them required.

**Assumptions to check against a live account first.**

* Dates are sent ISO (`yyyy-MM-dd`); the Swagger does not state a format.
* The seller's and buyer's one-line registered addresses go in `address.street`; the model wants
  parts (`region`, `city`, `street`, ...) and the platform holds one line.
* `document_version`, `factura_type` and the operator elements `sender_operator` /
  `receiver_operator` are omitted.

## State

The status table is served only by an authenticated `GET /Api/Document/GetDocumentStatuses` and
the Swagger names two rows of it ("Archived" = published, id 24; "ArchiveCanceled" = cancelled,
id 34). So the state is read from the documented booleans: not `isSent` is `DRAFT`; `isSent` is
`SENT`; `isContractorSigned` is `SIGNED`; and the **status text**, the one place a refusal or a
cancellation is named, overrides them when it contains a refusal stem (`отклон`, `отказ`, `reject`,
`declin`, `refus`) or a cancellation stem (`аннул`, `отмен`, `cancel`). The raw `Status:Description`
is stored beside the reading. **This mapping is provisional until one real document has been
through it**; the stems are the thing to correct.

## Not built

* Faktura.uz's callbacks (`POST /api/WatchDocument/Create`, "Работа с коллбеками"): state is polled.
* Cancelling or deleting a document (the Swagger has calls; they need the operator's signers).
* Attachments, multi-account users (`companyInn` is always the seller's own taxpayer number).

## Connecting HorecaOS's account

1. Obtain a Faktura.uz API login, `client_id` and `client_secret` for HorecaOS's own taxpayer number
   (Account settings > API, as the Swagger describes).
2. Put one JSON object in the secrets manager (ADR 0028, category `provider_einvoicing`):
   `{"username": "...", "password": "...", "clientId": "...", "clientSecret": "..."}` with
   `bao kv put`, and note its reference.
3. Control plane > E-invoicing > Faktura.uz: enter the reference, the seller's taxpayer number and name
   (and address, VAT registration code, bank account and MFO), then activate.
