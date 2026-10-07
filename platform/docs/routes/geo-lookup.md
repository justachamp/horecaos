# Route descriptor: `geo.lookup.v1`

Required by ADR 0007. A production route may not ship without one of these; see
`docs/routes/README.md` for the format. The provider is transcribed in
`docs/providers/yandex-maps.md`, and where this file and that one disagree, that one wins.

| Field | Value |
|---|---|
| Route IDs | `geo.lookup.v1`, `geo.lookup.dead-letter` |
| Version | 1 |
| Owning module | `integration` (route, gateway and adapters), commanded by `fulfillment` (`GeocodingService`) through `tenancy.api.geo.GeocodePort` (ADR 0145) |
| Owner | Ayubkhon Abbosov (platform architecture) |
| Input contract | `GeoOperation` v1 (in-process command record: kind `SUGGEST` / `GEOCODE` / `REVERSE`, text or point, the region, the locale), built from the `GeocodePort` arguments |
| Output contract | `ProviderOutcome` v1 carrying a `List<GeoSuggestion>` or `List<GeocodeResult>` under `payload`, narrowed to `GeocodeOutcome` v1 (`Answered` / `Unavailable(reason)`) at the port |
| Source | `direct:geo.lookup` (`block=false`) |
| Destination | The adapter named by `horecaos.geo.provider`. Yandex: `geocode-maps.yandex.ru/1.x` (`GET /`) for geocode and reverse, `suggest-maps.yandex.ru/v1` (`GET /suggest`) for suggest, both selected by the ADR 0026 `integration.provider_environments` rows `yandex_geocoder_production` and `yandex_suggest_production` (V0490). `FAKE` (local profile only) answers from fixtures with no network |
| Service identity | The platform's own account on the provider (ADR 0145 decision 4: one licence, not one per tenant). The key is an ADR 0028 reference named by `horecaos.geo.yandex.secret-reference`; there is no tenant installation and no tenant-supplied key. The provider takes the key as a query parameter |
| Secret reference type | `horecaos:{env}:provider_geocoding:platform:{id}` (ADR 0028; the platform-owned `PROVIDER_GEOCODING` category, which no tenant can write), resolved at call time, refreshed once past the cache on a rejected key |
| Connect timeout | 5s (`ProviderHttpClient`). A connect-phase failure is the only one that proves nothing left this process |
| Total timeout | 3s for `SUGGEST`, 5s for `GEOCODE` and `REVERSE`, applied to the whole exchange including the body. A person is looking at a type-ahead list or a dropped pin; a slower answer is no answer, and the field degrades to text |
| Retry classification | **None, anywhere on this route.** Every operation is a read, so a repeat could never create a duplicate; it is still not retried, because a retry after several seconds of silence is useless to a person typing, and the circuit breaker is what stops a failing provider costing every keystroke a timeout. The single exception is a rejected key, retried exactly once after a fresh ADR 0028 read: the provider answered *instead of* serving, so it is safe, and it separates a stale cache from a key rotated in the provider's console |
| Idempotency key | **None, and none is needed.** The provider documents none for its read endpoints, and a read has no effect to deduplicate. No `Idempotency-Key` is sent: a header the provider ignores is read as a guarantee by the next person |
| Circuit breaker | One for the platform's one geocoder (`GeoCircuitBreaker`, resilience4j): count window 10, at least 5 calls, 50% failure rate opens it for 30s, 3 half-open probes. Only `RETRYABLE` and `UNCERTAIN` outcomes count; a refused key says nothing about the provider's health. While open the gateway answers `PROVIDER_UNAVAILABLE` without a network round trip, which is the point: ADR 0145 decision 6 makes an outage "a metric and an alert, not a checkout failure" |
| Dead-letter destination | `geo.lookup.dead-letter` -> a `RETRYABLE` `GEO_ROUTE_FAILURE` outcome, which the port reports as `Unavailable(PROVIDER_UNAVAILABLE)`. Nothing is queued: a lookup nobody is waiting for any more is not work to retry later |
| PII classification | The exchange carries a customer's typed address or a coordinate (ADR 0029). `GeoOperation`, `GeocodeResult`, `GeoSuggestion`, `AddressComponents` and `GeocodeOutcome.Answered` override `toString` to print none of it, because Camel writes exchange bodies into route logs and into the messages of the exceptions it wraps. **The provider call's query string is the provider's and is never logged**: `ProviderHttpClient.get(..., query, ...)` keeps it apart from the path it logs, percent-encodes it so a malformed one cannot become an exception message, and puts none of it on an outcome. A provider error body is reduced to an allowlist of scalar fields with long digit runs masked. Metric labels are provider, operation and outcome only. The key is never logged and `ProviderCall.toString` redacts it |
| Expected volume | Pilot: under 60 suggestions per operator per minute (the limiter's ceiling is 90), under 30 geocodes and 30 reverse lookups. The thirty-day response cache (ADR 0033, `geo.responses`) absorbs repeats. Metered as `geocode.requests` (ADR 0021): provider calls only, not cache hits |
| SLO | p95 under 1.5s for a suggest, under 3s for a geocode or reverse. The deadlines above are the ceiling, not the target |
| Runbook | `docs/routes/geo-lookup.md#runbook` |
| Dashboard | Metric `horecaos.geo.lookups`, tagged `provider`, `operation` (`suggest`, `geocode`, `reverse`) and `outcome` (`answered`, `not_configured`, `refused`, `unavailable`); and `horecaos.geo.requests` for the service-level view, tagged `provider`, `operation` and `outcome` (`answered`, `cache_hit`, `rate_limited`, `not_configured`, `provider_refused`, `provider_unavailable`); and the gauge `horecaos.geo.circuit.not_closed` |

## What the route decides, and what it deliberately does not

It decides *whether to call* and *how a failure is classified*; it never decides whether an
address is acceptable. A result is a **suggestion a person confirms on a map** (ADR 0145
decision 5): nothing the route returns is stored, and nothing in the platform can turn it into a
`GEOCODER` point.

The region's SW/NE box is sent to the provider as a **bias**, not a restriction, and is applied
again on the way back by `GeoGateway` for every adapter: a result outside the box is
`LOW_CONFIDENCE` whatever the provider claimed. A restriction would make a result in the wrong
city vanish; the contract is that it comes back flagged, so a person sees that it was elsewhere.

## Outcome policy

| Outcome | Reason code | What the port answers | Why |
|---|---|---|---|
| Provider answered | — | `Answered(list)`, possibly empty | An empty list is "nothing found", which is not a failure |
| No provider named, no adapter registered, or no key reference | `GEO_NOT_CONFIGURED` | `Unavailable(NOT_CONFIGURED)` | **A product fact, not an error.** Nothing works without the key, and the screens say so rather than showing an empty list. No network round trip, no log line |
| Key reference set, secret manager has no value | `GEO_CREDENTIAL_MISSING` | `Unavailable(NOT_CONFIGURED)` | The same fact from the other side: configured on paper, not in OpenBao |
| Secret manager unreadable | `GEO_SECRET_UNAVAILABLE` | `Unavailable(PROVIDER_UNAVAILABLE)` | Retryable; counts toward the breaker |
| Provider `401` / `403` after one fresh key read | `PROVIDER_AUTHENTICATION` | `Unavailable(PROVIDER_REFUSED)` | The key was rotated or revoked in the provider's console and not in OpenBao. Nothing but a person fixes it |
| Provider `400` | `PROVIDER_REJECTED` | `Unavailable(PROVIDER_REFUSED)` | Our request is malformed. A code fault, not a provider outage |
| Provider `429` | `RATE_LIMITED` | `Unavailable(PROVIDER_UNAVAILABLE)` | The provider's own quota. Try again later; counts toward the breaker |
| Provider `5xx`, refused connection | `PROVIDER_UNAVAILABLE`, `CONNECTION_FAILED` | `Unavailable(PROVIDER_UNAVAILABLE)` | Counts toward the breaker |
| Read timeout, reset connection, unreadable body | `READ_TIMEOUT`, `CONNECTION_RESET`, `RESPONSE_UNREADABLE`, `GEO_RESPONSE_UNREADABLE` | `Unavailable(PROVIDER_UNAVAILABLE)` | `UNCERTAIN` to the provider layer; for a read there is nothing to reconcile, so it is the same answer as a retryable one |
| Breaker open | `GEO_CIRCUIT_OPEN` | `Unavailable(PROVIDER_UNAVAILABLE)` | Costs no round trip |
| No approved endpoint row | `GEO_ENDPOINT_NOT_APPROVED` | `Unavailable(PROVIDER_REFUSED)` | ADR 0026: no row, no call |
| The route never started | `GEO_ROUTE_UNAVAILABLE` | `Unavailable(PROVIDER_UNAVAILABLE)` | A deploy problem, not a provider problem; see the route README |

## Runbook

**Every lookup answers `NOT_CONFIGURED`.** This is the expected state of an environment where
nobody has obtained a provider key. Check, in order: `horecaos.geo.provider` names an adapter
(`yandex`; `fake` exists only under the `local` profile and in a production environment it
reports exactly this); `horecaos.geo.yandex.secret-reference` is set; the secret behind that
reference exists in OpenBao (`GEO_CREDENTIAL_MISSING` in the logs' reason counts); and the two
`provider_environments` rows from V0490 are present. The console shows the same fact to
operators through `GET .../map-config` (`configured: false`) and the screens say a map provider
is not set up.

**`PROVIDER_REFUSED` and the key was fine yesterday.** The gateway already read past the ADR
0028 secret cache once and got the same answer, so OpenBao's copy is stale, not ours. The key
was rotated or revoked in the provider's console. Write the new value to OpenBao under the
existing reference (the reference does not change) and re-check. Nothing is queued waiting:
every operator who tried during the window was told lookup was unavailable and kept typing the
address as text.

**Rising `unavailable`, or `horecaos.geo.circuit.not_closed` at 1.** The provider is failing and
the breaker has opened, so operators are already seeing text fields instead of suggestions.
Check the provider's status page before ours. Nothing durable is lost: no order, address or
zone depends on a lookup succeeding, and a pin dropped by hand saves as it always did. The
breaker closes by itself after thirty seconds of a healthy probe.

**`GEO_ROUTE_UNAVAILABLE`, or `/actuator/health` shows the route stopped.** The route failed
to build at startup and no provider was ever contacted. This is a deploy problem; see the "When
`/actuator/health` reports a route down" section of `docs/routes/README.md`. Do not restart to
fix it.

**The provider bill is higher than expected.** `geocode.requests` (ADR 0021) counts provider
calls per tenant per billing period with an `operation` dimension; a cache hit is not counted.
Check the per-person limiter (90 suggestions and 30 lookups a minute) and the thirty-day
`geo.responses` cache before suspecting abuse: a client that does not debounce is the usual
cause.

## Rollout

Per ADR 0007 and ADR 0145: ships with `horecaos.geo.provider` unset, which is `NOT_CONFIGURED`
for everybody and breaks nothing. Local development runs `horecaos.geo.provider=fake` under the
`local` profile. Enabling the real adapter is configuration per environment: obtain the key,
write it to OpenBao, set the reference, set `horecaos.geo.provider=yandex`, and run the bake-off
of ADR 0145 decision 3 against the recorded fixtures' live equivalents before pointing a
customer at it. Rollback is unsetting the property: suggestions degrade to text, the map falls
back to the coordinates the console shows today, and checkout continues on `NOT_GEOCODED`.
