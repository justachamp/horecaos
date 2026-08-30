# ADR 0011: POS installations, bindings, and capability adapters

- Decision status: Accepted
- Implementation status: Partial — the model, capability discovery and the
  export path are built against Clopos, and the export path now has a caller.
  `V0013` holds
  `integration.provider_environments`, `installations`, `bindings`,
  `binding_capabilities` and `provider_entity_mappings`, and `V0036` adds
  `pos_provider_capabilities`, `pos_capability_probes`, `pos_order_exports`,
  `pos_export_attempts` and `pos_export_candidates`;
  `ProviderInstallationController` installs, binds, activates and suspends
  behind `INTEGRATION_INSTALLATION_MANAGE` / `INTEGRATION_BINDING_ACTIVATE`;
  `PosAdapter`, `PosAdapterRegistry` and the test `FakePosAdapter` are the
  provider-neutral ports, `CloposAdapter` is the one real adapter reaching the
  provider only through `PosApiTransport` and the ADR 0007 route, and
  `PosCapabilityService` runs the capability probe.
  `PosOrderExportService` implements `open`, `send`, `discoverOutcome` and
  `settleByOperator` with `ExportStateMachine` and `UncertainExportResolver`,
  and `PosOrderExportController` exposes the `AWAITING_OPERATOR` queue,
  candidates, discovery and resolution. The gap that mattered is closed:
  `PosOrderExportTrigger` listens for `ordering.api.OrderConfirmed` and calls
  `open` at `BEFORE_COMMIT`, so the export row and the confirmation that caused
  it commit together, then dispatches `send` from a `@Scheduled` loop rather than
  the confirming thread, so a slow till is not a checkout outage. A confirmed
  order therefore reaches a till and the operator queue can receive a row in
  production. One durability weakness remains and the class states it: the
  dispatch hint is an in-process `ConcurrentLinkedQueue`, so an export whose
  process dies between commit and the next tick stays `PENDING` with no sweep
  over `PENDING` rows to find it. Also not built: the
  session token cache is an in-process `ConcurrentHashMap` in `CloposSession`
  rather than ADR 0033 shared state; there is no ADR 0030 polling-cadence policy
  and no `RateLimit-Remaining` back-off; the only POS meter is the route counter
  `qoida.pos.route` in `PosProcessor` — there is no export or operator-queue
  metric, no POS health check and no `AWAITING_OPERATOR` runbook in
  `docs/runbooks/`; `CUSTOMER_UPSERT` is declared `UNSUPPORTED` by `CloposAdapter`
  and not implemented; and `clopos.correlationEchoVerified` has never been set by
  a real experiment. The
  exit criteria are still not met — see "Blocked on Clopos" below.
- Date proposed: 2026-08-19
- Date decided: 2026-08-20
- Date revised: 2026-08-23 (Clopos contract read; capability model and export
  path implemented)
- Deciders: Ayubkhon Abbosov (platform architecture)
- Depends on: ADR 0007, ADR 0008, ADR 0026, ADR 0028, ADR 0029, ADR 0033
- Supersedes / Superseded by: —
- Open inputs: Clopos answers to Q1, Q2, Q18 and Q19
  ([`docs/providers/clopos-api.md`](../../providers/clopos-api.md) §12)

## Context

The pilot restaurant's POS is Clopos. r_keeper and iiko remain plausible later
providers and no adapter exists for either; this ADR is written so that adding
one is a package plus configuration rather than an edit to commerce code.

Qoida remains authoritative for customer-facing products, prices, and
availability after controlled synchronization. Provider checks inside
ordering/catalog code would make every new POS expensive and fragile.

Clopos's API was read in full on 2026-08-23 — the OpenAPI 3.1 document and all
32 documentation pages — and the findings are recorded in
[`docs/providers/clopos-api.md`](../../providers/clopos-api.md). Four of them changed
this ADR rather than merely confirming it.

### What Clopos established

1. **Authentication is per brand, not per venue.** One credential set covers
   every venue under a Clopos brand; the venue is chosen with an `x-venue`
   header per request. An installation is therefore a *brand* and a binding is a
   *venue*, which is the shape this ADR already had — and the rejected
   alternative "one installation row per location" would have been actively
   wrong, duplicating one secret across every venue.
