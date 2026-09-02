# ADR 0057: OpenAPI per-surface document groups

- Decision status: Accepted
- Implementation status: Built — `OpenApiSurface` (four groups), the four Springdoc
  `GroupedOpenApi` beans in `OpenApiConfiguration`, the per-group snapshot/compatibility
  tests and the `everyPublishedPathBelongsToExactlyOneSurfaceGroup` membership test in
  `OpenApiContractTests`, five checked-in baselines under `api/openapi/v1/`, five
  generated TypeScript clients under `api/generated/`, `make openapi-baseline` /
  `make openapi-client-check` coverage of all five, and the CI workflow's per-group
  drift loop all exist and pass `make verify`. What does not exist: any frontend
  importing a generated client (tracked by ADR 0035).
- Date proposed: 2026-08-30
- Date decided: 2026-08-30
- Deciders: platform owner (requested the split), Claude (grouping design)
- Depends on: 0031, 0040
- Supersedes / Superseded by: —
- Open inputs: whether the courier's own self-service endpoints (still folded into
  `operations` by elimination, not by a considered decision) deserve their own group once
  a frontend consumes them. Owner: platform owner; revisit when `frontend/` gains a
  courier app distinct from `mobile/`. The `platform-admin` half of this question is
  resolved — ADR 0066 moved it to `control-plane` once wave 28 built that frontend.

## Context

ADR 0031 names five URI-prefix surfaces and says they have different authentication,
authorization, and stability expectations, but the OpenAPI document, baseline, and
generated client have always been one document covering every surface. ADR 0040 adds a
sixth surface, `/api/v1/partner/**`. ADR 0035 wants each frontend to generate its own
typed client, but a client generated from the whole document forces every frontend to
depend on endpoints it never calls, and a breaking-change gate over the whole document
blocks one frontend's release for another surface's break. No frontend has adopted a
generated client yet.

## Decision

Springdoc publishes four additional, additive `GroupedOpenApi` documents alongside the
unchanged full v1 document, one per `OpenApiSurface`:

```text
storefront      /api/v1/storefront/**
control-plane   /api/v1/control-plane/**
providers       /providers/**, /api/v1/partner/**
operations      /api/v1/tenants/**, /api/v1/session/**, /api/v1/operations/**,
                /api/v1/courier/**, /api/v1/platform-admin/**
```

Each is served at `/v3/api-docs/<group>`, has its own checked-in baseline and generated
TypeScript client, and goes through the same compatibility gate and
`make openapi-baseline` flow as the full document, which is unchanged in path, filename,
and behavior.

`operations` absorbs `platform-admin` and `courier` by elimination: neither has a
consuming frontend today. `providers` groups payment-provider callbacks with the
partner/marketplace inbound surface because both are externally-initiated,
non-tenant-staff, non-customer traffic, even though no frontend generates a client
from it.

`OpenApiContractTests#everyPublishedPathBelongsToExactlyOneSurfaceGroup` fetches the
full document and all four group documents from the running server and asserts their
paths partition the full set exactly.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| Match ADR 0031's five surfaces one-to-one (plus partner as a sixth) | `platform-admin` and `courier` have no frontend today; a group with no consumer is upkeep with no payoff | A platform-admin or courier frontend is proposed |
| A catch-all `pathsToExclude` group for `operations` | Silently absorbs any future uncategorised prefix, defeating the membership test | Never |
| Publish only per-group documents, retire the full one | Breaks every consumer pinned to the full document | Never, absent a documented migration |
| Wait for a frontend to adopt a generated client before splitting | The split is contract-neutral and cheap now; a boundary mistake now costs a JSON diff, not a frontend migration | — |

## Consequences

### Positive

- A frontend pins and reviews only its own surface's baseline and client.
- The membership test turns "which group" into a build failure if skipped.
- The full document, baseline, and client are provably unchanged (byte-identical at
  introduction).

### Negative

- Five baselines and five clients instead of one to keep in review.
- `operations` is large (151 of 281 paths) and heterogeneous — the catch-all for
  everything that is not storefront, control-plane, or providers.
- `platform-admin` and `courier` are grouped by elimination, not by a reviewed audience
  decision; this record names that gap in its Open inputs without resolving it.

### Accepted trade-offs

`providers` ships a client no frontend will ever import, for uniform treatment of every
externally-initiated surface.

## Specification

See `api/README.md`'s "Per-surface groups" section and `OpenApiSurface`.

## Rollout and rollback

Additive; rollback is deleting the four beans, the file pairs, and the test methods.
Nothing downstream depends on the group endpoints yet.

## Implementation checklist

- [x] `OpenApiSurface` enum
- [x] Four `GroupedOpenApi` beans
- [x] Per-group baseline/compatibility tests
- [x] Membership test
- [x] Five baselines, five clients
- [x] Makefile coverage
- [x] CI extended to the four groups
- [ ] A frontend imports a generated client (tracked by ADR 0035)

## Exit criteria

`make openapi-baseline` produces five baselines/clients with zero drift on immediate
re-run; the membership test passes; `make verify` is green.

## References

- ADR 0031 — HTTP API conventions
- ADR 0040 — Marketplace channel and partner API
- ADR 0035 — Angular frontend platform and design system
