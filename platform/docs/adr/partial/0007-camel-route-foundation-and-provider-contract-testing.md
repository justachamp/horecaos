# ADR 0007: Camel route foundation and provider contract testing

- Decision status: Accepted
- Implementation status: Partial — the foundation and four real routes exist,
  and all four now have a domain caller.
  `ProviderExceptionClassifier` and `ProviderOutcome` are the shared
  classification; `ProviderHttpClient` and `ProviderCircuitMetrics` carry the
  per-adapter circuit breaking (`camel-resilience4j-starter` in `pom.xml`);
  `PosRouteBuilder`, `PaymentRouteBuilder`, `DeliveryRouteBuilder` and
  `NotificationRouteBuilder` are the routes, reached only through the
  provider-neutral ports `PosApiTransport`, `MerchantApiTransport`,
  `DeliveryPartner` and `NotificationTransport`. The delivery route was the
  unreachable one and no longer is: `CamelShipmentBookingPort` implements the
  fulfillment-owned `ShipmentBookingPort` over `direct:delivery.operation`, and
  the production path runs `OrderConfirmed` to `DeliveryPlanTrigger` to
  `DeliveryPlanningService`, then `DeliverySourcingScheduler` claims the sourcing
  job and `DeliverySourcingRunner` books through that port — fulfillment still
  compiling with no Camel on its classpath. `ControlledFakeProvider`
  implements all seven scenarios in test sources only and `ProviderContractTests`
  covers idempotency, rate limits, permanent rejection and uncertain outcomes.
  `ModularArchitectureTests` fails the build if any domain module imports
  `org.apache.camel` and if the Noor and Yandex adapters reference each other.
  Dependencies are settled: resilience4j is added and used, and
  camel-http/camel-kafka are correctly absent because the routes use `direct:`
  endpoints and a shared `java.net.http` client. The descriptor format is defined
  in `docs/routes/README.md`, all four route groups are described there, and
  `RouteDescriptorTests` fails the build when a route in the code is unclaimed, a
  descriptor names a route that no longer exists, a required field is blank, or a
  runbook link points at nothing. `ControlledCommandRoute` and
  `ControlledRouteTests` prove the whole inbox-route-outbox path against the fake,
  in test sources only. `CamelRouteHealthIndicator` reports a route that failed to
  start. The production Kafka-command entry point now exists for one command:
  `FulfillmentCommandListener` consumes `fulfillment.commands`,
  `ShipmentReconciliationHandler` runs `direct:delivery.operation` for a
  `ShipmentReconciliationRequested` v1 whose outcome the route could not settle
  in-exchange, and `ShipmentReconciliationOutbox` writes both that command and its
  `ShipmentOutcomeReconciled` v1 answer to `integration.outbox_events` — so rule 9
  below is exercised in production and the outbox has a third writer.
  `ExternalWorkInboxHandler` splits such a handler so the provider call runs with
  no transaction open, and `InboxHandlerRegistry.consumerNamesFor` keeps a command
  topic's records away from consumers that never registered for them. Not built:
  distributed traces, which need a tracing dependency nobody has decided to add and
  which ADR 0023 carries as its own un-built item; and the asynchronous entry point
  covers only the reconciliation command — booking, POS export, payment and
  notification calls are still invoked in-process and return their outcome to the
  caller, and a booking command in particular cannot be made asynchronous as it
  stands because its payload would carry the recipient's name, phone and address,
  which ADR 0029 keeps off every topic.
- Date proposed: 2026-08-19
- Date decided: 2026-08-20
- Deciders: Ayubkhon Abbosov (platform architecture)
- Depends on: ADR 0005, ADR 0006, ADR 0026, ADR 0028
- Supersedes / Superseded by: —
- Open inputs: none

## Context

Apache Camel is present but no route proves the intended boundary. Qoida needs
a repeatable way to consume durable commands, call providers, normalize
responses, apply retry/circuit-breaker policy, and preserve idempotency without
moving domain decisions into route DSL.

## Decision

Keep Camel inside the integration module initially. A route accepts a
provider-neutral command owned by a domain application port, maps it to a
provider-specific request inside an adapter package, performs protocol work,
and returns or emits a canonical result. Domain services decide whether that
result changes an aggregate.

Each production route must have a checked-in descriptor containing:

