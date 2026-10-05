# Route descriptor: `marketplace.availability.v1`

Required by ADR 0007. A production route may not ship without one of these; see
`docs/routes/README.md` for the format. This is the route behind ADR 0141's
marketplace availability reconciler and ADR 0040's `marketplace.availability.push`
capability.

| Field | Value |
|---|---|
| Route IDs | `marketplace.availability.v1`, `marketplace.availability.dead-letter` |
| Version | 1 |
| Owning module | `integration` (route, gateway and reconciler), commanded by nothing outside it: the reconciler reads inventory's resolver through `inventory.api.ChannelAvailabilityPort` |
| Owner | Ayubkhon Abbosov (platform architecture) |
| Input contract | `MarketplaceApiCall` v1 (in-process command record), built by a `MarketplaceAvailabilityAdapter` from an `AvailabilityPush` state-set |
| Output contract | `ProviderOutcome` v1, from which the reconciler draws a `PushConclusion` (`CONFIRMED`, `NOT_APPLIED`, `UNKNOWN`, `REJECTED_UNMAPPED`) |
| Source | `direct:marketplace.availability` |
| Destination | An aggregator's availability API over HTTPS, selected by the ADR 0026 binding. **No adapter ships in this build**: whether Uzum Tezkor, Yandex Eda, Wolt or Express24 expose such an API to a third party is an open commercial input of ADR 0141, so a provider type with no registered adapter is never called and its bindings show `MANUAL` |
| Service identity | Per-installation partner credential, ADR 0026 |
| Secret reference type | `horecaos:{env}:provider_marketplace:{owner}:{id}` (ADR 0028) |
| Connect timeout | 5s |
| Total timeout | `MarketplaceApiCall.timeout`, or 15s when the adapter names none |
| Retry classification | **None in-route, on purpose.** An availability push sets a value, so repeating it is safe, but repeating the value the route happened to carry would be the bug: by the time a retry fires the dish may have been stopped again. The route returns the classified outcome and the level-triggered reconciler, on its next tick, sends whatever is true by then, with the backoff in `RetryBackoff` (equal jitter, 5s to 10min) |
| Idempotency key | The state-set is keyed `(binding, item, desired_seq)` and sets `available = true or false`; a duplicate sets the same field to the same value. Whether a given partner documents an idempotency header is that adapter's to say |
| Circuit breaker | Sliding window 10, minimum 5 calls, 50% failure rate, 30s open, 3 half-open probes, **one breaker per binding** rather than per provider type: one venue's revoked token must not stop forty healthy venues' stop lists. Only `RETRYABLE` counts as a failure; an unknown outcome or a business refusal is not evidence the partner is down |
| Dead-letter destination | `marketplace.availability.dead-letter` → a `RETRYABLE` `UNCLASSIFIED` outcome, which is not in `PushConclusion`'s set of failures that provably wrote nothing, so the reconciler concludes `UNKNOWN` and resends the current truth |
| PII classification | None. An availability push carries the partner's own item id, a boolean and a sequence. No customer, no item name, no order. The MDC holds the tenant id, provider type and correlation id only |
| Expected volume | Pilot: tens of pushes per binding per day outside service peaks; a brand-wide stop of a hundred dishes across forty bound venues is four thousand pushes, paced by the partner's rate limit (a `RATE_LIMITED` outcome closes the door for the rest of that binding's batch) |
| SLO | A stop reaches a connected partner within one reconciler tick plus one call (seconds) when a marker fired, and within one resync interval (5 minutes by default) when none did |
| Runbook | `docs/routes/marketplace-availability.md#runbook` |
| Dashboard | Metrics `horecaos.marketplace.route` (tags `event`, `provider`, `operation`, `status`), `horecaos.marketplace.availability.push` (tag `conclusion`), `horecaos.marketplace.availability.pending_items`, `horecaos.marketplace.availability.oldest_pending_seconds`, `horecaos.marketplace.availability.stale_channels` (bindings inside a reported stale episode), `horecaos.marketplace.channel.went_stale` (a counter, one per episode), `horecaos.marketplace.circuit.not_closed` — none labelled by tenant, binding or item |

## What the platform believes after an attempt

The route, not `FailureClassifier`, concludes one of three things, because the
shared classifier files a `SocketTimeoutException` and a `ConnectException` under
the same `TRANSIENT_INFRASTRUCTURE` and only one of them leaves the partner's state
as it was.

