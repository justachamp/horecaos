# ADR 0103: A branch manager's own scope should reach `iam.grant.manage`

- Decision status: Proposed
- Implementation status: Not started — this record raises the question row
  `9.1` of the operations gap map names; it changes nothing in `PlatformRole`
  or `GrantController` on its own. Wave P30 (`wave131-p30`) built the
  console-side consequence of the limit this ADR asks to revisit — the rail
  and the route guard correctly hide Staff from `location-manager` and
  `brand-manager`, because the server genuinely refuses them today — and
  recorded the limit here rather than working around it in the frontend.
- Date proposed: 2026-09-11
- Date decided: —
- Deciders: proposed by Claude and built on the platform owner's instruction
  of 2026-09-11; Ayubkhon Abbosov (platform owner) decides
- Depends on: ADR 0025
- Supersedes / Superseded by: —
- Open inputs: whether `iam.grant.manage` should be held at `LOCATION`/`BRAND`
  scope by `location-manager`/`brand-manager` at all, and if so, whether it is
  the same capability narrowed by `GrantManagementService.requireGrantable`'s
  existing "cannot confer more than you hold" rule or a new, deliberately
  weaker capability — both are product and security decisions the platform
  owner has not made (platform owner); whether narrowing this way reopens
  ADR 0025's closed "no tenant-defined roles in v1" input, which a narrower
  capability might not need to (security / platform owner)

## Context

`docs/operations-spec/staff-and-access.md` §0 and the console's own IA row
(`frontend-information-architecture.md` §9.1) both specify the same scene:
"the Chilonzor manager sees Chilonzor's team" — a branch manager opens Staff,
sees the people working at her own branch, and can hand one of them a job.

That scene is unreachable today, and not by an accident this wave introduced.
Every grant-management endpoint on `GrantController`
(`GET/POST .../grants`, `GET .../roles`) declares
`@RequiresCapability(Capability.IAM_GRANT_MANAGE)` with no scope override,
which resolves to `IAM_GRANT_MANAGE` at `TENANT` scope. `PlatformRole` grants
that capability to exactly two of its eight tenant-visible bundles —
`tenant-owner` and `tenant-admin`, both `TENANT`-scoped — and to none of the
other six, `location-manager` and `brand-manager` included. A location
manager holding a real `LOCATION`-scope grant over everything else in her
branch (`ORDER_APPROVE`, `KITCHEN_TICKET_ADVANCE`, `OFFERING_MANAGE`, and
twenty-odd more) cannot open the one screen that would let her onboard the
line cook she hired this morning. She has to ask whoever holds
`tenant-owner`/`tenant-admin` to do it for her, every time, for every hire,
at every branch.

Wave P30 built exactly the frontend row `9.1c`/`9.1d` ask for — permission-
gated navigation and a legible refusal — and in doing so made this limit
*visible* rather than *fixing* it: the rail now correctly omits Staff for a
`location-manager` fixture, and a direct `/staff` URL is correctly refused,
because both of those are the truth about what the server allows. The gap
map's own instruction for this row is explicit: "raise, do not work around,
the ADR 0025 scope limit that keeps `IAM_GRANT_MANAGE` at `TENANT`." This
record is that raise.

ADR 0025 itself is not silent on the shape of the eventual answer. Its own
closed input reads "no tenant-defined roles in v1" — a decision about
whether a *tenant* may invent a role, which this question is adjacent to but
not identical with: nothing here proposes letting a branch manager compose a
new bundle of capabilities. It proposes letting an *existing*, code-owned
capability (`IAM_GRANT_MANAGE`, or a narrower sibling) be held at a narrower
scope than it is today by an *existing* bundle (`location-manager`). ADR
0025's own scope model already supports this mechanically —
`ResourceScope`'s four levels and `JdbcAuthorizationService.hasGrant`'s
downward-only `covers` place no floor under which capability may exist at
`LOCATION` scope — the only change needed is which `Capability` values
`PlatformRole.LOCATION_MANAGER`'s `EnumSet` names, which is a one-line,
one-release change once the two open inputs below are answered. Nothing about
this proposal requires reopening how `iam.roles`, `iam.grants`, or the
registry snapshot are stored (V0008), and no migration is implied.

