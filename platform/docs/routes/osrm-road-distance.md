# Route descriptor: `routing.road-distance.v1`

Required by ADR 0007; the decision is ADR 0147. A production route may not ship without
one of these; see `docs/routes/README.md` for the format.

| Field | Value |
|---|---|
| Route IDs | `routing.road-distance.v1`, `routing.road-distance.dead-letter` |
| Version | 1 |
| Owning module | `integration` (route and adapter), asked by `fulfillment` through `fulfillment.api.RoadDistancePort` (implemented by `CamelRoadDistancePort`) |
| Owner | Ayubkhon Abbosov (platform architecture) |
| Input contract | `RoadDistanceCommand` v1 (in-process command record): the branch's point, the customer's destination, and the tariff version's `routing_provider_installation_id` |
| Output contract | `Optional<RoadRoute>` v1 (`meters`, `seconds`, `provider`, `datasetVersion`) in the exchange body. Empty means "did not answer", never a fabricated number |
| Source | `direct:routing.road-distance`, reached synchronously from `DeliveryFeeResolver` on the quote path, once per quote and per cart re-quote |
| Destination | The platform's own OSRM engine, `GET /route/v1/driving/{lon},{lat};{lon},{lat}` with `overview=false`, `steps=false`, `alternatives=false`, over the private network only. The URL is the `base_url` of the approved ADR 0026 environment `osrm_internal` (`http://osrm:5000`), reached through a tenant's `ROUTING` installation. A tenant never supplies a URL |
| Service identity | None. The engine is keyless and answers only on the internal network; the installation is the tenant's standing to use it, not a credential |
| Secret reference type | None. There is no secret: `integration.installations.secret_reference` is null for a platform routing installation, and the adapter holds no credential |
| Connect timeout | 5s (`ProviderHttpClient`'s own ceiling), but never reached in practice: the whole call is bounded by the total timeout below, and a local engine connects in microseconds |
| Total timeout | 500 ms for the whole exchange including the body (`horecaos.routing.osrm.timeout`). ADR 0147 gives the checkout path exactly this: a quote waits half a second at worst for a distance and then prices from the straight line |
| Retry classification | **None, deliberately.** One attempt per quote. A timeout, a 5xx, a refused connection and a malformed body are all "no answer"; none is retried on the checkout path, because a second attempt spends the customer's wait on a question the first already answered. "No route between these points" (`NoRoute`, `NoSegment`) is the engine working and is not a failure of any kind |
| Idempotency key | None, and none needed: a route measurement has no side effect and creates nothing, so repeating one is harmless. The cache (below) makes repeats cheap, not safe |
| Circuit breaker | Sliding window of 20 calls (or the minimum, if larger), minimum 10 calls, 50% failure rate, 30s open, 3 half-open probes (`horecaos.routing.osrm.breaker-minimum-calls`, `...breaker-open-for`). Engine faults count (timeout, 5xx, refused, malformed answer); "no route" and a request the engine refused as invalid do not. Unlike the SMS route, the fallback here is graceful, so an open breaker costs a slightly wrong fee and never a failed sign-in or a failed checkout. While open, a call returns empty without waiting |
| Dead-letter destination | `routing.road-distance.dead-letter`, which answers "no distance", counts `horecaos.routing.calls{outcome="error"}` and logs the exception class only. **No durable record**: a distance that was not measured is not work to retry later, and the quote it was asked for has already been priced without it |
| PII classification | Two coordinates and no identifier, one of which is a customer's delivery point: a precise location is as identifying as the address beside it (ADR 0029). Nothing else is sent. `RoadDistanceCommand` prints neither point from `toString`, `ProviderHttpClient.getWithSensitivePath` logs the label `osrm.route` instead of the path, and no metric label carries a coordinate, a tenant or an installation |
| Expected volume | Pilot: under 2,000 quotes/day/tenant, of which the cache absorbs repeated doorsteps. The engine's own capacity is far above that on one core |
| SLO | p95 under 100 ms for an engine call (local, single-digit to low tens of ms); the 500 ms timeout is the ceiling, not the target |
| Runbook | `docs/runbooks/load-uzbekistan-routing-dataset.md` |
| Dashboard | Metrics `horecaos.routing.calls` tagged `outcome` (`ok`, `timeout`, `no_route`, `breaker_open`, `error`, `unavailable`), `horecaos.routing.cache` tagged `result` (`hit`, `miss`), `horecaos.routing.call.duration` tagged `outcome`, `horecaos.routing.dataset.age_days`, and the resolver's `horecaos.delivery.distance.fallbacks` and `horecaos.delivery.distance.road_measurements` tagged `mode` |

## What the route is and is not

The route is the ADR 0007 seam. It does not decide what a delivery costs, whether an
address is served, or which tariff applies; those are `DeliveryFeeResolver`'s (ADR 0037),
and the resolver is unchanged by this route: an empty answer falls back to
straight-line distance times the tariff's detour factor, records
`distance_source = RADIUS_FALLBACK` and increments a counter, exactly as it did while the
port answered empty on every call.

What the route adds is a number that was measured, with the dataset that measured it.
`RoadRoute.datasetVersion` is the tag of the dataset image deployed beside the engine, and
it is stored on every fee the engine prices (`delivery_fee_resolutions.routing_dataset_version`)
and on the quote's own evidence. It is **not** in the quote's context hash: an issued
quote keeps the fee it was issued with across a monthly dataset refresh, and the next
quote measures against the new map.

## Outcome policy

| Outcome | What the adapter does | Breaker | Why |
|---|---|---|---|
| Route found | Returns metres, seconds, `osrm`, the dataset tag; caches it for 24 hours | success | — |
| `NoRoute` / `NoSegment` (HTTP 400) | Empty | success | The engine answered. A pin on the far side of a canal is the customer's, not the engine's fault |
| Any other 4xx | Empty, logs the error code | success | Our request was wrong, not the engine; counting it would open the breaker on a healthy engine because of a bug here |
| 5xx, refused connection | Empty | failure | The engine is unhealthy |
| Timeout, connection lost | Empty | failure | The engine did not answer in time. Never retried |
| 200 with no route, or an impossible figure | Empty | failure | An answer that is not a route is a broken engine |
| Engine switched off, dataset unnamed, installation absent or not `ACTIVE`, installation not `ROUTING`/`OSRM` | Empty, nothing sent | not consulted | The engine-wide rollback is the flag. The installation is read before the cache, so a status that is not `ACTIVE` takes effect at once even for a cached route; but no console or API door sets one for a platform routing installation today (Integrations suspends bindings only), so this is a guard and not an operator's switch |
| Breaker open | Empty, nothing sent | n/a | A slightly wrong fee, never a failed quote |

## The cache

`routing.road_routes` (ADR 0033), 24 hours, 50,000 entries, keyed by tenant, installation,
the branch's exact point, the destination rounded to four decimals (about 11 m) and the
dataset version. A new dataset tag is a miss for every entry, so the cache never serves a
figure from another map. It is a performance aid and never an authority: the fee row stores
the metres it used, not a cache key.

## Rollout

Off by default (`horecaos.routing.osrm.enabled=false`), so nothing changes until a
deployment runs the `routing` compose profile and sets the flag. Enable it for one pilot
tenant's `ROAD` tariff and compare a week of fees with their `RADIUS_FALLBACK` shadow.
Rollback is switching the flag off, which answers empty for every tenant so the resolver
falls back and every fee says `RADIUS_FALLBACK`, or, for one tenant, activating a new
version of its `ROAD` tariff in `RADIUS` mode, which stops that tariff asking the engine at
all (its fees say `RADIUS`, with no detour factor, so it is a price change to agree with the
tenant). Neither needs a deploy of code; runbook step 7 has the procedure.
