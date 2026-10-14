# Connecting HorecaOS's Didox and Faktura.uz accounts

**Last executed:** never, and it cannot be yet: HorecaOS has no account with Didox or with Faktura.uz
(ADR 0096's open input, owned by finance and the owner). What exists is everything this runbook ends
in: both operators' adapters, the approved endpoints (V0516), two installations seeded unbound, the
control-plane screen that binds them, and fakes behind each adapter that prove the flow. Per ADR 0023 a
runbook that has never been executed is a draft; run it once against the operator's stage or a real
draft you delete again, and put the date and the operator here.

**How to get back.** Suspending an account (step 7) stops every send through it at once. Documents already
sent keep their last known state and resuming asks the operator for the current state of each. A draft that
reached the operator is deleted in the operator's own product and then released here (*A draft that went
out wrong*). Nothing in this runbook sends a signed document: HorecaOS never holds a signing key (ADR 0096
decision 3), so every document leaves as a draft that a person signs.

## What the owner provides, and what only a developer can

| Needs | Who |
|---|---|
| A Didox partner token and a login for HorecaOS's own taxpayer number | The owner, with Didox |
| A Faktura.uz API login, `client_id` and `client_secret` for the same taxpayer number | The owner, with Faktura.uz |
| The seller's identity as it should read on an invoice: taxpayer number, legal name, registered address, VAT registration code (if HorecaOS is registered for VAT), bank account, bank code (MFO), bank name | Finance |
| The classification code, unit and VAT rate of each kind of statement line, stated (not provisional) | Finance. The seeded rows are engineering's guesses, flagged provisional on screen |
| A second installation row on `didox_stage` for a first walk that is not a legal document | A developer, as a migration. The seeded Didox row names production, and no endpoint creates an installation |

The adapters, the endpoints (`didox_production`, `didox_stage`, `faktura_uz_production`) and the two
installation rows exist already. `docs/providers/didox.md` and `docs/providers/faktura-uz.md` say what each
adapter assumes about its operator; the first walk is what turns those assumptions into facts.

## Steps

1. **Put each operator's login in OpenBao**, category `provider_einvoicing`, owner `platform`. The value is
   one JSON object of the operator's own login fields, and goes in through the secrets manager and never
   through this API (ADR 0028):

   ```sh
   bao kv put horecaos/production/provider_einvoicing/platform/didox \
     value='{"partnerToken": "<from Didox>", "password": "<the login password>"}'
   bao kv put horecaos/production/provider_einvoicing/platform/faktura-uz \
     value='{"username": "<login>", "password": "<password>", "clientId": "<client id>", "clientSecret": "<client secret>"}'
   ```

   The references the platform is given are the same paths with colons:
   `horecaos:production:provider_einvoicing:platform:didox` and
   `horecaos:production:provider_einvoicing:platform:faktura-uz`.
   Check: `bao kv get horecaos/production/provider_einvoicing/platform/didox` lists the keys
   (do not print the values into a terminal that is being recorded).

2. **Read the accounts and their versions.** `GET /api/v1/control-plane/einvoicing/installations` lists both
   as `DRAFT`, `connected: false`, with `missing` naming `SECRET_REFERENCE`, `SELLER_TAXPAYER_NUMBER` and
   `SELLER_NAME`. Note each `installationId` (`018f9c10-5000-7000-8000-0000000000d1` is Didox,
   `…0000000000f1` is Faktura.uz) and the `ETag` of the read.

3. **Bind each account** (`COMMERCIAL_EINVOICING_MANAGE`, platform scope). The secret reference and the
   seller's public identity; `config` is replaced whole, so send every field you want kept:

   ```
   PUT /api/v1/platform-admin/commercial/einvoicing/installations/018f9c10-5000-7000-8000-0000000000d1
   If-Match: "<the version from step 2>"
   {"displayName": "Didox",
    "secretReference": "horecaos:production:provider_einvoicing:platform:didox",
    "config": {"sellerTaxpayerNumber": "<TIN>", "sellerName": "<legal name>",
               "sellerAddress": "<registered address, one line>",
               "sellerVatRegistrationCode": "<only if registered for VAT>",
               "sellerBankAccount": "<account>", "sellerBankCode": "<MFO>", "sellerBankName": "<bank>",
               "locale": "ru"},
    "reason": "binding HorecaOS's Didox account"}
   ```

   Faktura.uz is the same request against `…0000000000f1`, with its own reference. The taxpayer number is also
   the login both operators know HorecaOS by. Check: the read now shows `secretBound: true` and the
   seller fields, and `missing` is empty. With no `sellerVatRegistrationCode` the documents are sent without
   VAT, whatever the classification below says: that is the seller's registration deciding, not a setting.

