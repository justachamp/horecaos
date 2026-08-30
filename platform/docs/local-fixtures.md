# Local API fixtures

`make run` activates the `local` Spring profile. That profile alone adds
`classpath:db/local-fixtures` to Flyway, where the repeatable fixture migration
creates an additive demo tenant. Test and production profiles discover only
`classpath:db/migration`; production also explicitly activates the `production`
profile in its Compose definition.

The fixture never deletes or updates data. To return a local stack to its first
start state, remove its disposable Docker volume and start it again:

```bash
docker compose down -v
docker compose up -d
make run
```

Do not use that command against any environment containing data you need.

## Demo hierarchy

| Resource | Value |
| --- | --- |
| Tenant | `10000000-0000-0000-0000-000000000001` |
| Brand | `10000000-0000-0000-0000-000000000002` |
| Location | `10000000-0000-0000-0000-000000000003` |
| Channel | `STOREFRONT` |
| Currency | `UZS` |

The menu has available osh and somsa, plus a deliberately unavailable shashlik
so clients can render an unavailable item. The location is open every day, has
a 20-minute preparation band, and delivers within 5 km of its local fixture
coordinate in Tashkent.

## Public requests to try

These endpoints need no token and work as soon as the API reports ready:

```bash
curl 'http://localhost:8080/api/v1/storefront/pickup-locations?lat=41.311341&lon=69.282722'

curl 'http://localhost:8080/api/v1/storefront/tenants/10000000-0000-0000-0000-000000000001/brands/10000000-0000-0000-0000-000000000002/locations/10000000-0000-0000-0000-000000000003/menu?locale=uz'

curl 'http://localhost:8080/api/v1/storefront/tenants/10000000-0000-0000-0000-000000000001/brands/10000000-0000-0000-0000-000000000002/locations/10000000-0000-0000-0000-000000000003/serviceability?channel=STOREFRONT&mode=PICKUP'

curl 'http://localhost:8080/api/v1/storefront/tenants/10000000-0000-0000-0000-000000000001/brands/10000000-0000-0000-0000-000000000002/locations/10000000-0000-0000-0000-000000000003/delivery-fee?lat=41.3120&lon=69.2410&currency=UZS&subtotalMinor=100000'
```

## Signing in as a customer

`make run` also configures the one phone number that signs in with a fixed code
and sends no SMS. It exists so the customer journey is exercisable on a laptop,
where no gateway is bound and no message can leave.

| Setting | Value |
| --- | --- |
| Number | `+998000000000` |
| Code | `000000` |

`+998 00 …` is allocated to no Uzbek operator, so it addresses nobody. Both
values live in `src/main/resources/application-local.yml`, which the `local`
profile activates — the same binding `db/local-fixtures` has. Override them with
`HORECAOS_VERIFICATION_PRESET_PHONE` and `HORECAOS_VERIFICATION_PRESET_CODE` if you
want different ones.

Three requests, and the third returns the bearer everything else needs:

```bash
TENANT=10000000-0000-0000-0000-000000000001
BRAND=10000000-0000-0000-0000-000000000002
IDENTITY="http://localhost:8080/api/v1/storefront/tenants/$TENANT/brands/$BRAND/identity"

CHALLENGE=$(curl -sX POST "$IDENTITY/verification-challenges" \
  -H 'Content-Type: application/json' \
  -d '{"phone":"+998000000000"}' | jq -r .challengeId)

GRANT=$(curl -sX POST "$IDENTITY/verification-challenges/$CHALLENGE/attempts" \
  -H 'Content-Type: application/json' \
  -d '{"code":"000000"}' | jq -r .grant)

TOKEN=$(curl -sX POST "$IDENTITY/sessions" \
  -H 'Content-Type: application/json' \
  -d "{\"grant\":\"$GRANT\"}" | jq -r .token)

curl -s "http://localhost:8080/api/v1/storefront/tenants/$TENANT/brands/$BRAND/me" \
  -H "Authorization: Bearer $TOKEN"
```

The token is opaque, starts with `qcs1.`, and lasts 30 days
(`horecaos.customers.session.ttl`). Sign out with
`DELETE $IDENTITY/sessions/current`, which needs the same bearer and an
`Idempotency-Key` header.

**Any other number still needs a real SMS gateway.** The preset matches exactly
one destination; every other number goes down the real route to the real adapter
and comes back `NO_PROVIDER_BINDING` until an operator has configured one. See
[what a tenant must supply](providers/sms-gateway-vas.md#what-a-tenant-must-supply)
and [ADR 0051](adr/built/0051-customer-session-authentication.md).

**This cannot reach a deployment.** The preset bean is not created outside a
local profile, and `PresetVerificationCodeGuard` refuses to *start* a non-local
profile that has either property set. A fixed one-time code in production would
be a complete authentication bypass, so the failure mode is a container that will
not come up.

## Staff operations

Protected operator endpoints still need a local Keycloak access token; fixture
data does not weaken the platform's authentication or capability checks. A
customer session is not one of those tokens and confers no staff authority — it
authorises the caller's own rows and nothing else (ADR 0049).