2. **Capability is a property of the installation, not of the provider.** The
   restaurant generates the credential in their own back office and chooses
   which Staff user it acts as; every call runs with that user's permissions.
   Two restaurants on the same Clopos version expose different surfaces. The
   empirically discovered `capability_snapshot` is not a hedge — it is the only
   correct model, and Clopos is the proof case.
3. **There is no idempotency mechanism of any kind.** No key, no header, no
   documented repeat semantics, no dedupe window; Clopos's own retry guidance
   concedes it, telling integrators to check the server state before re-sending
   a non-idempotent request. With an eight-second upstream timeout, uncertain
   outcomes are routine. This is the single largest risk on the integration and
   the reason for the export state machine below.
4. **Nothing reports preparation.** The only preparation-shaped field,
   `Receipt.order_status`, is one *we* write through `PATCH /receipts/{id}`, and
   every other receipt field is explicitly read-only. `PreparationStatusCapability`
   is therefore **not supported** and must be unconfigurable, not merely
   undocumented.

## Decision

Model a tenant-owned `IntegrationInstallation`, bind it explicitly to brands or
locations, discover/configure independent capabilities, and keep provider
protocols in isolated Camel adapters. Domain code selects capabilities through
ports and never branches on provider name.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| Branch on provider name inside `catalog` and `ordering` | Every new POS becomes an edit to core commerce code, and provider quirks leak into business rules. This is the specific cost the capability model removes | Never |
| One installation row per location | Duplicates credentials per restaurant, so rotation must touch every location and a tenant-level provider account cannot be represented at all | Never; per-location differences belong in bindings and overrides |
| Store provider credentials encrypted in PostgreSQL | Still an application-owned secret store, with weaker rotation, weaker access separation, and worse audit than a purpose-built secrets manager. ADR 0028 owns this | The chosen secrets manager becomes unavailable in a target environment, forcing an explicitly approved fallback |
| Expose a generic pass-through POS proxy API | Server-side request forgery by design, and an unbounded provider surface that can never be contract-tested | Never |
| Assume every POS supports every capability and fail at runtime | Turns a configuration error into a customer-visible order failure. Capability discovery makes the gap visible at binding time | Never |
| Let tenants supply arbitrary provider base URLs | Server-side request forgery and an untestable egress surface | Never; approved provider environments only |
| Model payment and delivery providers separately from POS | Would produce three near-identical installation and binding models. The generic model is now extracted into ADR 0026 and reused by ADRs 0013, 0014, and 0020 | Never |

## Physical model

The installation, binding, secret-reference, and provider-mapping tables below
are **not POS-specific**. They are the generic provider integration model, now
owned by [ADR 0026](../built/0026-provider-installations-bindings-and-secret-references.md)
and reused by payments (ADR 0013), delivery (ADR 0014), and notifications
(ADR 0020), each of which references a binding by `provider_binding_id`. This
ADR keeps them here for readability but defers to ADR 0026 on any difference,
and adds only the POS-specific capability set and order behavior.

### `integration.installations`

```text
id, tenant_id, provider_type, display_name
status, secret_reference, non_sensitive_config jsonb
capability_snapshot jsonb, adapter_version
last_connection_check_at, last_connection_status
version, created_at, updated_at
```

Unique provider account references are stored only if non-sensitive. Actual
credentials remain in a secrets manager.

### `integration.bindings`

```text
id, tenant_id, installation_id
brand_id null, location_id null
status, priority, effective_from, effective_until
configuration_override jsonb
version, created_at, updated_at
```

A location binding includes matching brand/tenant ancestry. Scope checks require
at least brand or location, and location implies brand. Only one effective
primary binding per location/capability is allowed unless an explicit failover
policy exists.

### `integration.provider_entity_mappings`

```text
id, tenant_id, installation_id, binding_id
entity_type, qoida_entity_id, external_entity_id
external_parent_id, status, mapping_source
last_seen_at, version, created_at, updated_at
unique(binding_id, entity_type, external_entity_id)
unique(binding_id, entity_type, qoida_entity_id)
```

