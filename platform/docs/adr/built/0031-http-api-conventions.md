# ADR 0031: HTTP API conventions

- Decision status: Accepted
- Implementation status: Built — every convention is implemented and enforced in
  `uz.horecaos.platform.web`: `ErrorCode` (the enum is the authoritative count —
  a hand-written number here went stale twice), `ApiProblem` and
  `GlobalApiErrorHandler` for Problem Details across all surfaces — since wave 17
  the handler extends `ResponseEntityExceptionHandler`, so Spring MVC's own
  binding and content-negotiation failures (a missing request parameter, a
  non-UUID path segment) render ADR 0031 Problem Details instead of the servlet
  container's default error body; the rarer framework exceptions are covered too
  (wave 22): an unmapped route (`ROUTE_NOT_FOUND`, deliberately distinct from
  the entity-level `RESOURCE_NOT_FOUND`), an unacceptable `Accept` header, a
  missing required header, and the two conversion/serialization failures that
  signal a server defect all carry this platform's `code` extension, while
  multipart, method-validation, and `NoHandlerFoundException` stay uncovered
  because nothing in this application can reach them — with
  `TenantApiErrorHandler` reduced to tenancy-only mappings; `IdempotencyService`,
  `IdempotencyInterceptor`, `CachedBodyRequestFilter` and `IdempotencyPurgeJob` over V0006
  (scoped to caller and resource by V0047); `AggregateVersion` for `ETag`/`If-Match` and
  `STALE_VERSION`; signed `Cursor` and `Page`; `ApiMoney`; and `CorrelationIdFilter`.
  `EndpointCapabilityDeclarationTests` fails the build if a mutating endpoint omits a
  capability or an idempotency requirement. That test grew a second legal answer with the
  storefront's move to ownership: `@CustomerOwned` now satisfies the authorization
  declaration, declaring both it and `@RequiresCapability` fails, a declared scope wider
  than the endpoint's own path fails, and the idempotency requirement is read from
  `@Idempotent` as well as from `mutating = true` — so dropping a capability from a
  storefront handler can no longer drop its replay protection silently.
  `OpenApiContractTests` now gets Springdoc's real MVC document, canonicalizes it, checks it
  structurally against the released `api/openapi/v1/horecaos-api.json` baseline, and refuses any
  undocumented drift; `tools/openapi/generate_types.py` produces the checked-in typed
  TypeScript transport contract under `api/generated/`, with CI regenerating and diffing it.
  `OpenApiSurface` now turns this record's surfaces into four additive Springdoc
  groups — `storefront`, `control-plane`, `operations`, `providers` — at
  `/v3/api-docs/<group>`, each with its own checked-in baseline and generated
  TypeScript client behind the same compatibility gate; a path fitting none or more
  than one group fails the build (ADR 0057).
- Date proposed: 2026-08-20
- Date decided: 2026-08-20
- Deciders: Ayubkhon Abbosov (platform architecture)
- Depends on: ADR 0001, ADR 0025
- Supersedes / Superseded by: —
- Open inputs: none

## Context

Sixteen ADRs specify HTTP endpoints. They consistently assume conventions that
no ADR defines: `Idempotency-Key` on mutations appears in ADRs 0008, 0013, 0014,
0016, 0017, 0019, and 0021; "Problem Details" appears in ADR 0008; "expected
version" concurrency control appears in ADRs 0012, 0014, 0016, 0017, and 0021;
"cursor pagination and server filtering" appears in ADRs 0012 and 0022; and ADR
0022 requires generating typed clients from the OpenAPI document and failing CI
on undocumented breaking changes.

The API surface also spans four distinct audiences — `control-plane`,
`platform-admin`, `operations`, `storefront`, and `customer` — with different
authentication, authorization, and stability expectations, and nothing states
what those prefixes mean or which rules apply to each.

