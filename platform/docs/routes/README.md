# Route descriptors

ADR 0007 requires a checked-in descriptor for every production Camel route. This
file defines what one is, and `RouteDescriptorTests` fails the build when the
inventory and the code disagree.

## What a descriptor is for

**A descriptor makes a route's contract reviewable without reading its builder.**
That is the whole job, and it decides the format. Someone judging whether a
timeout is too long, whether a retry can double-charge a card, or whether a
courier's personal data leaves the country needs those answers on one page,
stated by the route's owner, not reconstructed from DSL and three processors.

Two consequences follow from that purpose:

- **A descriptor is prose about consequences, not a copy of the code.** Field
  values name the numbers, and the tables under them say what happens on each
  outcome and why the alternative was rejected. A descriptor that only restated
  what the builder already says would be a second thing to keep in sync and no
  easier to review.
- **The validation test enforces coverage and completeness, not values.** It
  cannot check that a stated 20-second timeout matches the code, and pretending
  otherwise would be false assurance. It checks the things that are actually
  checkable: that every route the code declares is claimed by exactly one
  descriptor, that no descriptor claims a route that does not exist, and that no
  required field has been left blank or filled with a placeholder. An unowned
  production integration then becomes impossible to ship, which is the risk ADR
  0007 named.

## Format

One file per route group, named after its canonical route. It opens with a table
of the required fields, in any order:

| Field | Meaning |
|---|---|
| `Route IDs` | Every Camel route id in the group, backticked and comma-separated. The canonical route comes first, and its dead-letter and query routes follow. This is the field the validation test matches against the code |
| `Version` | The contract version the canonical route serves |
| `Owning module` | The module that owns the route, and the module that commands it if they differ |
| `Owner` | A named person. "The platform team" is not an owner |
| `Input contract` | The provider-neutral command type and its version |
| `Output contract` | The canonical result type and its version |
| `Source` | The endpoint URI callers send to |
| `Destination` | What the route talks to on the far side |
| `Service identity` | Whose credential the call runs as |
| `Secret reference type` | The ADR 0028 reference shape, never a value |
| `Connect timeout` | How long before a connection attempt is abandoned. A connect-phase failure is the only one that proves the request never left |
| `Total timeout` | The per-call deadline, and the default applied when a caller names none |
| `Retry classification` | Which outcomes retry, where the retry happens, and which do not |
| `Idempotency key` | The value the provider deduplicates on, and whether the provider actually documents it |
| `Circuit breaker` | Window, threshold, open duration, half-open probes — or `None`, with the reason |
| `Dead-letter destination` | The route id that handles exhaustion, and where the work lands afterwards |
| `PII classification` | What personal data the exchange carries under ADR 0029 |
| `Expected volume` | What this route is sized for, so a tenfold jump is recognisable as one |
| `SLO` | The latency the route is held to, per operation where they differ |
| `Runbook` | A link to a runbook section that exists |
| `Dashboard` | The metric name and its bounded tags |

`Circuit breaker`, `Idempotency key`, and `Retry classification` may be answered
with `None` — several routes deliberately have none — but the answer has to say
why, because "no retry" and "nobody thought about retry" look identical in code
and are opposite facts about a route.

## When `/actuator/health` reports a route down

`CamelRouteHealthIndicator` names every route and its status. A `DOWN` here is
not a provider outage even though it looks like one from outside: the route never
started, so callers get "no consumers available" for every attempt and no
provider was ever contacted. The two have opposite fixes — one is a phone call to
a partner, the other is a deploy — so check this before the partner's status page.

1. Read the `stopped` list in the health details. It names the route ids.
2. Look for the Camel startup failure in the application log at boot. A route
   that failed to build logs once and the application then serves HTTP normally,
   which is why this is invisible without the indicator.
3. Nothing durable is lost while a route is down. Provider calls are made
   in-process by a caller that will classify the failure, and work that was
   already accepted sits in PostgreSQL under ADR 0004 and ADR 0006.
4. Do not restart to "fix" it unless the log says the failure was transient.
   A route that cannot build will not build on the second attempt either, and the
   restart discards the log line that says why.

The indicator contributes to `/actuator/health` only. It is deliberately not in
the liveness, readiness, or customer groups: a route that failed to start is an
operator's problem to fix forward, not a reason for the watchdog to recreate the
only container on the box. An open circuit breaker is likewise not reported here
— that is a gauge, and ADR 0023 alerts on how long it has been open.