```text
route_id and version
owning module/team
input and output contract versions
source and destination
service identity and secret reference type
timeout and retry classification
idempotency key
circuit-breaker settings
dead-letter destination
PII/security classification
expected volume and SLO
runbook and dashboard
```

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| Plain `RestClient`/`WebClient` adapters with no Camel | Fewer moving parts, and genuinely the right answer for a single simple HTTP dependency — which is exactly why ADR 0009 uses plain HTTP for Keycloak. It loses out here because Qoida expects three POS providers, three delivery partners, several payment providers, and multiple notification channels, each needing retry, throttling, circuit breaking, idempotent repositories, and dead-lettering that would otherwise be re-implemented per adapter | If the provider count stays at two or three simple HTTP APIs, this ADR is over-engineering and should be narrowed |
| Spring Integration | Comparable EIP coverage and tighter Spring alignment, but a much smaller connector library for the protocols expected here (POS APIs, SMS gateways, file feeds) and less mature dead-letter and route-testing tooling | Camel's connector advantage stops mattering because every provider is plain HTTPS |
| Run Camel routes in a separate service from the start | Operational split before any measurement, and a second deployment to keep in lockstep with domain contracts | ADR 0023 measures a route group whose volume or failure profile justifies isolation |
| Call provider SDKs directly from domain modules | The precise coupling this platform is being rebuilt to remove. A provider SDK upgrade would become a domain change | Never |
| Let Camel routes own domain decisions such as order confirmation | Business rules would live in route DSL, untestable as domain logic and invisible to the state machines in `docs/domains` | Never |
| Test only against real provider sandboxes | Sandboxes cannot produce timeouts, connection resets, or uncertain outcomes on demand, which are the cases that actually break payments and bookings | Never as the only method; sandbox contract tests remain complementary |

## Implementation notes

Delivered: the canonical `ProviderOutcome`, the shared exception classifier, the
controlled fake provider with its contract suite, and the first real route —
`delivery.operation.v1`, described in `docs/routes/delivery-operation.md`, with
the Yandex Delivery and Noor Delivery adapters behind it.

**Four outcomes, and the fourth is the point.** Most integration bugs come from
collapsing `UNCERTAIN` into `RETRYABLE`. A read timeout *after* a request was
sent is not a failure to retry — the provider may already have charged the card
or booked the courier. The classifier's decision therefore turns on whether the
request is known to have left the process: connect timeout is retryable, read
timeout is uncertain.

**The fake exists because a sandbox cannot be asked to fail.** No real provider
sandbox will time out on demand, reset a connection mid-request, or accept a
command and then lose the reply. Those are precisely the cases that duplicate a
payment. The fake lives in test sources only, so ADR 0007's rule that no scenario
switch may exist in production provider code is enforced by the switch not
shipping at all.

**Whether the request was sent cannot be tracked with a flag.** The first
version of `ProviderHttpClient` set `requestSent = true` after `client.send()`
returned, which is wrong in the one case that matters: connect, write, and read
all happen inside that single call, so a response lost on the way back reported
"never sent" and would have been retried into a duplicate booking. The flag is
now derived from the exception type, and the default is that the provider *did*
receive the request. Only a connect-phase failure proves otherwise. Assuming
"sent" costs one reconciliation query; assuming "not sent" costs a second
courier.

**A business rejection must not open the circuit.** The route throws only on
`RETRYABLE` and `UNCERTAIN`, so the breaker counts provider faults and ignores
provider decisions. A partner declining twenty out-of-zone addresses is a partner
working correctly, and taking it offline for that would be a self-inflicted
outage. Both halves are asserted in `DeliveryRouteTests`.

**What a descriptor is for, decided before designing one.** Its job is to make a
route's contract reviewable without reading the builder and three processors —
whether a timeout is too long, whether a retry can double-charge a card, whether
a courier's personal data leaves the country. That decides the format (a field
table, then prose about consequences) and it decides what the validation test
can honestly assert. `RouteDescriptorTests` enforces coverage and completeness,
not values: it cannot check that a stated 20-second timeout matches the code, and
asserting that it could would be false assurance. It checks that every route id
the code declares is claimed by exactly one descriptor, that no descriptor claims
a route that no longer exists, that no required field is blank or a placeholder,
and that every runbook link resolves. That makes an unowned production
integration impossible to ship, which is the risk this ADR named. Route ids are
read from the source rather than from a started `CamelContext`, because every
builder has a live dependency graph behind it and booting Spring to enumerate ten
strings would make the test slow enough that somebody eventually deletes it.

**The controlled route is built and is still test-profile only.** It proves the
whole path — a versioned command on a Kafka record, through the ADR 0005 inbox,
out through Camel to a provider that can be asked to fail, and back as a
canonical result written to the ADR 0004 outbox inside the handler's transaction.
Its shape deliberately mirrors `DeliveryRouteBuilder`, because a test route that
took shortcuts the real routes do not would prove nothing about them. The broker
itself is not started: the record is offered to `InboxExecutor.execute`, the
exact method the listener calls with a consumer record's fields, and starting a
broker would test Spring Kafka's deserialisation and nothing about this path.

