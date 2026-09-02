# Connecting a Click or Payme sandbox through the Integrations screens

**Last executed:** never. This runbook walks the screens ADR 0065 built end to
end and every step short of the last one has been driven against a real local
stack while writing it — the one step that has not is the actual sandbox
round trip against Click's or Payme's real servers, because that needs a real
sandbox merchant account and a real credential only the platform owner holds.
Run it there first, watch a real payment settle, and update this line with
the date and which provider — per ADR 0023, a runbook that has never been
executed is a draft.

**How to get back.** Every step below is reversible from the same screen:
archive a merchant binding that turns out wrong (a `SUSPENDED`/`DRAFT` row —
see "Rolling back" at the end), or connect the provider again with a
corrected value — the write-only door never blocks a retry, and a rejected
value is never written to the row it would have replaced (see "What
'write-only' actually guarantees").

## Before you start

- A signed-in control-plane session for the tenant, held by someone with
  `INTEGRATION_INSTALLATION_MANAGE` (installing a provider) and
  `PAYMENT_MERCHANT_BINDING_MANAGE` (registering the merchant binding) —
  `tenant-owner` holds both; `tenant-admin` holds only the first, see
  `Capability.PAYMENT_MERCHANT_BINDING_MANAGE`'s own doc comment for why.
- The platform API reachable at the URL the control-plane app is built
  against (`make run`, or the real deployment).
- **The sandbox `provider_environments` row already seeded.** This is
  platform-owned reference data (ADR 0026: "Tenants choose an environment;
  they never supply a URL") — there is deliberately no tenant-facing endpoint
  that writes it, so a platform operator seeds it once, the same way
  `tools/seed-payments` seeds the local fake's row. If
  `GET /api/v1/control-plane/tenants/<tenantId>/integrations/connect-fields`
  is reachable but connecting later fails with `INVALID_REQUEST: Unknown
  provider environment`, this step was skipped. Seed it (adjust the sandbox
  base URL to whatever Click's or Payme's own onboarding actually issued —
  never guess one):

  ```bash
  docker compose exec -T -e PGPASSWORD=horecaos_migrator platform-db \
    psql -v ON_ERROR_STOP=1 -U horecaos_migrator -d horecaos <<'SQL'
  INSERT INTO integration.provider_environments
      (code, provider_category, provider_type, base_url, is_production, egress_allowlist, notes)
  VALUES
      ('click-sandbox', 'PAYMENT', 'CLICK', 'https://api.click.uz/v2/merchant', false, 'api.click.uz',
       'Click sandbox -- test service/merchant ids from Click onboarding, not a separate host'),
      ('payme-sandbox', 'PAYMENT', 'PAYME', 'https://checkout.test.paycom.uz', false, 'checkout.test.paycom.uz',
       'Payme test cashbox -- confirm the exact host in your Payme onboarding email before trusting this row')
  ON CONFLICT (code) DO NOTHING;
  SQL
  ```

  A real production deployment does this as a migration instead (matching
  `V0036`'s and `V0061`'s own precedent of a migration-seeded
  `provider_environments` row), reviewed the same as any schema change.

## What "write-only" actually guarantees, before you type a real credential in

Every field below marked **secret** goes through
`POST /api/v1/control-plane/tenants/<tenantId>/integrations/secrets`
(`SecretIngressController`) the moment you submit the form it is on. From
that instant:

- The value is written into the ADR 0028 secrets manager under a reference
  the platform minted, never one you chose.
- **There is no endpoint, anywhere in this API, that reads it back.** The
  screen only ever shows "Configured •••••" and a rotation date afterwards.
  If you mistype a credential, the fix is to rotate it again — there is no
  "view" to check what was typed.
- The value never appears in a server log, a trace, an audit row, or an error
  response — `SecretIngressControllerEndpointTests` scans captured logs for
  exactly this on every build.
- Telegram's credential is verified live (a real `getMe` call) before it is
  ever written. Click's and Payme's are not — neither offers HorecaOS a
  harmless outbound call to check one against, so a typo is caught only when
  a real payment fails, not at connect time. Type carefully; there is nothing
  else standing between a typo and a live checkout failing that same hour.

## Connecting a Telegram bot (the fully-verified case, do this one first)

The simplest complete round trip, worth doing once to see the screen work
before touching Click or Payme:

1. **Integrations → Connect a provider.**
2. Provider: `TELEGRAM_BOT_API`. Display name: anything recognisable
   ("Ops bot"). Environment: the code seeded for it (`telegram-prod` in a
   real deployment, or whatever `docs/local-fixtures.md`/your own seeding
   used locally).
3. Bot token (**secret**): the BotFather token.
4. **Connect.** The screen writes the token through the door, then calls
   `POST .../integrations` with the reference the door returned. A new row
   appears in "Provider installations", status `DRAFT`.
5. This is as far as the screen takes an installation on its own — binding it
   to a brand/location and running the capability-reconciliation preflight
   still need the underlying API directly (see "What this iteration does not
   do" below). For a smoke test that the credential itself is good, use the
   **Rotate credential** button on the new row with the same token again:
   a `200` with a `botUsername` in the response is Telegram confirming the
   token live, right there in the UI.

## Connecting a Click sandbox merchant account

Click's shape needs two things the screen keeps separate on purpose: the
**installation** (the Click account itself, ADR 0026) and the **merchant
binding** (which legal entity settles under it, ADR 0013) — registering a
merchant binding needs a legal entity, and an installation is what it binds
to.

1. **Integrations → Connect a provider.** Provider `CLICK`. Display name
   ("Click — sandbox"). Environment: `click-sandbox` (the row seeded above).
   Merchant id / Service id: Click's own sandbox identifiers, exactly as
   Click's onboarding gave them to you — non-secret, they identify the
   account rather than authenticate it. Secret key (**secret**): Click's
   sandbox secret key.
2. **Connect.** A new row appears in "Provider installations", category
   `PAYMENT`, status `DRAFT`. **Copy its id from the network response or
   Swagger UI's `GET .../integrations` list** — the table itself does not
   show the raw id, and the next screen needs it (see the known limitation
   below).
3. This installation still needs an ADR 0026 *binding* to a brand before a
   merchant binding can reference it — the screen does not build one yet
   (again, "What this iteration does not do"). Create it directly:

   ```bash
   curl -sX POST "$API/api/v1/control-plane/tenants/$TENANT/integrations/$INSTALLATION_ID/bindings" \
     -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
     -H "Idempotency-Key: $(uuidgen)" \
     -d '{"brandId":"'"$BRAND"'","priority":100,"capabilities":["PAYMENT_INITIATE"],"primaryCapabilities":["PAYMENT_INITIATE"]}'
   # -> {"bindingId": "<id>", "status": "SUSPENDED"}
   ```

4. **Integrations → Register a merchant binding.** Provider `CLICK`. Legal
   entity id: the seller this account settles for (from Tenants → Legal
   entities once that screen exists, or `GET .../legal-entities` today).
   Installation id: from step 2. Integration binding id: from step 3.
   Merchant account reference: Click's service id again, as the account's
   human-readable name on this platform. Callback path segment: a fresh
   8–64 character lowercase/digit/hyphen string nobody else has used yet (the
   database rejects a collision loudly rather than silently sharing one).
   Merchant secret key (**secret**): the same sandbox key as step 1 — Click
   only issues one, and the merchant binding needs its own reference to it
   because the two rows resolve it independently (ADR 0026 installation
   credential vs. ADR 0013 binding credential; today they are usually the
   same value written under two different references, which is expected).
5. **Register.** A new row appears in "Merchant bindings", status `DRAFT`.
   Activate it (needs `expectedVersion=1`, the version the register response
   returned):

   ```bash
   curl -sX POST "$API/api/v1/operations/tenants/$TENANT/merchant-bindings/$BINDING_ID/activate?expectedVersion=1" \
     -H "Authorization: Bearer $TOKEN"
   ```

6. Checkout an order with Click and confirm it settles. **This is the step
   that needs the owner's real sandbox account and has not been run yet** —
   see this file's own header.

## Connecting a Payme sandbox cashbox

The same shape as Click, one field narrower — Payme has a cashbox id and a
key, not a merchant/service id pair:

1. **Integrations → Connect a provider.** Provider `PAYME`. Environment:
   `payme-sandbox`. Cashbox id: non-secret. Key (**secret**): Payme's test
   cashbox key.
2. Create the ADR 0026 binding (same `curl` shape as Click step 3, this
   installation's id).
3. **Integrations → Register a merchant binding.** Provider `PAYME`, same
   fields as Click's step 4, with the cashbox id as the merchant account
   reference.
4. **Register**, then activate (same as Click step 5).
5. Checkout an order with Payme and confirm it settles — **not yet run
   against a real Payme sandbox**, same caveat as Click.

## Rotating a credential from the screen

Click gave you a new key, or a Telegram token was reissued:

- **Provider installations** (Telegram, or an installation-level rotation for
  any provider): row → **Rotate credential** → new value + a reason → the
  screen writes the value through the door, mints a fresh reference, and
  swaps the row onto it. Telegram is verified live before the swap; every
  other provider type is written and swapped with the row left
  `last_connection_status = UNVERIFIED` — an honest "connected, not
  independently confirmed", never a manufactured "verified".
- **Merchant bindings** (a Click/Payme merchant account's own credential):
  row → **Rotate credential**, same shape. Neither provider offers a
  harmless call to verify against here either, so this is always written
  unverified in the same sense.
- Either way the old reference is abandoned in the secrets manager (nothing
  deletes it — ADR 0028's rollback posture is "never returns secret values to
  the database", not "clean up the old one"), and the row's "last rotated"
  column updates immediately.

## Rolling back / disconnecting

- **A merchant binding**: suspend it first if it is `ACTIVE`
  (`POST .../merchant-bindings/<id>/suspend?expectedVersion=<n>` — no screen
  button for this yet, see below), then **Archive** from the row. The row
  survives archived, per `MerchantBindingController`'s own doc comment: every
  payment it ever settled still resolves an account reference.
- **An installation**: there is no archive/retire action anywhere yet, screen
  or API (`status` supports `RETIRED` in the database's own check constraint,
  but nothing transitions a row to it). Suspend every binding under it
  instead — a suspended binding never resolves for a new payment, which is
  the practical disconnect.

## What this iteration does not do (deliberate, not hidden)

- **No brand/location picker anywhere in the connect flow.** Binding an
  installation to a brand (step 3 above) is a raw `curl`/Swagger call. A
  future iteration adds this to the drawer once the brand list has a real
  screen to read from.
- **No legal-entity, installation, or integration-binding picker in "Register
  a merchant binding".** Three plain id fields, copy-pasted from elsewhere in
  the platform. Building searchable pickers needs list endpoints this screen
  does not fetch yet — flagged in `register-merchant-binding-panel.ts`'s own
  doc comment, not silently shipped as if it were a finished wizard.
- **No activation button on either table.** Activating an installation's
  binding needs a successful capability-reconciliation preflight first (ADR
  0026); activating a merchant binding just needs its version. Both are one
  `curl` call, shown above, and neither handles a secret value, so neither
  needed the door.
- **The merchant-bindings API lives on the `operations` OpenAPI surface
  group while this screen lives in `control-plane`** (ADR 0057 vs. ADR
  0065) — `IntegrationsApi`'s own doc comment names this rather than hiding
  it behind an unqualified path.

## Appendix: the OpenBao/manual path, for an emergency only

Use this only when the door endpoint itself is down, or you are debugging the
door and need to compare against the mechanism it replaced. It bypasses ADR
0065 entirely — a human with infrastructure access writes the value straight
into OpenBao, the same two-actor process ADR 0065's own Context section
describes as "right for the founding team operating its own pilot" and wrong
as the product. Anyone using this path for routine tenant onboarding after
this runbook exists is doing it the old way for no reason.

```bash
# 1. Write the value directly (needs an OpenBao token with write access to
#    horecaos/<environment>/provider_payment/**, per infra/openbao/policies).
curl -sf -X POST \
  -H "X-Vault-Token: $BAO_TOKEN" -H "Content-Type: application/json" \
  -d '{"data":{"value":"the-real-sandbox-secret-key"}}' \
  "$BAO_URL/v1/horecaos/data/<environment>/provider_payment/tenant-<tenantId>/<any-unique-id>"

# 2. Compose the reference by hand, in the exact format SecretReference.parse
#    enforces, and use it wherever the screens above ask for a value:
#      horecaos:<environment>:provider_payment:tenant-<tenantId>:<any-unique-id>

# 3. Install/bind/register exactly as the sections above describe, but with
#    "secretReference": "<the reference from step 2>" in the raw request body
#    instead of a value -- ProviderInstallationController.InstallRequest and
#    MerchantBindingController's own RegisterMerchantBindingRequest both still
#    accept a caller-supplied reference; the door adds a way to mint one from
#    a value, it does not remove the older path.
```

This path never appears in the control-plane UI, deliberately: it needs
infrastructure access no tenant has, which is exactly the two-actor problem
ADR 0065 exists to remove for everyone who does not also need this appendix.