Some of this already exists in code: the application is on Springdoc OpenAPI
with a Keycloak bearer scheme, and `CorrelationIdFilter` already propagates a
correlation identifier. This ADR makes the rest explicit before sixteen
capabilities each invent their own answer.

## Decision

### Surfaces and versioning

```text
/api/v1/platform-admin/**   Qoida staff, global scope, least stable
/api/v1/control-plane/**    tenant administration
/api/v1/operations/**       brand and location staff
/api/v1/storefront/**       public and customer-authenticated commerce
/api/v1/customer/**         authenticated customer self-service
```

- The URI carries the major version. A new major version is additive and
  parallel; the old one continues until its published deprecation date.
- Within a major version, changes are additive only: new optional fields, new
  endpoints, new enum values where the client is documented to tolerate unknown
  values. Removing a field, narrowing a type, adding a required request field,
  or changing a status code is breaking and requires a new major version.
- Tenant identity stays in the path for tenant-scoped resources, matched against
  the signed organization claim as ADR 0003 requires. It is never taken from a
  header or a subdomain.

### Errors

All errors are RFC 9457 Problem Details, `application/problem+json`:

```json
{
  "type": "https://docs.horecaos.uz/problems/insufficient-capability",
  "title": "Insufficient capability",
  "status": 403,
  "detail": "Requires order.approve at LOCATION scope.",
  "instance": "/api/v1/operations/orders/018f.../approval-decisions",
  "code": "INSUFFICIENT_CAPABILITY",
  "correlationId": "01J8...",
  "errors": [
    { "field": "lines[0].quantity", "code": "MUST_BE_POSITIVE" }
  ]
}
```

- `code` is a stable machine-readable identifier from a code-owned registry, and
  it, not `title` or `detail`, is what clients branch on.
- `detail` never contains PII, secrets, SQL, stack traces, or internal
  identifiers beyond the ones the caller already supplied.
- A denied authorization names the missing capability and scope, never the
  policy that produced the decision.
- The distinction ADR 0021 requires is preserved: `INSUFFICIENT_CAPABILITY` and
  `ENTITLEMENT_REQUIRED` are different codes with different remediation.

### Idempotency

- Every non-`GET` endpoint that creates a resource or causes an external effect
  requires an `Idempotency-Key` header, tenant-scoped, client-generated, and
  stable for a retry.
- The server stores the key with a hash of the request body, the resolved
  principal, and the completed response. A repeat with the same key and the same
  hash returns the stored response with `Idempotency-Replayed: true`. A repeat
  with a different hash returns `409` and `IDEMPOTENCY_KEY_REUSED`.
- A request that is still in flight for the same key returns `409` with
  `IDEMPOTENCY_KEY_IN_PROGRESS`; it never runs twice concurrently.
- Keys are retained for a published window, at least 24 hours and longer for
  monetary operations.

### Concurrency

- Mutations of versioned aggregates require the expected version, supplied as
  `If-Match` with the aggregate's `ETag` for REST-shaped resources or as an
  explicit `expectedVersion` field for command-shaped endpoints.
- A mismatch returns `409` with `STALE_VERSION` and the current version.
- `GET` responses for versioned aggregates return an `ETag` and honour
  `If-None-Match`.

### Pagination, filtering, sorting

- Cursor pagination only. Offset pagination is not offered, because Operations
  lists change constantly and offsets silently skip and duplicate rows.
- Request: `?cursor=&limit=`; response: `{ "items": [...], "nextCursor": null }`.
- Cursors are opaque, signed, and encode the sort key and filter set, so
  changing filters mid-iteration fails rather than returning incoherent pages.
- `limit` has a documented default and maximum per endpoint.
- Filters are explicit named parameters. There is no generic query language.

### Representation

- JSON only. `snake_case` is not used; field names are `camelCase`.
- Money is always an object: `{ "amountMinor": 125000, "currency": "UZS" }`.
  A bare number is never a money value.
- Instants are RFC 3339 UTC with `Z`. Local times carry an explicit IANA
  timezone field beside them.