Ambiguous mappings are conflicts, never last-write-wins.

## Capability model

Ten independently testable ports. The first six are the original set; the last
four were found while reading a real POS and are declared here rather than left
as provider quirks, because each is something a domain module will want and none
of them is Clopos-specific in principle.

```text
CATALOG_READ                    AVAILABILITY_READ
ORDER_APPROVAL                  ORDER_EXPORT
ORDER_CANCELLATION              PREPARATION_STATUS
RECEIPT_READ                    FISCAL_IDENTIFIER_WRITE_BACK
FULFILLMENT_STATUS_WRITE        CUSTOMER_UPSERT
```

`FULFILLMENT_STATUS_WRITE` is the mirror image of `PREPARATION_STATUS` and the
two are kept apart deliberately. One is outbound telemetry we send a restaurant;
the other is a report a kitchen sends us. On Clopos they would be the same
field, and collapsing them is exactly how a screen comes to present our own
writes to a branch manager as though the kitchen had said them.

### Support is three-valued, not a boolean

`SUPPORTED` / `PARTIAL` / `UNSUPPORTED`. The middle value exists because the
failure that reaches a customer is the half-working capability: Clopos's order
cancellation works before the clerk accepts the order and sets a decorative
label afterwards, and calling that either "supported" or "unsupported" puts a
false statement in front of whoever configures the branch. A `PARTIAL` entry
carries a stated rationale naming which half is missing.

### Two stores, and the narrowing rule between them

| Store | Answers | Owned by |
|---|---|---|
| `integration.pos_provider_capabilities` | what this vendor's API can *ever* do | Platform |
| `integration.pos_capability_probes` | what one credential *did* | Discovery, append-only |
| `integration.installations.capability_snapshot` (ADR 0026) | the current answer | Discovery |

A probe can only narrow the ceiling and never widen it. An adapter that appears
to discover a capability the vendor's API has never had has found a bug in its
own probe, and `PosCapabilityService` caps the discovered value rather than
trusting it.

A capability entry carries version, limits, push support, **idempotency
behaviour** (`KEYED` / `NATURALLY_IDEMPOTENT` / `NONE`), evidence, and the time
it was verified. `NONE` is the value the whole export design follows from.

### An unsupported capability is unconfigurable, in the database

Not "the control-plane UI must prevent it". A rule held in one screen is a rule
a migration, a fixture, or a support script at two in the morning can route
around. `integration.pos_capability_is_supported()` is a trigger on
`integration.binding_capabilities` that refuses to enable a POS capability the
vendor does not have, and refuses one nobody has assessed rather than defaulting
to permissive. It is scoped to POS installations and lets every delivery and
payment capability code through untouched.

Probes must be free of side effects. A capability that can only be demonstrated
by doing the thing — creating an order — is reported `UNVERIFIABLE`, which is a
different statement from "unsupported" and is recorded as one. A discovery run
that sends a kitchen a test dinner is not a discovery run.

### Clopos's assessed capabilities

| Capability | Support | Why |
|---|---|---|
| `CATALOG_READ` | Supported | Full re-read only; no incremental fetch, no change feed |
| `AVAILABILITY_READ` | Partial | Stop list only; per-venue availability is an undocumented array |
| `ORDER_APPROVAL` | Partial | A genuine authority, but polled — see the latency note below |
| `ORDER_EXPORT` | Supported | And the risk: no idempotency key of any kind |
| `ORDER_CANCELLATION` | Partial | Pre-acceptance only; post-acceptance is a label, not a cancel |
| `PREPARATION_STATUS` | **Unsupported** | The only such field is one we write |
| `RECEIPT_READ` | Supported | Feeds ADR 0043 |
| `FISCAL_IDENTIFIER_WRITE_BACK` | Supported | ADR 0038's loop-closing write |
| `FULFILLMENT_STATUS_WRITE` | Supported | Outbound only |
| `CUSTOMER_UPSERT` | Partial, not enabled | Needs an ADR 0029 consent basis, not an endpoint |

## Module layout