## Decision

Not decided. This ADR names the two questions that block deciding it, so the
"raise it" instruction in the gap map has a record to attach to rather than a
comment in a wave report that nobody outside this session reads again.

**Question 1 — same capability, narrower scope, or a new one?** Granting
`location-manager` (and, symmetrically, `brand-manager`) `IAM_GRANT_MANAGE`
at their own `LOCATION`/`BRAND` scope is the smallest change: no new
capability, and `GrantManagementService.requireGrantable`'s existing rule —
a granter may only confer a role whose capability set is a subset of what
they themselves hold, at a scope they themselves cover — already stops a
location manager from granting `tenant-owner` or reaching outside her own
branch. Under the current rule, though, holding `IAM_GRANT_MANAGE` at all is
also what lets a principal pass the platform-admin bootstrap bypass's
adjacent check in `JdbcAuthorizationService.has` for *any* scope they can
name, so a location manager who holds it needs `ResourceScopeVerifier` to be
exactly right about denying her a sibling branch — a review this ADR has not
done. The alternative is a new, narrower capability (`staff.grant.manage`
appears in nobody's sentence yet) that `LOCATION_MANAGER` alone would carry,
leaving `IAM_GRANT_MANAGE` itself as a signal that always means tenant-wide
administration. That is a cleaner boundary and a slower one: a new
capability is "a decision that someone must make deliberately" per ADR
0025's own accepted negative consequence, plus a registry entry, a role-
bundle test update, and a capability sentence in three locales
(`capability-sentences.ts`) before it ships.

**Question 2 — which jobs may a branch-scoped granter confer at all?**
`staff-and-access.md` §0's "granter can only give away what they hold"
already answers *whether* a location manager could over-grant — she
cannot, because her own bundle is a strict subset of what `tenant-owner`
holds. It does not answer *which* of the eight tenant-visible jobs make
sense to hand out from a branch at all: `location-staff` obviously does;
`tenant-finance` obviously should never be grantable from a branch scope
regardless of the subset rule, because a `TENANT`-scoped role has no
`LOCATION`-scoped meaning to confer downward in the first place
(`ResourceScope.covers` already refuses a `LOCATION` grant naming a
`TENANT`-scope role for exactly this reason — `scopeChain` never produces
the pair). So in practice this question narrows itself to "does a location
manager get to hand out `location-staff`, and does a brand manager get to
hand out `location-manager` and `location-staff`" — genuinely small, but it
is still the platform owner's product decision, not an inference from the
subset rule alone.

## Alternatives considered

| Option | Why not chosen (yet) | Revisit when |
|---|---|---|
| Leave `IAM_GRANT_MANAGE` `TENANT`-only, permanently | This is the status quo the gap map already found: a branch manager cannot onboard her own hire without escalating to the owner every time, for a tenant that may run a dozen branches. Fine for a single-location pilot tenant; wrong for the multi-location tier the spec's own worked example ("Chilonzor's team") describes | A second pilot tenant with more than one staffed branch reaches production |
| Widen `IAM_GRANT_MANAGE` itself to `LOCATION_MANAGER`/`BRAND_MANAGER` (Question 1, option A) | Fastest to ship — one `PlatformRole` line — but conflates "administers this branch's staff" with whatever else a future capability check keys `IAM_GRANT_MANAGE` on, and has not been checked against the platform-admin bootstrap bypass's own scope-naming path | The subset-and-scope review below is done and finds no widening the boundary was not meant to have |
| A new, narrower capability for branch-scoped staff management (Question 1, option B) | Correct-shaped, but a new capability is real work — registry entry, role-bundle test, three-locale sentence, `PlatformRoleTests` coverage — that the platform owner has not asked for yet and this wave's size (1d) did not budget | The platform owner decides Question 1 in this option's favor |
| Reopen ADR 0025's "no tenant-defined roles" input and let a tenant define its own branch-manager-can-grant policy | Solves this and a wider class of problem at once, but is a materially bigger decision — a policy authoring surface, its own capability, its own audit story — than "let an existing bundle confer an existing job," and ADR 0025 already named it a deliberately closed input for v1 | The platform owner wants tenant-composed roles for a reason broader than this one gap |

## Consequences

### Positive

- Names the exact two questions that block the fix, so the next person who
  reads row `9.1` does not have to re-derive "which capability, and which
  jobs" from `PlatformRole.java` and `GrantManagementService` from scratch.
- Keeps the frontend honest in the meantime: P30's rail and guard reflect
  the real server boundary rather than a client-side workaround that would
  have shown a screen the API refuses, or silently granted more than the
  server actually allows.

### Negative

- The scene the spec was written against — "the Chilonzor manager sees
  Chilonzor's team" — stays unreachable until this is decided, on every
  tenant with more than one staffed branch, for as long as this stays
  Proposed.
- Every new hire at a branch still requires the tenant owner or admin to act,
  which does not scale past a handful of branches without becoming their
  full-time job.

### Accepted trade-offs

- This record deliberately does not pick an answer. Naming the two questions
  without deciding them is worth less than a decision, but more than the
  status quo, where the limit was undocumented and this wave's report would
  have been the only place it was written down.

## Specification

Not applicable until Decision status moves to Accepted — see Question 1 and
Question 2 above for the shape either answer would take. No migration: the
change, whichever option Question 1 settles on, is either a `PlatformRole`
`EnumSet` edit (existing capability) or a new `Capability` enum constant plus
the same edit (new capability); `iam.roles`/`iam.role_capabilities` are
already populated from code at startup by `RoleRegistrySynchronizer`; the
registry snapshot changes and needs no `V0NNN` migration.

## Rollout and rollback

Not applicable until decided.

## Implementation checklist

- [ ] Platform owner answers Question 1 (same capability at narrower scope,
      or a new one) and Question 2 (which jobs a branch-scoped granter may
      confer).
- [ ] `ResourceScopeVerifier` and `JdbcAuthorizationService.has`'s bootstrap-
      bypass path are reviewed against whichever capability
      `location-manager`/`brand-manager` would hold, so a location-scoped
      grant of it cannot be walked into a tenant-wide one.
- [ ] `PlatformRole.LOCATION_MANAGER`/`BRAND_MANAGER` gain the chosen
      capability; `PlatformRoleTests`'s orphan-capability check and its
      subset invariants are re-run.
- [ ] If Question 1 chose a new capability: a `Capability` registry entry, a
      `capability-sentences.ts` entry in `ru`/`uz`/`en`
      (`capability-sentences.spec.ts` already asserts every tenant-visible
      capability has one), and `EndpointCapabilityDeclarationTests` coverage.
- [ ] `frontend/operations/src/app/shell/navigation.ts`'s `/staff` entry and
      `capability.guard.ts` are updated if the capability name changes; the
      `q-denied-state` sentence `staff-roles-page.ts` already renders needs
      no change either way, since it names whatever capability the server
      actually refused.

## Exit criteria

A `location-manager` fixture can open `/staff`, sees her own branch's
people, and can grant `location-staff` to a new hire through
`GrantManagementService`'s ordinary audited path — proven by a Java test
naming the exact scope and capability this ADR leaves open, and by the
console's `9.1c` route guard admitting her without the redirect P30 built.

## References

- [ADR 0025: Fine-grained authorization and the capability model](../built/0025-fine-grained-authorization-and-capability-model.md)
- [docs/operations-spec/staff-and-access.md](../../operations-spec/staff-and-access.md) §0, §5
- [docs/operations-gap-map.md](../../operations-gap-map.md), row `9.1`