**The production command path exists, and it carries the one command that could
go first.** The blocker named here before was that an asynchronous route result
needs a fulfilment aggregate to own the shipment and its events; ADR 0014
delivered that, so `fulfillment.commands` and `fulfillment.events` are catalogued
and `ShipmentReconciliationRequested` v1 is the first record on either. The
command chosen is the reconciliation of an uncertain courier outcome, and the
choice is not arbitrary. It is the command this record's own error-handling
section already specifies — "an uncertain external outcome triggers a provider
reconciliation command, not a blind duplicate request" — and until now that
sentence had no implementation: a status query that failed inside the exchange
left a log line and a caller holding an outcome nobody would revisit. It is also
the only delivery command whose payload contains no personal data. A booking
command would have to carry the recipient's name, phone and address to be
executable, and ADR 0029 forbids all three on a topic; a status query needs the
partner's own reference and nothing else. And it is a decision the route is
allowed to make: asking again what already happened is "whether to call", which
is the route's job, where deciding to book or cancel is fulfilment's.

**A handler that calls a provider cannot run inside the inbox transaction.**
`InboxHandler` says so in its own javadoc, and the controlled route quietly did it
anyway — its handler calls `producer.requestBody` inside the transaction that
records the `PROCESSED` transition, which on the single box ADR 0034 provides
would hold one of ten shared connections for the length of a courier's timeout.
`ExternalWorkInboxHandler` splits the two halves: `perform` runs with nothing
open, `record` runs inside the transaction that commits the inbox transition. The
split does not reduce the duplicate-effect risk — a commit can fail after the call
either way — and it is not claimed to. That risk is answered where this record
already says it must be, by a provider idempotency key derived from the command
id.

**The last attempt settles rather than disappearing.** A query that stays
unsettled emits nothing and lets the inbox retry, because recording a retryable
outcome would settle a shipment nobody has established anything about. But
exhausting the attempts and dead-lettering would leave a possibly-booked courier
with no fact anywhere that anybody reads, so the final attempt emits
`resolution: UNRESOLVED`. It is a third value and not a synonym for `ABSENT` on
purpose: "the partner has no such shipment" makes re-issuing safe and "nobody
could find out" does not, and collapsing them is how a second courier arrives.

**An open circuit is deliberately not unhealthy.** `CamelRouteHealthIndicator`
reports route status and nothing else. ADR 0023 is explicit that a breaker
opening is the breaker working, and on a single-box deployment reporting that as
DOWN would pull the only container out of the reverse proxy because one courier
partner was having a bad hour. Circuit state is a gauge, alerted on by duration.
The indicator contributes to `/actuator/health` only — the liveness, readiness,
and customer groups list their members explicitly, and a route that failed to
start is an operator's problem to fix forward, not a reason for the watchdog to
recreate the container.

## Package shape

```text
integration/
  camel/
    common/                 shared route policies, correlation, safe logging
    testprovider/           controlled fake adapter, test profile only
    pos/clopos/             future provider-specific DTOs and mapping
    pos/rkeeper/
    pos/iiko/
    delivery/yandex/
    delivery/noor/
```

POS adapter packages remain empty by decision: their partner API documentation
has not been received, and an adapter written against a guessed contract is worse
than no adapter. They are added when the documentation arrives.

Domain modules expose small ports such as `QuoteDelivery`, `CreateShipment`,
or `ExportOrder`. They never import Camel `Exchange`, endpoint URIs, provider
DTOs, or retry classes.

## First controlled route

Build a test-profile route that consumes a versioned command from Kafka,
passes through the inbox/idempotency foundation, calls a local controlled fake
HTTP provider, and emits a canonical result through the outbox. The fake must
support deterministic scenarios selected by a non-production test header:

- success
- slow response/timeout
- HTTP 429 with `Retry-After`
- HTTP 400 permanent rejection
- HTTP 500 transient failure
- connection reset
- accepted response followed by an uncertain client timeout
- duplicated provider callback

No fake scenario switch is allowed in production provider code.

## Route rules

1. Restore tenant, event, correlation, causation, and trace context.
2. Validate the command contract before mapping.
3. Resolve a scoped installation and secret reference; never log the secret.
4. Set provider idempotency key from the canonical command ID.
5. Apply connect/read/total timeouts.
6. Normalize responses to canonical success, rejection, retryable failure, or
   uncertain outcome.
7. Use bounded redelivery only for operations proven safe under the same
   idempotency key.
8. Open a circuit on classified provider failures, not domain rejections.
9. Persist outcome/inbox state before acknowledging the Kafka command.
10. Route exhausted work into the failure operations model.

## Deployment topology

Run routes in the modular monolith for the first release. Preserve route IDs,
contracts, configuration, health, and package boundaries so high-volume or
high-risk route groups can later run in a separate Camel process from the same
repository. Do not create a separate service merely because Camel supports it.

## Configuration and secrets