- Identifiers are UUIDs as strings. Enum values are `SCREAMING_SNAKE_CASE`.
- Absent and null are distinguished on `PATCH`: absent means unchanged, null
  means clear.
- No unbounded free-form maps in request or response contracts.

### Cross-cutting headers

- `X-Correlation-Id` is accepted and echoed; the server generates one when
  absent and propagates it through logs, traces, outbox, inbox, and Camel.
- W3C `traceparent` is propagated per ADR 0023.
- `Retry-After` accompanies `429` and `503`.

### Documentation and compatibility

- The OpenAPI document is generated from code and is the contract of record.
- CI diffs it against the previous release and fails on an undetected breaking
  change within a major version.
- Typed clients for ADR 0022 are generated from that document; response types
  are never hand-copied.
- Every endpoint declares its required capability from ADR 0025, and a startup
  test fails the build if a mutating endpoint declares none.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| Header or media-type versioning instead of URI versioning | Cleaner in theory and harder to operate: caches, gateways, logs, and support conversations all lose the version, and "which version is this tenant on" stops being visible in a URL | Never for this platform |
| GraphQL for the Operations and control-plane surfaces | Genuinely attractive for dense Operations screens that need many related entities. Rejected because per-field authorization against ADR 0025 scopes, query cost control, idempotent mutations, and OpenAPI-generated typed clients all become bespoke work, and the platform gains a second contract model | A read-heavy Operations surface proves that over-fetching is a measured problem. A narrow read-only GraphQL or a purpose-built projection endpoint could then sit beside REST |
| gRPC between frontend and backend | Better typing and streaming, worse browser and debugging story, and it would abandon the OpenAPI generation that ADR 0022 depends on | Internal service-to-service calls appear after a module extraction |
| Bespoke error envelopes per module | Every client writes per-endpoint error handling, and the frontend cannot map errors to user-facing messages consistently | Never |
| Offset pagination | Simpler to implement and wrong for constantly changing lists: rows are skipped and duplicated during iteration, which in an order feed means a missed order | Never for mutable collections; acceptable for static reference data if ever needed |
| Server-derived idempotency from a request hash | Two legitimately different carts can normalize to the same hash, and a retry with a trivial difference creates a duplicate. ADR 0019 rejected this for the same reason | Never |
| Optimistic concurrency by timestamp | Clock skew and same-millisecond updates make it unsafe. Version columns already exist on every aggregate | Never |

## Consequences

### Positive

- One set of conventions across sixteen ADRs, so client behavior is predictable
  and the generated client is uniform.
- Idempotency and concurrency have one implementation to test rather than one
  per capability.
- Stable error codes give the frontend, support tooling, and audit a shared
  vocabulary.

### Negative

- Requiring `Idempotency-Key` on every effectful mutation adds client burden and
  will be forgotten during integration work, producing avoidable `400`
  responses until the pattern is habitual.
- Idempotency records are another growing table requiring retention and cleanup.
- Cursor pagination cannot answer "page 7 of 92", so Operations screens must be
  designed around continuation rather than page numbers.

### Accepted trade-offs

- Strict additive-only evolution within a major version means some poorly named
  fields will persist until the next major version.
- The signed-cursor rule makes cursors opaque, so support cannot construct one
  by hand for debugging.

## Implementation notes

Delivered: the error code registry, the shared Problem Details handler across
every surface, the idempotency table and service, signed cursor pagination, and
the money representation.

Idempotency is applied over HTTP by an interceptor keyed off
`@RequiresCapability(mutating = true)`, purged on a schedule rather than on the
request path, and the capability-declaration test is in place.

`AggregateVersion` renders a **weak** validator, because two responses at one
version are semantically equivalent without being byte-identical and a
formatting change should not invalidate a caller's cache. A malformed
`If-Match` is rejected rather than ignored: treating an unparseable precondition
as no precondition would silently disable the check it was meant to perform.

