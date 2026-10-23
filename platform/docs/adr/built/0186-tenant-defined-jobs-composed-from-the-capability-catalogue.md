# ADR 0186: Tenant-defined jobs, composed from the capability catalogue

- Decision status: Accepted
- Implementation status: Built — an owner defines, copies, edits and closes the company's own
  jobs from the console and gives them away, and the grants a job confers are exactly the
  capabilities it carries. V0692 adds the author, copy-source and closing columns to `iam.roles`;
  V0693 gives `iam.roles` and `iam.role_capabilities` row-level security (a tenant reads the
  platform's jobs and its own and writes only its own; `RoleRegistrySynchronizer` writes the
  platform's under the exempt role and prunes a capability code the build no longer declares from
  tenant jobs). `TenantRoleService` and `TenantRoleController` (`POST`, `PUT .../{code}` under
  `If-Match`, `DELETE .../{code}` on `/api/v1/control-plane/tenants/{tenantId}/roles`, and
  `GET .../capabilities`) enforce `iam.role.manage` (the owner and the administrator), the author
  holding every capability she writes, the deny-list derived in `TenantJobRules`, the twenty-job
  ceiling and unique names, with `RoleChanged` audited as `iam.role.created|changed|retired`.
  `GrantManagementService` lists the company's jobs with `grantable` read back from the grant rule
  (one grants read per list) and reads a tenant job's own level in `requireGrantable`; every reader
  of a grant's role binds the tenant (`IamRolesRowLevelSecurityTests` runs each as the application
  role, bound and unbound). The session context names a job the company defined. In the console,
  `/staff/roles/grid` (lazy) is «Таблица прав», with the job editor; the library marks «Своя».
  What is not done: no tenant has defined a job in production; `iam.grants` has no row-level
  security yet; a tenant job has no description and no translations; and nobody has driven the
  branch console's invite dialog with a custom job and a real branch manager's token (the HTTP
  tests cover the `grant-roles` route she reads).
- Date proposed: 2026-10-14
- Date decided: 2026-10-14
- Deciders: Ayubkhon Abbosov (platform owner)
- Depends on: ADR 0025, ADR 0027, ADR 0031, ADR 0032, ADR 0033, ADR 0056, ADR 0103
- Supersedes / Superseded by: — (reverses one closed input of ADR 0025, «no tenant-defined
  roles in v1», without editing that record: ADR 0025 carries a dated status addition that
  points here)
- Open inputs:
  - **Whether a tenant job may ever carry `iam.grant.manage`** (platform owner, security).
    Default taken: never. The deny-list below holds every `iam.*` capability back from a
    tenant job, so the power to give jobs away stays with the code-owned `tenant-owner`,
    `tenant-admin`, `brand-manager` and `location-manager`. Revisit when a tenant with
    several branches asks for a deputy who is neither a manager nor an administrator.
  - **The ceiling of twenty active jobs per tenant** (platform owner). A number chosen so
    the grid stays readable on a laptop, not a measured limit.
  - **Translated job names** (product owner). Default taken: one free-text name, written in
    whatever language the author writes in. A tenant that works in three languages types
    three names or picks one.

**Decision record, 2026-10-14.** Accepted by Ayubkhon Abbosov (platform owner) with the instruction "accept all defaults, start batch 21" (batch 21); each decision below is taken at the value the owner chose or, where the owner said nothing else, at the smallest default the batch-21 plan proposed.

## Context

ADR 0025 closed an input on 2026-08-20: «no tenant-defined roles in v1». Its body kept the
door ajar in one sentence — «Tenants may later define custom roles from the same
capability catalogue; they can never invent a capability» — and its Accepted trade-offs say
the same: «Tenants get flexibility in composing roles, not in inventing permissions».
Row `9.1b` of the operations gap map is that «later»: an owner who wants a «Старший кассир»
who may refund but not change prices cannot say so. She can read what the eight code-owned
jobs carry and pick the nearest one, and the nearest one is always either too much or too
little. `docs/operations-spec/staff-and-access.md` §5 predicted the shape — the
permission grid «earns its place exactly once — when tenant-defined jobs ship» — and §11.4
records that more of the plumbing exists than a creation path suggests.

What is already true in the tree, verified on 2026-10-14:

- `iam.roles` carries `tenant_id`, `is_platform_defined`, `ck_role_ownership` and
  `uq_role_tenant_code` (V0008). `iam.role_capabilities` holds a role's capability codes.
- `GrantManagementService.resolveRole` already falls back to a tenant-scoped lookup for a
  code that names no platform role, and reads the capability set from `iam.role_capabilities`.
  V0086 and V0089 closed the hole that would have let a grant in one tenant name another
  tenant's private role (`fk_grant_role` is composite on the derived owner).