- Store installation metadata and secret references in PostgreSQL.
- Resolve actual credentials at call time from the environment's secrets
  manager with bounded caching and rotation support.
- Separate provider base URLs, timeouts, and circuit policies by environment.
- Disallow arbitrary endpoint URIs from tenant-controlled configuration to
  prevent SSRF.
- Egress allowlists and TLS verification are mandatory in production.

## Error handling

Use a shared exception classifier mapping transport errors to ADR 0006 error
categories. Do not retry validation, authentication, or provider business
rejection as infrastructure failures. An uncertain external outcome triggers a
provider reconciliation command, not a blind duplicate request.

## Observability

Emit route duration, inflight, success, retry, timeout, circuit-open,
reconciliation, and dead-letter metrics with bounded provider/operation tags.
Propagate W3C trace context across Kafka and HTTP. Logs contain route,
installation, tenant, command, aggregate, and correlation IDs but redact bodies
by default.

## Testing

- Camel AdviceWith or test endpoints verify route shape without real providers.
- The fake HTTP provider verifies mapping, headers, authentication placeholder,
  timeout, retry, circuit breaker, and response normalization.
- Duplicate Kafka commands result in one fake provider side effect.
- Retry reuses the same provider idempotency key.
- Permanent 4xx goes directly to dead-letter handling.
- Uncertain outcome schedules reconciliation and never immediately repeats the
  side effect.
- Trace and correlation context appear in the canonical result.
- Secrets and payloads do not appear in captured logs.

## Rollout and rollback

The controlled route is test-profile only. The first real provider route ships
disabled, runs connection and contract tests, then enables for one test tenant
and location. Rollback disables that binding/route while durable commands remain
in PostgreSQL/Kafka for reconciliation.

## Consequences

### Positive

- Provider protocol concerns stay in adapters, so a new POS or courier partner
  is a package rather than a change to commerce code.
- Route descriptors make every production integration inventoried, owned, and
  linked to a runbook before it can ship.
- The fake provider makes timeout, rate-limit, and uncertain-outcome behavior
  testable in CI, which sandboxes cannot do on demand.

### Negative

- Camel is a substantial framework with its own idioms, error semantics, and
  upgrade cadence, and it must stay compatible with Spring Boot and Modulith as
  a set.
- Debugging spans two mental models: Spring application code and route
  exchanges, which raises the cost of onboarding a new engineer.
- Route-level retry policy can mask a systemic provider problem if metrics are
  not watched.

### Accepted trade-offs

- Simple single-provider integrations may be genuinely cheaper without Camel.
  ADR 0009 takes that exemption explicitly rather than pretending consistency
  matters more than clarity.
- The descriptor requirement adds friction to shipping a route. That is
  intended: an unowned production integration is an incident waiting to happen.

## Implementation checklist

- [x] Add required Camel Kafka/HTTP/resilience dependencies only when used. — `camel-resilience4j-starter` is present and used by all four provider families; camel-http and camel-kafka are correctly absent, because every route entry point is `direct:` and provider calls go through one shared `java.net.http` client.
- [x] Define route descriptor format and validation test. — The format is `docs/routes/README.md`, with a descriptor for each of the four route groups. `RouteDescriptorTests` fails the build on an unclaimed route, a stale descriptor, a blank required field, or a runbook link that resolves to nothing.
- [x] Implement the shared error classifier and canonical `ProviderOutcome`.
- [x] Implement the controlled fake HTTP provider with all seven scenarios, in test sources only.
- [x] Build the Kafka-inbox-Camel-outbox test route. — `ControlledCommandRoute` plus `ControlledRouteTests`, in test sources only: a duplicated command invokes the provider once, a transient failure retries under the same idempotency key, a permanent rejection is recorded and never retried, and an uncertain outcome is reconciled by a status query that creates no side effect.
- [x] Add the contract suite covering idempotency, rate limits, permanent rejection, and uncertain outcomes. Circuit breaking and dead-lettering arrive with the first real route.
- [ ] Add route health, metrics, traces, and runbook example. — Health, metrics, and runbooks are built: `CamelRouteHealthIndicator` reports a route that failed to start (the failure that otherwise looks exactly like a provider outage and has the opposite fix), the per-route counters and circuit gauges are the metrics, and every descriptor in `docs/routes/` carries its own runbook section. **Traces are not built**: no tracing dependency is on the classpath, W3C propagation across Kafka and HTTP is one of ADR 0023's four un-built items, and adding one is that record's decision to make. Correlation context is propagated through the MDC by each route's `restoreContext`.
- [x] Verify Spring Modulith boundaries keep Camel out of domain modules.

## Exit criteria

An integration test proves that a duplicated Kafka command invokes the fake
provider once, transient failures retry within policy, permanent failures are
dead-lettered, and uncertain outcomes reconcile without duplicate side effects.
