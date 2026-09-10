# ADR 0082: A feature flag is a boolean configuration key

- Decision status: Proposed
- Implementation status: Built — `ConfigurationKeys.featureFlags()` and the first flag, `feature.support_visits`; `FeatureFlagController`'s rollout view and per-tenant read, tested against PostgreSQL; the control-plane feature-flag screen; the operations app's flag reader and the settings page it gates
- Date proposed: 2026-09-11
- Date decided: —
- Deciders: proposed by Claude and built on the platform owner's instruction of 2026-09-10 to finish the control plane's remaining waves; Ayubkhon Abbosov (platform owner) decides
- Depends on: ADR 0025, ADR 0027, ADR 0030
- Supersedes / Superseded by: —
- Open inputs: whether percentage rollout is wanted before tenants number in the hundreds — owner Ayubkhon Abbosov. Nothing built here depends on the answer

## Context

The information architecture (8.1) asks for flags with per-tenant rollout,
and says HorecaOS "builds once and rolls out with flags" instead of running
two frontends side by side. Nothing existed.

ADR 0030 already gives the platform everything a flag is made of: keys
declared in code, typed values stored per scope, resolution from platform to
tenant to brand to location, optimistic versions, an audit fact for every
write, and a console screen for setting values. Its own rule — reuse the
shared mechanism rather than build a module-local one — applies.

## Decision

A feature flag is a boolean configuration key whose code starts with
`feature.`, off by default, settable at the platform and per tenant.

1. Turning a flag on for everyone is setting its platform value; for chosen
   tenants, setting their tenant value; holding a tenant back from a flag that
   is on for everyone, setting its tenant value to false.
2. Handing a tenant back to the platform value is the existing explicit-null
   write, which ADR 0030 already defines as "continue resolution".
3. Every change is the ordinary configuration write: a reason, an expected
   version, an audit fact. There is no second write path.
4. Two reads exist for flags alone: the rollout view (each flag, its platform
   value and every tenant set apart from it) and one tenant's resolved answers.
5. A flag is declared when something reads it. The first is
   `feature.support_visits`, which gates the tenant-facing half of ADR 0081.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| A separate flags table and service | A second copy of scopes, versions, audit and a console for them | Flags need something configuration cannot hold, such as a schedule |
| Percentage rollout by hashed tenant id | At pilot scale a named list is clearer and auditable; a percentage says nothing about which restaurant | Tenants number in the hundreds and rollouts are routine |
| A hosted flag service | A runtime dependency and a data processor for a handful of booleans | Never at this scale |
| Flags per brand or location | No feature needs it yet, and ADR 0030 already allows it by adding scopes to the key | A flag whose rollout is by branch |

## Consequences

### Positive

- One mechanism: a flag is audited, versioned and cached like any setting.
- The rollout screen and the settings screen can never disagree about a
  value, because they read the same rows.

### Negative

- The operations app reads flags with a tenant-wide read, so a person whose
  only grant is at a location sees every flag as off. Right for the one flag
  today, whose page is for administrators; a location-level feature will need
  a location-scoped read.
- A flag left on for everyone is a dead branch in code until someone removes
  both the flag and the old path; nothing forces that.

### Accepted trade-offs

- The configuration namespace now carries flags beside settings. The prefix
  and a test that every `feature.` key is a boolean, off by default, settable
  only at the platform and per tenant keep the two apart.

## Specification

- `ConfigurationKeys.featureFlags()`: every `feature.` boolean key, sorted.
- `GET /control-plane/feature-flags` (platform admin): code, description,
  default, platform value and version, and every tenant row with name, value
  (null = follows the platform) and version.
- `GET /operations/tenants/{t}/feature-flags` (`tenant.read`): code → resolved
  boolean.
- Writes: `POST /control-plane/configuration/keys/{code}/values` at `PLATFORM`
  or `TENANT` scope, `explicitNull` to hand a tenant back.

## Rollout and rollback

Additive. A flag is removed by deleting its declaration together with its
stored rows in one migration, as V0194 did for dead settings.

## Implementation checklist

- [x] Flag declaration rules, registry, first flag
- [x] Rollout view and tenant read, tested
- [x] Console rollout screen; operations flag reader and a gated page

## Exit criteria

An operator turns `feature.support_visits` on for one tenant from the
console; that tenant's administrators see the support-visits page and no
other tenant's do; handing the tenant back hides it again.

## References

- `docs/frontend-information-architecture.md` §8.1