The original sketch put adapters in `integration.camel.pos.<vendor>`. Built, it
follows the shape ADR 0013's payment adapters already use, and for the same
reason: a vendor's wire knowledge belongs with the module that owns the *meaning*
of what is being sent.

```text
pos.api                          capability vocabulary, snapshot  (named interface)
pos.domain                       export state machine, difference engine, quorum
pos.application                  services and the provider-neutral PosAdapter port
pos.infrastructure.clopos        the only place the word "clopos" appears
pos.infrastructure.persistence   JdbcClient stores
pos.web                          control-plane endpoints

integration.api.pos              PosApiCall / PosApiTransport  (the seam)
integration.camel.pos            the ADR 0007 route, gateway, and circuit breakers
```

`pos` compiles without Camel on its classpath and `PosModuleBoundaryTests`
enforces it, along with two more boundaries: no provider name escapes
`pos.infrastructure.clopos`, and `pos.domain` holds no JDBC and no HTTP — the
difference engine and the uncertainty resolver have to be provable by reading
them.

`PosApiCall` carries an **effect classification** rather than a mutating flag:
`READ`, `IDEMPOTENT_WRITE`, `UNKEYED_CREATE`. Everything the route does with a
failure follows from it, and the third value exists as its own name so that
nobody has to remember which endpoints it applies to.

Physical model: [`V0036`](../../../src/main/resources/db/migration/V0036__create_pos_capability_model_and_order_export.sql).

## Configuration resolution

Provider policy resolves through platform, tenant, brand, then location. The
installation is tenant-owned; location bindings determine actual restaurant
scope. Configuration can specify enabled capabilities, external organization/
restaurant IDs, sync schedule, field authority policy, approval support,
timeouts, and safe failover.

## Connection and capability discovery

On create/update:

1. Validate provider type and allowed endpoint template.
2. Store non-sensitive configuration and a secret reference.
3. Execute a bounded connection check through the adapter.
4. Discover or verify capabilities against a controlled provider call.
5. Read back provider restaurant/unit identity.
6. Require an operator to confirm binding to the intended location.
7. Store capability snapshot, provider IDs, adapter version, and evidence.

Do not accept tenant-provided arbitrary URLs. Endpoint selection uses approved
provider environments and egress allowlists.

## APIs

```text
POST /api/v1/control-plane/tenants/{tenantId}/integrations
GET  /api/v1/control-plane/tenants/{tenantId}/integrations
GET  /api/v1/control-plane/tenants/{tenantId}/integrations/{installationId}
POST /.../{installationId}/connection-checks
POST /.../{installationId}/capability-reconciliation
POST /.../{installationId}/bindings
GET  /.../{installationId}/bindings
POST /.../{installationId}/bindings/{bindingId}/activate
POST /.../{installationId}/bindings/{bindingId}/suspend
```

Secret values are write-only and ideally submitted directly to the secrets
manager workflow; API responses return only reference metadata.

## POS order behavior

- Auto-confirmed Qoida orders export asynchronously.
- Restaurant-approval mode may request decisions from both Operations and a POS
  implementing reliable approval.
- First valid approval/rejection wins atomically in ordering.
- A POS transport failure never reverses a confirmed commercial order.
- If POS approval creates the external order, its external ID completes export
  reconciliation and prevents a duplicate export.

### Approval latency, not only approval failure

The original text addressed a POS *failing*. Clopos publishes no webhooks, so the
clerk's decision reaches us one poll interval late, and that needs saying too.

At a fifteen-second cadence, Operations wins essentially every race, and "first
valid approval wins" degrades into "Operations decides, and the POS's answer
arrives afterwards to be reconciled". The correctness question is what happens
when the clerk pressed *reject* thirty seconds before we read it and Operations
has already accepted.

**The answer is the same as for a transport failure: the commercial order
stands.** A confirmed order is a promise Qoida made to a customer, and a decision
we had not yet observed when we made it cannot retract it. The late rejection
becomes an operational fact the branch and Operations resolve through the
ordinary cancellation path, with a reason and an ADR 0027 record — not a silent
reversal. `ORDER_APPROVAL` is recorded `PARTIAL` on Clopos with
`decisionLatency: one poll interval` in its limits, so a control plane offering
"the POS decides" also shows how late the decision arrives.