| Conclusion | When | What the row does |
|---|---|---|
| `CONFIRMED` | A success answer | Records the value that was sent as `confirmed_available` |
| `NOT_APPLIED` | No request was written (connection refused, circuit open, a rate limit before the send, a missing or suspended installation) or the partner refused and changed nothing (a 4xx business answer, a 429) | Changes nothing about what it believes; retried under backoff |
| `UNKNOWN` | A timeout or a reset after the request was written, a 5xx whose contract does not promise atomicity, anything unclassified | Withdraws the belief (`confirmed_available` becomes NULL, state `UNCERTAIN`); the next tick sends the current desired value whatever it is |
| `REJECTED_UNMAPPED` | The adapter reads the partner's answer as "no such item" | Not retried; appears in the mapping pane until the mapping changes |

## Runbook

**Every binding of one provider shows `MANUAL`.** No adapter for that provider type
is registered in this build. Nothing has been pushed and nothing will be. The
operator updates the partner portal by hand from the stop list; the platform still
refuses the dish on every channel it owns.

**Items stuck `PENDING` for one binding.** Read the propagation view for the branch
(`GET .../inventory/marketplace-propagation`): `lastFailureCode` names the last
refusal. `CIRCUIT_OPEN` means five or more calls failed in a window of ten; the
breaker half-opens after 30s on its own. `RATE_LIMITED` means the partner is
throttling a large batch and will drain it. `PROVIDER_AUTHENTICATION` means the
gateway already retried once past the secret cache: rotate the credential at the
partner, update the secret, and resume.

**Items `UNCERTAIN`.** A push timed out after the request was written. The platform
no longer assumes it knows what the partner holds and will send the current value on
the next successful call; nothing needs doing unless the state does not clear.

**Items `REJECTED_UNMAPPED`.** The partner does not know the item id. Fix the
`MENU_ITEM` mapping (ADR 0012); the row is replaced when the mapping changes.

**A stale-channel alert: "N dishes not confirmed since 14:32".** A binding has had at least
one dish unconfirmed longer than `marketplace.availability.stale_after_seconds` (default thirty
minutes). It is reported once per episode: `MarketplaceChannelWentStale` on `integration.events`
(counts and instants, no dish names) and an ADR 0058 operations alert to the chats subscribed to
`MARKETPLACE_CHANNEL_STALE`. The partner is still selling what the kitchen stopped. Read the
propagation view for the branch for the dishes and `lastFailureCode`; update the partner portal by
hand from the stop list while it is fixed. The report clears when nothing is unconfirmed past the
bound, so the next outage alerts again. A dish the partner refused as unknown is a mapping to fix
(`REJECTED_UNMAPPED`) and never counts as a channel gone quiet.

**An aggregator that polls instead.** `GET /api/v1/partner/tenants/{tenantId}/restaurants/{locationId}/availability`
answers from the same resolver on every call: the partner's own item id and one boolean per mapped
dish, in cursor pages (`limit` up to 200, `cursor` from the previous page's `nextCursor`).
Authorised by the aggregator's client credential and the bindings of its installation
(`marketplace.availability.pull`); a restaurant that is not one of its bound branches is a 404, the
same as one that does not exist, and an integration with no single active sales channel is a
`409 CHANNEL_NOT_CONFIGURED` rather than an all-available list.

**Decommissioning stops (`inventory.stops.read_enabled`).** Never a rollback: it sells every stopped
dish again that no position carries. Run `POST .../inventory/stop-materialisation-runs` for each
brand that has had a stop (`inventory.stop.manage` at the brand); read the report
(`GET .../stop-materialisation-runs/{id}/report`: `UNTRACKED_ITEM`, `QUANTITY_ITEM`,
`CHANNEL_SCOPE`, `MENU_NOT_EVERY_CHANNEL`, `WRITE_FAILED`, each the dish that will be on sale
again); the brand's owner acknowledges it (`POST .../acknowledgement`, `If-Match`). Only then does
the configuration write succeed; without it the answer is `409 MATERIALISATION_REQUIRED`, and any
stop made after the run asks for another. A `WRITE_FAILED` line means the stop is not carried:
run again. While the switch is off, active stops stay on their rows, ignored and marked so; new
operator and bot stops are refused as frozen; the POS poll writes the position boolean again.
Turning it back on resumes the ignored stops and is never refused.

**After an outage, or after switching the reconciler back on.** Every confirmation of
the binding is withdrawn first and everything is resent once, because the partner
portal may have been edited by hand in the meantime.

## Rollout

Per ADR 0007: ships with its bindings disabled and no adapter registered, so it does
nothing until an adapter exists for a provider and a binding for it is active.
ADR 0141's Phase 3 is a dry run for one aggregator at one branch, compared against that
partner's portal for a week, then live. Rollback is the `marketplace.availability.reconcile_enabled`
switch (ADR 0030): no call reaches any partner, the resolver is unaffected, and
resumption resends everything once.
