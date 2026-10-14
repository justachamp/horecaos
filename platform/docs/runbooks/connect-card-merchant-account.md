# Connecting HorecaOS's own card merchant account

**Last executed:** never, and it cannot be yet: HorecaOS has no Click or Payme merchant
account of its own (ADR 0095's open input, owned by finance and operations). What exists
is everything this runbook ends in — the installation to fill, the rules it is held to,
the real Click adapter (`ClickCardProviderAdapter`, provider type `CLICK_CARD`) and a fake
of Click's HTTP boundary behind it that proves the flow. Per ADR 0023 a runbook that has
never been executed is a draft; run it once against the provider's sandbox and put the date
and the provider here. The facts that wait on the account, one section each, are in
[`external-connections/w12-money.md`](external-connections/w12-money.md).

**How to get back.** Suspending the installation (step 6) puts every card tenant back to
being collected like an invoice tenant, at once and without losing a card or a ledger entry.
Nothing here deletes anything.

## What the owner provides, and what only a developer can

| Needs | Who |
|---|---|
| The merchant account, with card-token (recurring) access, and its credential | Finance and operations, with the provider |
| An adapter that speaks that provider's API: a class implementing `CardProviderAdapter` (its `providerType()` is the value the installation names) | A developer. **Done for Click:** `ClickCardProviderAdapter`, type `CLICK_CARD`, over `docs/routes/card-billing-provider-api.md`. A second provider is a second class |
| The provider's sandbox and production endpoints as `integration.provider_environments` rows, category `PAYMENT` | A developer, as a migration — tenants and staff never type a URL (ADR 0026). **Done for Click:** `click_card_production` and `click_card_sandbox` (V0689), both `https://api.click.uz/v2/merchant` because Click publishes no separate sandbox host |

The adapter must honour the idempotency key on `charge` — a repeated key is the same
attempt and never a new charge — and must answer `status(key)`. The contract is written on
`CardCharger`, and `CardProviderContract` is the executable form of it. It is run against the
fake double (`FakeCardProviderTests`) and against the real Click adapter talking over a socket
to a fake of Click's HTTP (`ClickCardProviderAdapterTests`). Click has no idempotency key on a
charge, so the adapter keeps the contract itself: it asks `status_by_mti` for the key before it
charges, and an answer it cannot read leaves the attempt `PENDING` rather than charging on a
guess. Run the same assertions by hand against the sandbox before activating it (step 5).

## Steps

1. **Put the credential in OpenBao**, category `provider_payment`, owner scope `platform`,
   for example `horecaos:production:provider_payment:platform:click-card`. The value goes
   in through the secrets manager and never through this API (ADR 0028). For Click it is one
   JSON object of the two private values Click issues, and `service_id` (public) is not in it:

   ```sh
   bao kv put horecaos/production/provider_payment/platform/click-card \
     value='{"merchantUserId": "<merchant_user_id>", "secretKey": "<secret_key>"}'
   ```
2. **Declare the account, in DRAFT** (`INTEGRATION_INSTALLATION_MANAGE` at platform scope):

   ```
   POST /api/v1/platform-admin/commercial/billing/card-installations
   {"providerType": "CLICK_CARD", "environmentCode": "click_card_sandbox",
    "displayName": "HorecaOS card account", "secretReference": "horecaos:production:provider_payment:platform:click-card",
    "externalAccountReference": "<Click's service_id>",
    "configuration": {"lookbackDays": 2, "notFoundErrorCodes": []},
    "reason": "connecting the merchant account"}
   ```

   `externalAccountReference` is Click's `service_id`, a public identifier the adapter sends and
   the browser sees. `configuration` is optional: `lookbackDays` (0 to 31, default 2) is how many
   days before today the adapter looks for an earlier charge under the same key, and
   `notFoundErrorCodes` lists the `error_code` values the sandbox walk showed Click answers for
   "no such payment" (empty until then, and the adapter then charges nothing it cannot rule out).
   Use `click_card_production` only for the live account.

   Check: `GET /api/v1/control-plane/billing/card-installations` lists it as `DRAFT` with
   `secretConfigured: true` (the reference itself is never returned).
3. **Check no other account is active.** At most one installation is `ACTIVE`; activating a
   second is refused. If a test double is active (it cannot be outside a local or test run),
   suspend it.
4. **Activate it**, quoting the `version` the read returned:

   ```
   POST /api/v1/platform-admin/commercial/billing/card-installations/{id}/activation
   {"expectedVersion": 0, "reason": "merchant account approved"}
   ```

   Check: the response says `ACTIVE`, and a tenant that opens Finance → Wallet now sees card
   payments as available (`cardPaymentsAvailable: true`): "Add a card" and "Top up by card" stop
   saying the account is not connected.
5. **Walk one real card through.** With a test tenant: add a card, top up a small amount,
   check the ledger holds one `TOP_UP` whose reference is the provider's own, and that the
   provider's dashboard shows exactly one charge. Then choose CARD and issue its statement.
   The sandbox walk's checklist, and what each answer sets, is section 5 of
   `external-connections/w12-money.md`.
6. **To stop charging**, suspend the installation (`.../{id}/suspension`). Cards stay on file. It is
   refused while a top-up or statement charge asked through it is still `PENDING`; see *Replacing an account*.

Steps 2, 4 and 6 are also on the control plane's Billing setup screen (Commerce → Billing
setup): "Declare an account", then Activate or Suspend with a reason. The screen sends the
`version` it read, never shows the secret reference (only whether one is named), and says in
words that with no account active every card tenant is collected like an invoice tenant.