### An export whose outcome is unknown

This is the part Clopos forced. There is no idempotency key, so a lost response
is not a failure to retry — it is a request whose outcome has to be discovered.

```text
PENDING ──▶ SENT ──▶ ACCEPTED | REJECTED | UNCERTAIN
                                              │
UNCERTAIN ──▶ RESOLVED_LANDED   (only when the provider echoed our reference)
          └─▶ AWAITING_OPERATOR ──▶ RESOLVED_LANDED | RESOLVED_ABSENT | ABANDONED
RESOLVED_ABSENT ──▶ SENT        (the only state a second attempt leaves from)
```

**There is no edge from `UNCERTAIN` back to `SENT`.** Three separate mechanisms
carry that, because any one of them alone can be worked around: the state machine
cannot express a blind retry, the ADR 0007 route beneath has no redelivery at
all, and claiming an export for an attempt is a conditional update so two workers
produce one send.

*Resolution.* A recovery read searches the provider's orders for the day at the
bound venue and attaches what it found as evidence. It is deliberately **not**
filtered by status: an order the clerk accepted between our timeout and our read
has already left `PENDING`, and a filtered read would report it absent — and an
export reported absent is an export somebody may send again.

The rule for what the read establishes is one sentence: **an automatic resolution
requires the provider to have handed back our own reference.** Everything else —
one candidate, no candidates, five candidates — goes to a person holding
`pos.export.resolve`. The two obvious automatic rules are both wrong:

- *One heuristic match means it landed.* The heuristic is venue, phone, creation
  time and line composition. A customer who orders the same basket twice ninety
  seconds apart produces exactly one such match per export, and no field in the
  API separates the two cases. This is a coin toss dressed as reconciliation, and
  it fails in the direction that costs food.
- *No candidates means it did not land, so send it again.* Absence from a read is
  not absence at the provider. The read is a paged list over a table the
  restaurant is editing, taken seconds after a call that timed out. Auto-resending
  on a negative read is the same duplicate, arrived at more slowly.

That is a real operational cost — every uncertain export costs an operator a
decision — and it is the honest price of the missing key. It belongs in the
operations budget rather than hidden inside a guess. The day Clopos answers Q1 or
Q2 affirmatively and the echo is verified against the pilot brand, the queue
empties by itself: `UncertainExportResolver` already returns `LANDED` on an
echoed reference and `RETRY_UNDER_KEY` on a provider that deduplicates, and
neither path needs new code.

*What we send anyway.* `order_number` carries the Qoida public order number,
truncated to the twenty characters Clopos's prose documents and its schema omits.
It costs nothing if dropped, makes the recovery read deterministic if honoured,
and is unique per location per day — which is exactly the scope the recovery read
searches.

*Pilot posture.* `auto_order_accept` defaults to false. An order sitting in
`PENDING` awaiting a clerk is recoverable and visible; an order auto-accepted and
auto-sent to a station is already food. The safe failure and the convenient
configuration point in opposite directions here.

*Personal data.* The export needs the customer's name, phone and address —
a courier cannot deliver to a hash — so `JdbcPosOrderSource` reveals them from
`ordering.order_customer_snapshots` under an ADR 0029 purpose, for one call. The
export row keeps only a hash of the phone, which is enough to compare a candidate
against and not enough to call anybody.

## Events and commands

```text
IntegrationInstalled
IntegrationBindingActivated
IntegrationCapabilityChanged
PosSyncRequested
PosOrderApprovalRequested
PosOrderExportRequested
PosOrderExported
PosIntegrationFailureDetected
```

Provider commands retain stable IDs/idempotency keys. Raw provider payloads are
protected integration evidence with explicit retention.

## Security and audit

- Tenant owner/admin may configure own installations under entitlement policy.
- Platform admins can inspect safe diagnostics, not reveal credentials.
- Secret rotation does not change installation ID.
- Audit installation/binding/capability/secret-reference changes and connection
  checks.
