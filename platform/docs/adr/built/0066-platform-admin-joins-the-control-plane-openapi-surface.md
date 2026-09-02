# ADR 0066: `platform-admin` joins the control-plane OpenAPI surface

- Decision status: Accepted
- Implementation status: Built — `OpenApiSurface.CONTROL_PLANE` now claims
  `/api/v1/platform-admin/**` alongside `/api/v1/control-plane/**`;
  `OpenApiSurface.OPERATIONS` no longer lists it. `api/README.md`'s table is
  updated. `OpenApiContractTests#everyPublishedPathBelongsToExactlyOneSurfaceGroup`
  re-passes against the running server with no test change, because it asserts
  partitioning, not which group owns which prefix. `make openapi-baseline`
  regenerates all five baselines/clients with the migration (ADR 0024) and
  commercial-admin (ADR 0021) endpoints moving from the `operations` client to
  the `control-plane` one; `frontend/operations` was grepped first and holds no
  reference to any moved path or operation id, so the move is safe against the
  one frontend that could have broken.
- Date proposed: 2026-09-02
- Date decided: 2026-09-02
- Deciders: Claude (wave 28, building the control-plane app to its P tier)
- Depends on: 0031, 0057
- Supersedes / Superseded by: — (resolves the `Open inputs` item ADR 0057 left
  open, without rewriting ADR 0057's own decision or argument)
- Open inputs: none. `courier` remains folded into `operations` by
  elimination, unresolved and untouched — no courier frontend exists yet, and
  this record does not speak for it.

## Context

ADR 0057 split the OpenAPI contract into a full document plus four per-surface
groups, and named its own gap in `Open inputs`: `/api/v1/platform-admin/**`
had "no dedicated frontend" and folded into `operations` "by elimination, not
by a considered decision" — explicitly revisit-worthy "once a frontend
consumes either." `MigrationProgramController`'s own class comment makes the
audience claim independently and earlier than ADR 0057 does: those paths are
"reserved... for HorecaOS staff at global scope," never a tenant's own
operations staff, and every declared scope under them is `PLATFORM`. Wave 28
builds exactly that frontend — `frontend/control-plane`, to its full P tier —
and two of this wave's P-tier screens need endpoints that live only under
`/api/v1/platform-admin/**` today: 9.1 Migration runs
(`MigrationProgramController` and siblings, ADR 0024) and part of 5.3
Entitlements (`CommercialAdminController`'s subscription/override/usage
writes, ADR 0021 — the paired control-plane-surface `CommercialControlPlaneController`
already exposes the reads). Building either screen against the wrong group's
generated client would mean regenerating `operations`'s client for a
control-plane screen's own use, which is exactly the confusion ADR 0057's
per-surface split exists to prevent.

Two things kept this from mattering earlier. First, `frontend/control-plane`
does not consume the generated TypeScript clients under `api/generated/` at
all — every screen calls `ApiClient` with a hand-written path and hand-written
DTOs, the same convention `IntegrationsApi` already uses for a path
(`payment.merchant-binding`) that itself crosses a surface-group line, flagged
in a code comment rather than fixed. So this move changes nothing about what
the frontend *can* call at runtime; the group is a documentation and
drift-detection boundary, not a CORS or security gate — `SecurityConfiguration`
never references `OpenApiSurface`. Second, `frontend/control-plane` already
calls `GET /api/v1/session/context`, which sits under `operations`
(`/api/v1/session/**`) for the same reason `platform-admin` did: it has no
surface of its own and folds into the catch-all. Crossing a group line at
runtime is therefore an established, working pattern here already — this
record does not invent that risk, it removes one specific, well-justified
instance of it for the two screens that need it most.

## Decision

Move `/api/v1/platform-admin/**` from `OpenApiSurface.OPERATIONS` to
`OpenApiSurface.CONTROL_PLANE`. `CONTROL_PLANE`'s path patterns become
`/api/v1/control-plane/**` and `/api/v1/platform-admin/**`; `OPERATIONS` drops
the pattern and keeps `/api/v1/tenants/**`, `/api/v1/session/**`,
`/api/v1/operations/**`, and `/api/v1/courier/**`. Every controller under
`/api/v1/platform-admin/**` — migration (`MigrationProgramController`,
`MigrationScopeController`, `MigrationRunController`,
`MigrationQuarantineController`, `MigrationOwnershipController`) and
commercial admin (`CommercialAdminController`) — moves with the prefix; none
of their `@RequestMapping` paths, capabilities, or behavior changes. `courier`
stays exactly where ADR 0057 left it: this record answers the half of that
ADR's open question a real consumer now exists for, and leaves the other half
open for whoever builds a courier frontend.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| Leave `platform-admin` in `operations`; have `frontend/control-plane` call it anyway | Already technically possible (the group has no runtime effect) and was this wave's first instinct, but it leaves `api/README.md` and `OpenApiSurface`'s own javadoc actively wrong about who consumes these paths, and the next reader has no way to tell "nobody built the frontend yet" from "somebody decided against it" | Never — the whole point of ADR 0057 naming the gap was to close it once a consumer existed |
| A fifth `platform-admin` group, split from `control-plane` | Keeps the API-contract boundary matching a possible future org boundary (a platform-ops team distinct from whoever owns tenant lifecycle), but there is one frontend and one team building it today, and a group with one real consumer sharing its client with a second empty group is upkeep ADR 0057's own "Accepted trade-offs" already warns against (`providers` ships a client nobody imports) | A second platform-facing frontend is proposed, or platform-admin and tenant-lifecycle ownership genuinely splits |
| Also move `courier` to a group of its own now | No courier frontend exists in this wave's scope, and this record's evidence (a real consumer, a grep proving safety) does not exist for that prefix | A courier frontend is proposed |

## Consequences

### Positive

- `api/README.md` and `OpenApiSurface`'s javadoc describe the actual audience
  of every path again, closing the gap ADR 0057 named and left open.
- `frontend/control-plane` gains 9.1 Migration runs and the commercial-admin
  half of 5.3 Entitlements without a hand-copied DTO drifting from a client
  document that was never meant to describe them.
- The membership test still proves the four groups partition the full
  document; nothing about that guarantee weakens.

### Negative

- `api/generated/horecaos-api-v1.operations.ts` loses the migration and
  commercial-admin operation ids and types it carried before. Nothing in
  `frontend/operations` referenced them (confirmed by a repository-wide grep
  before this change), so this is a paper loss today, but it is a real
  breaking change to that generated client's shape for any future consumer
  that assumed otherwise.
- `api/generated/horecaos-api-v1.control-plane.ts` grows by roughly thirty
  operations in one baseline refresh, which is a large diff to review in one
  commit even though every path's own contract is unchanged.

### Accepted trade-offs

`courier` stays an unresolved catch-all member of `operations`, the same
"elimination, not decision" state `platform-admin` was in before this record —
named again rather than fixed, because fixing it needs a consumer this wave
does not build.

## Specification

`src/main/java/uz/horecaos/platform/configuration/OpenApiSurface.java`:
`CONTROL_PLANE`'s `pathPatterns` gain `/api/v1/platform-admin/**`;
`OPERATIONS`'s drop it. `api/README.md`'s per-surface table and prose updated
to match. No controller, capability, DTO, or migration changes; this is a
pure reclassification of which generated document and baseline a
already-existing, already-correct set of endpoints appears in.

## Rollout and rollback

`make openapi-baseline` regenerates all five baselines and clients in one
commit; `make openapi-client-check` (CI) fails on any undocumented drift
afterward, same as any other additive API change. Rollback is reverting the
two `pathPatterns` lists and re-running `make openapi-baseline` to restore the
prior baselines — additive in both directions, since no path, capability, or
behavior moves, only which generated document names it.

## Implementation checklist

- [x] `OpenApiSurface.CONTROL_PLANE` gains `/api/v1/platform-admin/**`
- [x] `OpenApiSurface.OPERATIONS` drops it
- [x] `api/README.md` table and prose updated
- [x] Grep `frontend/operations` for every moved path/operation id — zero hits
- [x] `make openapi-baseline` — five baselines/clients regenerated
- [x] `make openapi-client-check` — zero drift after baseline refresh
- [x] `OpenApiContractTests` green with no test-code change

## Exit criteria

`GET /v3/api-docs/control-plane` includes the migration and commercial-admin
paths; `GET /v3/api-docs/operations` does not; `everyPublishedPathBelongsToExactlyOneSurfaceGroup`
passes; `make openapi-client-check` reports zero drift.

## References

- ADR 0031 — HTTP API conventions
- ADR 0057 — OpenAPI per-surface document groups (leaves this record's
  question open)
- ADR 0024 — Legacy data migration, cutover, and retirement
- ADR 0021 — SaaS plans, entitlements, and usage metering