- `JdbcAuthorizationService.SELECT_GRANTS` joins `iam.role_capabilities` and filters
  `r.status = 'ACTIVE'`, so a tenant row would authorise correctly the day it exists.
- `RoleRegistrySynchronizer` writes platform rows only and never lists `tenant_id`.

What does not exist is every creation path, the row-level security ADR 0056 says
`iam.roles` still needs (its text: «`tenant_id = :guc OR tenant_id IS NULL` ... not yet
built»), and any screen. ADR 0103 named this decision as the broader answer to its own
open question and left it closed.

## Decision

A tenant may define its own jobs. A job is a **named subset of the capability catalogue**,
and the platform never lets it be more than that.

1. **Who authors.** One new capability, `iam.role.manage`, declared at `TENANT` scope and
   held by `tenant-owner` and `tenant-admin` only. It is not a bootstrap capability: the
   platform-admin realm role does not confer it (the bypass in `JdbcAuthorizationService`
   stays one capability wide, `iam.grant.manage`).
2. **What a job is.** A name (one free-text value, 1 to 80 characters, no translations),
   the level it is normally given at (`TENANT`, `BRAND` or `LOCATION`, fixed at creation),
   and a non-empty set of capability codes. The code a grant names is generated by the
   platform, `job-` followed by ten lowercase hex digits, immutable, and never a platform
   code, so a tenant job cannot shadow `tenant-owner`. The service mints it and checks it
   against the code-owned registry; `GrantManagementService.resolveRole` consults that
   registry before the table whatever a row is called, so a hand-written row cannot shadow
   either. The schema does not constrain the format (see Specification: a dozen existing
   suites hand-author tenant roles with readable codes to model a narrower-than-bundle grant).
3. **What a job may carry.** Every capability in it must be (a) held by the author at
   `TENANT` scope at the moment of the write, and (b) not on the deny-list. The deny-list is
   derived from code, not written twice: a capability is composable only if some
   tenant-visible code-owned job already carries it (so every device-class,
   platform-staff-only, support-session-only, granted-per-person and non-staff-relationship
   capability is excluded by construction) and its resource type is neither `platform` nor
   `iam`. A tenant job therefore never carries `platform.admin`, a grant power, a role
   power, or a device's authority, and a new capability that no human job carries yet is
   unavailable to tenant jobs until a code-owned bundle takes it. This is ADR 0025's
   «they can never invent a capability» made executable.
4. **Edit is a new version, effective at once.** `PUT` with `If-Match` replaces the name and
   the capability set, bumps `iam.roles.version`, evicts the grant cache of every active
   holder, and is audited with a reason as a change document (before and after). The
   editor must satisfy rule 3 for the whole resulting set. The level does not change; to
   move a job to another level, make a copy and move its holders.
5. **Retire, never delete.** `DELETE` with `If-Match` and a reason sets `status = RETIRED`
   with `retired_at`, `retired_by`, `retired_reason`. It is refused (`409`) while any
   active grant names the job, so a retire never silently removes access. A retired job's
   code is not reused; its name is free again.
6. **A template is a copy.** There is no template table. The console opens the editor
   prefilled from any visible job (code-owned or the tenant's own), trimmed to what the
   author holds, and the new job records `copied_from` for the audit trail.
7. **Giving a tenant job away is the unchanged ADR 0103 path.** `GrantManagementService`
   resolves the job, then `requireGrantable` applies as written: the granter must hold every
   capability of the job at the scope, and a granter whose `iam.grant.manage` stops short of
   the company may give a job only at a level no broader than the scope — now read from the
   job's own `scope_type` where the job is a tenant's, not only from the code-owned
   registry. A tenant job appears in `grant-roles` with `grantable` computed by that same
   refusal, so the picker and the button cannot disagree.
8. **Isolation is in the schema as well as the service.** `iam.roles` gets row-level
   security in the shape ADR 0056 reserved for it: a tenant sees the platform's rows
   (`tenant_id IS NULL`) and its own, and may write only its own; the platform rows are
   written under the exempt role (`bindPlatform`), which is what `RoleRegistrySynchronizer`
   now does. `iam.role_capabilities` follows its role's visibility. Every reader that joins a
   grant to its role binds the tenant (or the exempt role, for the few cross-tenant
   reconcilers) first. `iam.grants` is not touched by this record.
9. **The read side.** `GET .../roles` returns the code-owned jobs and the tenant's own with
   `custom`, `name`, `version` and `grantable`; the branch and brand routes include the
   tenant's jobs; and a tenant-readable catalogue (`GET .../capabilities`: code, area,
   action, `held`, `composable`) replaces the platform-admin-only registry as the console's
   source for the grid, which is a lazy route in the staff area.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| Keep «no tenant-defined roles» and ship more code-owned bundles on request | Every new bundle is a release and a decision for HorecaOS about one tenant's org chart; the owner's request (a «Старший кассир») is a per-tenant fact | Never for the per-tenant fact; a recurring job name across tenants becomes a new code-owned bundle |