- Redact authentication headers and sensitive payload fields from logs/traces.

### Rotation is a support ticket, and ADR 0028 has to accommodate that

Clopos states it outright: once generated, a client secret stays visible on the
module page and **cannot be regenerated from the back office**. Rotation is an
email to their support address, with a human turnaround, initiated by the
restaurant rather than by us and not on any schedule.

Two consequences, and the second is worse than the first.

- **Any ADR 0028 rotation policy that assumes self-service rotation does not
  apply to Clopos credentials.** They are long-lived and effectively
  unrotatable, and this ADR does not promise a rotation SLA the platform cannot
  execute.
- **The secret remains permanently readable in the customer's own back office**
  to anyone holding the add-ons permission. So the blast radius of a compromised
  back-office account includes our integration, and we cannot cut it off
  ourselves except by suspending the binding.

**Binding suspension is therefore the actual containment control**, not
rotation. `PosGateway` refuses to call a non-`ACTIVE` installation for exactly
this reason: a suspended installation may well mean "we believe this credential
is compromised", and calling anyway would be worse than useless.

The session token is cached in process today, and that is a stated limitation
rather than a design. Clopos's first rate-limit tier is sixty `POST /auth` calls
a minute keyed on client IP, so a fleet of pods each minting its own token walks
into it; ADR 0033's shared runtime state is where the cache belongs, and
`CloposSession`'s interface is narrow enough that moving it is one implementation.

## Testing

The abstraction is proved against the ADR 0007 fake rather than against a second
real vendor, and the fake is built to be *unlike* Clopos in every way that
matters: it deduplicates on a key, it pushes, it reports preparation, and it
walks its catalog by identifier. A contract only ever exercised against Clopos
would be a description of Clopos with an interface in front of it.

`PosAdapterContractTests` runs the same assertions against both. Notice what is
not among them: that both support the same things. They do not, and a contract
requiring it would have to invent a preparation feed for a till that has none.

- A discovered capability never exceeds what the adapter declares.
- Every declared capability has a snapshot entry — an absent one reads as "not
  discovered", which is indistinguishable from "not asked".
- Every adapter states what a repeated export would do, since the resolver's
  whole decision turns on that value.
- Nothing throws for a provider failure; every path yields a classified outcome.
- The fake proves what an idempotency key would buy: three exports under one
  reference produce one order. Clopos's snapshot says `NONE`, and the same
  resolver sends its uncertain exports to a person.
- `PosSchemaTests` proves the two database rules against a real PostgreSQL:
  `PREPARATION_STATUS` cannot be enabled on a Clopos binding, an unassessed
  capability is refused rather than assumed, a payment binding is untouched by
  the rule, and one order has one export row.
- Secret values never appear in database responses or captured logs; the client
  secret enters only the authentication body, applied inside the gateway.
- Camel route policies satisfy ADR 0007: no redelivery anywhere on the POS route.