The OpenAPI contract is generated through the actual MVC document in a Spring
Boot integration test, rather than from controller annotations copied into a
second model. Its v1 baseline is canonical JSON under `api/openapi/v1/`. CI
first rejects a removed path, operation, response, parameter or compatible
schema shape, then rejects any undocumented document or generated-client diff.
`make openapi-baseline` is the deliberate refresh path; it runs compatibility
before writing either checked-in artifact.

**A bug worth remembering.** The claim originally caught `DuplicateKeyException`
and then queried for the existing record. That works only when each statement
auto-commits. Under a real transaction a constraint violation aborts the
PostgreSQL transaction, so the follow-up query failed with "current transaction
is aborted". The unit tests passed because they constructed the service directly
rather than through a Spring proxy, so `@Transactional` was inert and every
statement auto-committed — the tests were green for a reason that did not hold in
production. The claim now uses `ON CONFLICT DO NOTHING`, matching the inbox
insert in ADR 0005 for the same reason, and the end-to-end test exercises the
real proxied path.

The idempotency service claims in its own transaction, so a claim survives a
rollback of the business transaction it guards; otherwise a failed attempt would
erase the evidence that it happened. A lease bounds the in-progress state, so a
process that dies mid-request does not block its key until retention expires.

## Physical model

```text
platform.idempotency_records
  id, tenant_id null, scope_key, idempotency_key
  request_hash, principal_subject
  status, response_status null, response_body null
  lease_expires_at, first_seen_at, completed_at null, expires_at
  unique(scope_key, idempotency_key, tenant_id)
```

`lease_expires_at` was added during implementation: without it, a process that
crashed between claiming a key and completing the request would block that key
for the whole retention window, turning one crash into a customer who cannot
retry their checkout.

`scope_key` names the operation, so the same client key used against two
different operations does not collide.

## Testing

- A replayed mutation returns the original response and creates no second effect.
- A reused key with a different body is rejected.
- Concurrent identical requests produce one effect and one stored response.
- A stale expected version is rejected with the current version returned.
- Cursor iteration under concurrent inserts and deletes neither skips nor
  duplicates items.
- Every error path returns Problem Details with a registered code.
- A mutating endpoint without a declared capability fails the build.
- The OpenAPI compatibility check fails on a deliberately introduced breaking
  change.

## Implementation checklist

- [x] Add the error code registry and a Problem Details exception handler covering all surfaces (`ErrorCode`, `ApiProblem`, `GlobalApiErrorHandler`).
- [x] Reduce `TenantApiErrorHandler` to the tenancy-only mappings; everything shared moved to the global handler.
- [x] Add idempotency records, the claim/replay/conflict service, the HTTP interceptor, and the scheduled purge (`IdempotencyPurgeJob`).
- [x] Implement `ETag`/`If-Match` handling (`AggregateVersion`) and the shared `STALE_VERSION` conflict response.
- [x] Implement signed cursor pagination helpers (`Cursor`, `Page`).
- [x] Add the money convention (`ApiMoney`). Instant and enum conventions follow the existing Jackson configuration.
- [x] Add the OpenAPI diff gate and typed TypeScript client generation to CI (`OpenApiContractTests`, `api/openapi/v1/`, `tools/openapi/generate_types.py`).
- [x] Add the build-time test asserting every mutating endpoint declares a capability and an idempotency requirement.

## Exit criteria

Every endpoint across all five surfaces returns Problem Details with registered
codes, effectful mutations are idempotent and version-checked by shared
infrastructure rather than per-controller code, list endpoints paginate by
cursor, and CI fails on an undocumented breaking change to the OpenAPI contract.

## References

- [RFC 9457: Problem Details for HTTP APIs](https://www.rfc-editor.org/rfc/rfc9457.html)
- [ADR 0025: Fine-grained authorization and the capability model](../built/0025-fine-grained-authorization-and-capability-model.md)