| Let a tenant edit the code-owned bundles | Changes what `tenant-owner` means for every tenant at once and breaks ADR 0103's reasoning about what a branch manager holds | Never |
| Let a tenant job carry any capability its author holds, with no deny-list | An administrator could mint a job carrying `iam.grant.manage` and `iam.role.manage` and hand them to anyone, widening who may give authority without the owner deciding it | A tenant needs a delegated administrator who is neither owner nor admin; then a record decides which `iam.*` power, not a loosening of this list |
| Free-form capability strings or per-tenant capabilities | ADR 0025's accepted trade-off: capabilities are code-owned so an unknown one fails at startup | Never |
| A template table (platform-curated starter jobs) | A second catalogue to keep in step with the first, for a convenience a copy provides | Tenants ask for starters HorecaOS curates per cuisine |
| Translations per job name | Triples the editor and the audit sentence, and the pilot tenants write in one language each | A tenant with staff reading different languages needs the same job named for each |
| Row-level security on `iam.grants` in the same change | Grants are read on the hot path by far more callers than roles, and the ADR 0056 inventory for them is its own wave | The next `iam` backstop wave |
| A level that can be edited | A level change silently re-meaning every grant of the job for ADR 0103's branch-granter rule | Holders need to move often enough that copy-and-move is a chore |

## Consequences

### Positive

- An owner can say what a job is without waiting for a release, and the sentence the console
  prints for it («Что можно делать») is built from the same capability sentences as every
  code-owned job.
- The escalation rules stay where ADR 0025 put them: nobody gives, or makes, more than they
  hold, and the deny-list keeps the authority to give authority code-owned.
- `iam.roles` joins `inventory` and `printing` in having the database refuse a cross-tenant
  read or write that a missing predicate would have allowed.

### Negative

- Every reader of a grant's role now carries a binding obligation. A reader that forgets it
  loses a tenant job's holders from its answer (an inner join drops the grant) while the
  platform's eight jobs keep working, so the failure is invisible until a tenant defines a
  job. This record's tests run the real readers as the application role to hold that line;
  a future reader of `iam.roles` is on its own.
- Edits are live. A job edited at three in the afternoon changes what every holder can do at
  the next request. The mitigation is the reason on the audit fact and the holder count the
  editor shows before it saves, not a staging step.
- A tenant job has no description and no translations, so a job name can be unclear to a
  colleague who did not write it.
- Twenty active jobs is a ceiling that some tenant will meet and want raised.

### Accepted trade-offs

- A tenant job cannot carry any `iam.*` capability, so a delegated administrator is not
  expressible. Accepted: the code-owned bundles are where authority to give authority lives.
- `RoleChanged` is an in-process event consumed by the audit listener, like `GrantChanged`;
  no external consumer reads it, so it has no Kafka schema and no outbox row. If a
  consumer outside the process ever needs it, that consumer's record adds the schema.
- The grants cache is evicted per holder on every edit. The cache is a registered accelerator
  and PostgreSQL stays the authority, so a missed eviction costs a TTL, not a decision.

## Specification

### Schema (V0692, V0693)

`iam.roles` gains `created_by`, `updated_by`, `copied_from`, `retired_at`, `retired_by`,
`retired_reason`, all nullable. Checks: `ck_role_retirement_agrees` (the three retirement
columns are set together, and only on a `RETIRED` row) and
`ck_role_platform_has_no_tenant_columns` (a code-owned job carries none of them). Not
constrained, deliberately: the shape of a tenant job's code and a non-null `created_by`.
Both are the service's to mint, and constraining them would refuse the rows a dozen existing
suites hand-author without making anything safer. V0693 enables row-level security:

```text
iam.roles           SELECT  tenant_id IS NULL OR tenant_id = guc
                    INSERT  tenant_id = guc            (WITH CHECK)
                    UPDATE  tenant_id = guc            (USING and WITH CHECK)
iam.role_capabilities
                    SELECT  its role is visible
                    INSERT/DELETE  its role belongs to guc
```

`guc` is `NULLIF(current_setting('horecaos.tenant_id', true), '')::uuid` — ADR 0056's
setting, fail-closed for an unbound session. No policy permits a delete of `iam.roles`; the
application role has no `DELETE` on it (V0035).

### API (ADR 0031)