## Walking the flow on a laptop, with no Click account

`make seed-card-provider` (`tools/seed-card-provider`) points a **local** database at the fake of Click's card API
that `make run` starts under the `local` profile on port 18090: it writes the fake's credential to the local OpenBao,
moves the approved `click_card_sandbox` environment onto the fake, and declares and activates the account. Then
Finance > Wallet > Add a card, with card `8600 1234 1234 4242` and the SMS code `246810`, walks the real adapter, route and
egress check against the fake. Card `…0002` declines and card `…0003` takes the first charge and loses the answer, which the
settlement sweep then resolves without charging again. The script has been syntax-checked and **not yet run**: nobody has had a
local stack of their own to run it against. It never touches a deployment, and `docker compose down -v` throws its work away.

## Replacing an account

A card token is only meaningful to the merchant account that minted it, so every stored card
is bound to the installation it was added under. Suspend the old account, activate the new
one, and every tenant's card is refused by name (`CARD_BOUND_UNDER_ANOTHER_MERCHANT_ACCOUNT`,
visible as the decline reason) until the tenant adds it again. That is deliberate: sending a
token to an account that never minted it would read as the cardholder's bank declining.

An idempotency key is just as local to the account it was sent to, and that is the part to
check **before** suspending. A top-up or a statement charge that was asked and never
answered (`PENDING`) may have moved the money, and the account that replaces this one has
never seen its key, so it cannot say. Suspending is therefore refused with
`UNRESOLVED_CARD_CHARGES` (the count of top-ups and of statement charges is in the answer)
while any charge asked through the account is still `PENDING`. In the normal case there is
nothing to do: the settlement sweep resolves them within minutes, and the suspension then
goes through.

If the account cannot answer (credentials revoked, provider down for a day) suspend with
`"acknowledgeUnresolvedCharges": true`. Nothing is lost by it: the charges stay `PENDING` —
never `FAILED`, never `NOT_CONFIGURED` — the audit fact for the suspension carries the counts,
and `commercial.wallet.card_top_up{outcome="stranded"}` and
`commercial.wallet.card_charge{outcome="stranded"}` count every sweep that found one. A
stranded top-up blocks that tenant's next top-up (`TOP_UP_IN_FLIGHT`) until it is resolved.
To resolve one, make the old account active again (suspend the new one, activate the old):
the next sweep asks the account that holds the key and records the money once if it was
taken. Finance can also compare the old account's dashboard with `commercial.card_top_ups` /
`commercial.card_charge_attempts` by row id, which is the key.

## When it goes wrong

- **A top-up is `PENDING` for more than a few minutes.** The provider has not answered. The
  settlement sweeper (`WalletCardSettlementSweeper`, every five minutes) asks the provider
  what it believes under the same key and records the money if it was taken; it never charges
  a second time. Nothing for a person to do unless it stays `PENDING` for a day — then compare
  the provider's dashboard with `commercial.card_top_ups` by the row id, which is the key.
- **`commercial.wallet.card_charge_declined` for every tenant at once.** That is the account
  or the adapter, not the cardholders: suspend the installation and read the provider's
  status page. The counter `commercial.wallet.card_charge{outcome}` is the rate to alert on.
- **`commercial.wallet.card_charge_surplus_refused` or `…_after_supersede`.** Money the
  provider took that the ledger refused to credit twice; finance reconciles it by hand against
  the provider, then refunds it there.