4. **Have finance state the classification and VAT** for each line kind (`PLAN`, `MODULE`, `OVERAGE`,
   `EARLY_EXIT`, `DEPOSIT`): `GET /api/v1/control-plane/einvoicing/line-classifications` shows them
   `provisional`. For each, finance supplies the ИКПУ code and name, the unit's code and name, and the VAT
   rate in basis points, and whether the statement amounts are net or gross of VAT:

   ```
   PUT /api/v1/platform-admin/commercial/einvoicing/line-classifications/PLAN
   If-Match: "<the version of that row>"
   {"itemLabel": "...", "catalogCode": "...", "catalogName": "...", "packageCode": "...",
    "packageName": "...", "vatRateBp": 1200, "confirmed": true, "reason": "stated by finance"}
   ```

   This step does not block activation, and a document can be sent with provisional rows, but it must be done
   before the first document that is not deleted again: a wrong code is an invoice the buyer refuses.

5. **Activate.** One account is active per operator.

   ```
   POST /api/v1/platform-admin/commercial/einvoicing/installations/018f9c10-5000-7000-8000-0000000000d1/activation
   If-Match: "<the version now>"
   {"reason": "operator account approved"}
   ```

   Check: `status: ACTIVE`, `connected: true`. A 422 `OPERATOR_NOT_CONNECTED` names what is missing.

6. **Walk one statement through.** With a test tenant whose legal entity has a taxpayer number the operator
   knows, issue a statement and send it:

   ```
   POST /api/v1/platform-admin/commercial/tenants/{tenantId}/statements/{statementId}/einvoices
   {"provider": "DIDOX", "legalEntityId": null, "reason": "first walk"}
   ```

   Check, in this order: the attempt is `SUBMITTED` with the operator's document id; the operator's own product
   shows one **draft** invoice numbered with the statement number and carrying the seller, buyer, lines and
   amounts you expect; "Refresh state" reads `DRAFT` (Didox) or `DRAFT`/`SENT` (Faktura.uz). Compare the
   amounts line by line with the statement: both operators take money as a decimal, and a factor of a hundred
   is the mistake to look for. Delete the draft in the operator's product, refresh again, and release the
   statement (*A draft that went out wrong*). Only when this passes, tell finance the account is real.

7. **To stop sending**, suspend the account (Control plane > E-invoicing > Suspend, or `…/suspension`).

Steps 3, 5 and 7 are also on the control plane's E-invoicing screen, which sends the `version` it read and
never shows the secret reference, only whether one is bound.

## What the first walk settles

Nothing below is known today; each is a fact the walk answers and each is recorded in
`external-connections/w12-money.md`.

- **Didox:** whether the document id is read from the answer (the SDK types it `any`); whether the number
  lookup (`GET /v2/documents?name=`) honours its filter; whether the `Summa` and `CatalogName` mappings
  produce an invoice Didox accepts; what each numeric status means beyond the five the adapter reads.
- **Faktura.uz:** the address parts the model wants against the one line HorecaOS holds; whether ISO dates are
  accepted; the status text for a refusal and a cancellation, from which the adapter infers the state.
- **Both:** whether a draft's number may be reused after the draft is deleted; and the VAT treatment, which is
  finance's, not the platform's.

## A draft that went out wrong

Every classification is provisional until finance confirms it, so a first draft may carry wrong codes or
VAT. Delete it in the operator's own product; the next refresh reads `UNKNOWN` with status
`NOT_FOUND_AT_OPERATOR`; "Release statement" (a reason is required) records the attempt as `CANCELLED` and
frees the statement to be sent again or voided. A signed, refused or cancelled document is final and cannot
be released. The route descriptor `docs/routes/einvoicing-operator-api.md` has the rest of the runbook:
`UNCERTAIN` attempts, `OPERATOR_LOGIN_REFUSED`, an open circuit, and a statement that cannot be voided.

## What the merchant reads

A tenant's finance user sees the state of their own invoice in the operations console, under Finance >
Subscription: the operator, the document number, the state in words, the operator's own status, and when the
state was last asked (`GET /api/v1/tenants/{tenantId}/commercial/einvoices`, `COMMERCIAL_USAGE_READ`). The page shows
nothing when no document has been sent, and it never shows an account, a secret or a failure text from the
operator. HorecaOS staff see the same documents with their attempts on the control plane.