```text
GET    /api/v1/control-plane/tenants/{tenantId}/roles            iam.grant.manage   jobs, code-owned and the tenant's
GET    /api/v1/control-plane/tenants/{tenantId}/capabilities     iam.grant.manage   the catalogue the grid is built from
POST   /api/v1/control-plane/tenants/{tenantId}/roles            iam.role.manage    create (Idempotency-Key)
PUT    /api/v1/control-plane/tenants/{tenantId}/roles/{code}     iam.role.manage    edit (If-Match, Idempotency-Key)
DELETE /api/v1/control-plane/tenants/{tenantId}/roles/{code}     iam.role.manage    retire (If-Match, reason)
```

The branch and brand `grant-roles` routes of `ScopedGrantController` gain the tenant's jobs.
Refusals: `VALIDATION_FAILED` (name, level, empty set, a capability on the deny-list),
`INSUFFICIENT_CAPABILITY` (a capability the author does not hold, named), `STALE_VERSION`,
`RESOURCE_CONFLICT` (a name already in use, the twenty-job ceiling, a retire with holders),
`RESOURCE_NOT_FOUND` (a code that is not this tenant's, indistinguishable from none).

### Audit and events

`iam.role.created`, `iam.role.changed`, `iam.role.retired`, class `SECURITY`, written
before commit by a listener on the in-process `RoleChanged` event (the module cycle that
keeps `iam` from calling `audit` is the one `GrantChanged` documents), every document a
`ChangeDocuments.created` or `.diff`, correlated by the request's correlation id. The
console's action-code dictionary names the three in ru, uz-Latn and en, and its coverage
spec fails for a literal code without a sentence.

### Console

A lazy route under the staff area, `/staff/roles/grid`, «Таблица прав»: every job across (the
code-owned ones and the company's own) and every capability down in plain sentences, one
read-only `q-matrix-grid` per area, with a search over the catalogue itself — the sentence in the
reader's language, the dotted code or the area name. The job library gains, for a person holding
`iam.role.manage`, «Новая должность» and, per job, «Копировать» (any job) and «Изменить» (the
company's own), all of which open the editor beside the grid (`?new`, `?copy=<code>`,
`?edit=<code>`, so a refresh keeps the form). The editor is a checklist grouped by area, not
clickable matrix cells: an edit is one versioned write with a name and a reason, and a matrix of
cells would invite ticking forty boxes across six jobs and saving none. It shows the number of
people a change reaches, locks (with the reason) what the author does not hold or what a company's
job can never carry, and closes a job nobody holds. All strings are in the staff area's message
files, in ru, uz-Latn and en.

## Rollout and rollback

V0692 and V0693 are additive. Rollback of the feature is retiring the tenant's jobs (the
endpoints refuse while holders remain, so holders are moved first) and, if the policies
themselves must go, a forward migration with `DISABLE ROW LEVEL SECURITY` on the two
tables — policies carry no data. Nothing here changes a code-owned job, a grant or a
Keycloak role.

## Implementation checklist

- [x] V0692 columns and checks; V0693 policies; `RoleRegistrySynchronizer` under the exempt role
- [x] `iam.role.manage` in `Capability`, `tenant-owner` and `tenant-admin`; sentences in three locales
- [x] `TenantRoleService` and the three endpoints; deny-list; subset-of-author; ceiling; duplicate name
- [x] Every reader of a grant's role binds the tenant (or the exempt role), proved as the application role
- [x] `GET roles`, `GET capabilities`, `grant-roles` with the tenant's jobs; `requireGrantable` reads a tenant job's level
- [x] `RoleChanged`, audit listener, action-code dictionary in three locales
- [x] Console: grid, search over the catalogue, editor, copy, retire, holders
- [x] ADR 0025, ADR 0056 and ADR 0103 carry dated status additions pointing here

## Exit criteria

An owner defines a job from the console, gives it to a colleague, edits it, and the
colleague's next request reflects the edit; an administrator cannot put a capability she
does not hold, or any `iam.*` or `platform.*` capability, in a job; a second tenant cannot
read, name, edit or grant it, proved both through the API and as the application role
against the table; and a retire with a holder is refused.

## References

- [ADR 0025: Fine-grained authorization and the capability model](../built/0025-fine-grained-authorization-and-capability-model.md)
- [ADR 0027: Audit evidence and approval model](../built/0027-audit-evidence-and-approval-model.md)
- [ADR 0031: HTTP API conventions](../built/0031-http-api-conventions.md)
- [ADR 0033: Caching, rate limiting and shared runtime state](../built/0033-caching-rate-limiting-and-shared-runtime-state.md)
- [ADR 0056: Application-enforced tenant isolation, with RLS as a production backstop](../partial/0056-tenant-isolation-enforcement-and-rls.md)
- [ADR 0103: A branch manager's own scope should reach `iam.grant.manage`](../not-started/0103-wave131-p30-a-branch-managers-own-scope-should-reach-iam-grant-manage.md)
- `docs/operations-spec/staff-and-access.md` §5 and §11.4