Still to write: tenant/location ancestry rejection for POS bindings specifically
(the generic case is covered by ADR 0026's suite), and a route-level test that a
dead-lettered create is classified uncertain end to end.

## Rollout and rollback

Implement one fake adapter first, then one POS provider/capability and one
internal location. Bindings remain disabled until connection, mapping, and dry-
run checks pass. Rollback suspends the binding and returns operations to Qoida
manual paths; mappings and evidence remain for reconciliation.

## Consequences

### Positive

- A new POS provider becomes an isolated adapter package plus configuration.
- Capability discovery makes provider gaps visible at binding time rather than
  during a customer's order.
- Credentials never enter Qoida's database, API responses, or logs.

### Negative

- The indirection is real: reading an end-to-end POS flow now requires following
  a port, an adapter, a binding, and a capability snapshot.
- Capability snapshots can go stale when a provider changes behavior silently,
  so reconciliation must be scheduled rather than assumed.
- One provider contract suite must be maintained against an API Qoida does not
  control, and one more per POS added.
- The vendor's missing idempotency key is not something this design fixes. It
  makes the failure visible and settles it with a person, which costs an operator
  a decision per uncertain export, and that queue is real work somebody has to
  own until Clopos answers Q1 or Q2.

### Accepted trade-offs

- Requiring an operator to confirm the provider restaurant identity before
  activating a binding slows onboarding, and prevents orders being exported to
  the wrong restaurant.
- Conflicting mappings stop rather than resolve automatically, which produces
  manual work in exchange for never corrupting a catalog silently.

## Implementation checklist

- [x] Obtain the current API contract and capability matrix for the pilot
      provider ([`clopos-api.md`](../../providers/clopos-api.md)). r_keeper and iiko
      remain unread; neither has an adapter.
- [x] Approve the endpoint allowlist design — one approved Clopos host, seeded
      in `integration.provider_environments`.
- [x] Add the capability ceiling, probe, export, attempt, and candidate tables
      (V0036). Installation, binding, and mapping tables came from ADR 0026.
- [x] Implement provider-neutral capability ports and a fake adapter.
- [x] Implement the first real provider connection and capability check through
      the ADR 0007 route.
- [x] Implement the export path and its uncertain-outcome resolution.
- [x] Add provider contract, capability, uncertainty, and schema tests.
- [ ] Put Q1, Q2, Q18 and Q19 to Clopos in writing.
- [ ] Run the correlation-echo experiment against the pilot brand and record the
      result in `clopos.correlationEchoVerified`.
- [ ] Move the session token cache into ADR 0033 shared runtime state.
- [ ] Add the polling cadence as an ADR 0030 policy value scaled by venue count,
      and consume `RateLimit-Remaining` to back off before a 429.
- [ ] Add metrics, health, and a runbook for the `AWAITING_OPERATOR` queue.
- [ ] Implement `CUSTOMER_UPSERT` once an ADR 0029 consent basis exists for
      exporting a customer's phone and address to a POS.

## Exit criteria

A tenant can install and bind a POS to one location without exposing secrets,
capabilities are verified independently, and a fake plus one real adapter pass
the same provider-neutral contract tests.

**Those three are met. The ADR is not done, and saying so is the point of this
section.**

### Blocked on Clopos

A fourth criterion is implicit in every other line of this document and was never
written down: *an order exported to a POS reaches exactly one kitchen ticket.*
That cannot be met today.

Clopos has no idempotency mechanism, and the recovery read that stands in for one
is a heuristic that cannot distinguish a double export from a customer who
ordered twice. Everything above is built so that the failure is visible and
settled by a person rather than silent — which is the best available answer and
is not the same as the criterion being met. Four questions are outstanding, and
the first three block go-live:

| | Question | What it changes |
|---|---|---|
| **Q1** | How does a caller set `integration_uuid` / `integration_id` on `POST /orders`? They appear on every order *response*, described as coming from the integration source "if provided", and the create request has no field that provides them. Are they unique-constrained? Can `GET /orders` filter on them? | Everything. An affirmative answer turns the operator queue into an automatic resolution with no new code. |
| **Q2** | Is `order_number` a real request field? The prose documents it at twenty characters; the OpenAPI schema omits it. If real: unique per brand or per venue, enforced, filterable — and why is it absent from the `Order` response? | The same, one step weaker. We send it regardless. |
| **Q18** | What is the documented behaviour of a repeated `POST /orders` with an identical body? Two orders, or a dedupe? Is there any dedupe window at all? | Whether the risk is real or merely undocumented. |
| **Q19** | Can a client secret be rotated on a schedule, or is it always a support ticket? Is self-service rotation planned? | Whether ADR 0028's rotation policy can ever apply here. |

**ADR 0011's exit criteria are not genuinely met while a retried export can
produce a second kitchen ticket, and this ADR says so rather than pretending
otherwise.** The pilot may proceed on the strength of the operator queue and the
clerk-approval default; a second restaurant should not, until Q1 or Q2 has an
answer.

Two further questions shape the design without blocking it: **Q6** (is
`GET /products` sortable, which would make the catalog walk stable and retire the
removal quorum) and **Q8** (how is an order cancelled after it reaches
`RECEIVED`, which is the missing half of `ORDER_CANCELLATION`). Both are recorded
in [`clopos-api.md`](../../providers/clopos-api.md) §12.
