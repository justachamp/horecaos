# Customers cannot sign in — no SMS is arriving

**Trading-hours alert.** **Last executed:** never — this is a draft.

**Nothing here needs a code change.** Every part of the sign-in path ships; what
a deployment supplies is an SMS gateway account. Until it has one, a customer
asking for a code gets a 500 whose `reason` names what is missing, and no message
is sent — the platform does not swallow it (ADR 0051, ADR 0026).

**Way back:** every step below is additive. Nothing here deletes a row or
invalidates a session, so there is nothing to undo.

## 1. Ask what the reason is

```bash
curl -sX POST \
  "https://$HORECAOS_HOST/api/v1/storefront/tenants/$TENANT/brands/$BRAND/identity/verification-challenges" \
  -H 'Content-Type: application/json' \
  -d '{"phone":"+998901112233"}' | jq '{status: .status, code: .code, reason: .reason}'
```

Use a number you control. It will be sent a real message if this works.

**Check:** a `202` means the gateway took it and this is not your problem — go to
step 5. Otherwise the `reason` decides where you go:

| `reason` | What it means | Go to |
|---|---|---|
| `NO_PROVIDER_BINDING` | No ADR 0026 installation or binding for this tenant | step 2 |
| `SMS_ROUTE_UNAVAILABLE` | The Camel route did not start | step 4 |
| `NO_TRANSPORT` | No adapter bean at all — a non-local profile should not have started | step 4 |
| `13` | The gateway says the key is wrong | step 3 |
| `16` | The gateway says the sender is not registered | step 2 |

## 2. Check what the tenant has

```bash
qc exec -T postgres psql -U horecaos -d horecaos -c "
SELECT i.id, i.status, i.environment_code, i.provider_type,
       i.non_sensitive_config, i.secret_reference,
       b.id AS binding, b.brand_id, b.status AS binding_status
FROM integration.installations i
LEFT JOIN integration.bindings b ON b.installation_id = i.id
WHERE i.tenant_id = '$TENANT' AND i.provider_type = 'SMSGW_VAS';"
```

**Check:** you need one `ACTIVE` installation whose `environment_code` is
`smsgw_vas_production` and whose `non_sensitive_config` holds both `login` and
`sender`, plus one `ACTIVE` binding covering the brand. Anything `DRAFT`, any
missing `sender`, or no binding row at all is the fault.

There is no API that writes these — the installation endpoint has no field for
`non_sensitive_config` and no endpoint activates a binding. Provisioning is an
operator database change today; the three inputs and their exact shapes are in
[what a tenant must supply](../providers/sms-gateway-vas.md#what-a-tenant-must-supply).

## 3. Check the credential is in OpenBao under the reference the row names

```bash
qc exec -T openbao bao kv get -field=value \
  "$HORECAOS_OPENBAO_MOUNT/$(echo "$SECRET_REFERENCE" | cut -d: -f2)/provider_notification/$(echo "$SECRET_REFERENCE" | cut -d: -f4)/$(echo "$SECRET_REFERENCE" | cut -d: -f5)" \
  > /dev/null && echo present
```

**Check:** `present`. A missing path or a missing `value` field is the fault —
the resolver reads that field name and no other.

**The key is yours to place, and only yours.** It is rotated in the provider's
web console and written back under the *same* reference; the reference never
changes. It is never a column, never configuration, and never in a commit
(ADR 0028). If it is not in OpenBao, this runbook cannot get it for you.

## 4. Check the route is running

```bash
curl -s "https://$HORECAOS_HOST/actuator/health" | jq '.components.camelRoutes'
```

**Check:** `sms.verification.send.v1` is `Started`. If it is not, the adapter
failed to build at boot and the application log's startup section says why. A
non-local profile with no adapter bean at all should not have started — see
`VerificationTransportGuard`.

## 5. Confirm a real number all the way through

```bash
IDENTITY="https://$HORECAOS_HOST/api/v1/storefront/tenants/$TENANT/brands/$BRAND/identity"

CHALLENGE=$(curl -sX POST "$IDENTITY/verification-challenges" \
  -H 'Content-Type: application/json' -d '{"phone":"+998901112233"}' | jq -r .challengeId)

# read the code off the handset, then:
GRANT=$(curl -sX POST "$IDENTITY/verification-challenges/$CHALLENGE/attempts" \
  -H 'Content-Type: application/json' -d '{"code":"123456"}' | jq -r .grant)

curl -sX POST "$IDENTITY/sessions" \
  -H 'Content-Type: application/json' -d "{\"grant\":\"$GRANT\"}" | jq '{expiresAt, created}'
```

**Check:** an `expiresAt` about thirty days out. The `token` is deliberately not
printed above — it is a live credential for that account.

## If a customer says they were signed out

That is not this runbook. A session lasts thirty days and then ends, and an ended
one answers `401` with `code: SESSION_EXPIRED` — distinct from `UNAUTHENTICATED`,
which is what an invented token gets. The storefront should show "sign in again"
and keep the basket. If it shows the front door instead, the client is branching
on the status and not on the code.

To end every session an account holds — a lost handset, a compromised number:

```sql
UPDATE customer.customer_sessions SET revoked_at = now()
WHERE tenant_id = :tenant AND customer_account_id = :account AND revoked_at IS NULL;
```

## Why it is built this way

Sign-in does not go through Keycloak. A customer proves a phone number with a
one-time code and receives a platform-issued opaque session; the realm is for
staff. That is [ADR 0051](../adr/built/0051-customer-session-authentication.md),
and its consequence for you at 3am is the useful one: an identity-provider
outage stops staff working and does not stop customers ordering.

The one number that signs in with a fixed code and no SMS exists only on a local
profile, and a non-local profile refuses to start if it is configured. If this
host ever fails to boot with a message about
`horecaos.customers.verification.preset.phone`, that is the guard doing its job —
unset it, do not work around it.
