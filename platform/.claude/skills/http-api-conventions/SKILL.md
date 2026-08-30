---
name: http-api-conventions
description: Use whenever creating or modifying an external-facing HTTP endpoint, controller, request or response DTO, or error response in Qoida Platform. Encodes ADR 0031 and the capability model from ADR 0025.
---

# HTTP API conventions

Per ADR 0031, and ADR 0025 for authorization. APIs are adapters over shared application
use cases — organise by business capability, not by API audience. Customer, vendor,
courier, and operations endpoints call the same services.

## Every endpoint

- **Errors are Problem Details** (RFC 9457) with a **stable machine-readable code**.
  Clients branch on the code, so it is part of the contract: never repurpose one.
- **Every mutating endpoint declares a capability** (ADR 0025). Not a role, not
  organization membership — a capability. Reads are scoped too.
- **Tenant context comes from the signed token**, matched to the tenant record. Never
  from a header or parameter.
- Money in a payload is an **object**: integer minor units plus ISO currency code.
- Instants are UTC ISO-8601.

## Effectful mutations

- Accept **`Idempotency-Key`**. Replaying a key returns the original result rather than
  performing the effect twice. Idempotency is scoped to the caller and the resource — see
  V0047, which had to correct exactly that.
- Aggregate mutations carry an **expected version** and reject a stale one. Aggregates
  that change concurrently use optimistic locking.

## Collections

Cursor pagination. No offset pagination — it skips and repeats rows under concurrent
writes, which is precisely when it is used.

## Contracts

- The OpenAPI contract is versioned and generates typed frontend clients. Never hand-copy
  a DTO between applications.
- Provider DTOs and SDK types stay inside their adapter and are mapped to canonical domain
  commands at the boundary. A controller never sees a provider type.
- Domain code imports no `org.springframework.web` or `org.springframework.http`.

## Before saying it is done

- [ ] Capability declared and enforced, with a test proving denial
- [ ] Cross-tenant access denied, with a negative test
- [ ] Problem Details code registered and stable
- [ ] `Idempotency-Key` honoured on effectful mutations, with a replay test
- [ ] Expected-version conflict returns the documented code
- [ ] OpenAPI contract regenerated
- [ ] Loading, empty, error, expired-session, forbidden, and degraded states exist for any
      frontend journey consuming it
