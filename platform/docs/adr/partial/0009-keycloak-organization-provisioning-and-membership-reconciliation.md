# ADR 0009: Keycloak organization provisioning and membership reconciliation

- Decision status: Accepted
- Implementation status: Partial — the provisioning adapter and the
  organization-level drift report work, and as of 2026-08-30 they work from a
  fresh `make up`: the OpenBao seed and the realm's client secrets are
  reconciled, `assign-service-account-roles.sh` runs inside `make up` with
  wait/retry, and `KeycloakOrganizationIntegrationTests` — previously
  skip-only — passes 8/8 against the live realm. `ensureOrganizationRoles` is
  deliberately not implemented: Keycloak 26.7's Organizations API has no
  org-scoped roles, so the platform-side `tenant-owner` grant (ADR 0008) is
  the v1 answer, recorded in `OrganizationProvisioner`'s javadoc. The IAM
  evidence tables still exist with no code writing them. Pointing the adapter at a real realm found
  that `ensureMembership` had never worked: Spring's `RestClient` sends a
  `String` body with no `Content-Type`, and the organization members endpoint
  answers 415 every time, so owner linking failed against any Keycloak and passed
  against every stub. Fixed by naming the content type. That is the second time
  on this record that a test bypassing the real thing proved less than it
  appeared to. **The first checklist box was wrong and is
  now unchecked.** `infra/keycloak/realm/horecaos-realm.json` declares the two
  service accounts and maps *no* `realm-management` roles onto either of them, so
  against the checked-in realm every Admin API call in this record returns 403 —
  including the ones the table below records as 201. Verified 2026-08-25 against
  the running Keycloak 26.7: both clients authenticate, and `GET
  /admin/realms/horecaos/organizations` is forbidden for both. Granting the ADR's
  role sets by hand reproduces the table exactly, 403s included, so the decision
  is right and the export does not carry it. `OrganizationDirectory` splits the
  read path from the write path so the drift
  report holds a credential that cannot write, and `KeycloakOrganizationDirectory`
  distinguishes "the organization is gone" from "Keycloak cannot be asked"
  (the previous `catch (RuntimeException)` reported an unreachable realm as
  missing drift for every tenant at once); `IdentityDriftReporter` compares every
  live tenant's stored organization against the realm on a timer and reports
  `ORGANIZATION_MISSING`, `_DISABLED`, `_ALIAS_MISMATCH` and `_UNLINKED` as
  ADR 0027 `SECURITY` audit facts plus `horecaos.iam.identity.drift`,
  `.drift.detected{code}`, `.drift.scans{outcome}` and `.drift.report.age.seconds`
  — the last so that a report which has silently stopped is distinguishable from
  a healthy estate; and `KeycloakOrganizationIntegrationTests` runs the ADR's own
  test list against a real Keycloak, skipping with the realm finding above rather
  than substituting a mock. `infra/keycloak/realm/horecaos-realm.json`
  defines the two approved service accounts (`horecaos-provisioning`,
  `horecaos-identity-reader`); `KeycloakConfiguration` authenticates with cached
  client-credentials tokens refreshed 30s before expiry, bounded 3s connect /
  10s read timeouts, and resolves the client secret by ADR 0028 reference so no
  credential is logged; `KeycloakOrganizationProvisioner` implements
  `ensureOrganization` with read-back by stored id and an explicit refusal to
  create a replacement on drift or on an ambiguous alias, plus `getOrganization`
  and `ensureMembership` (subject link, then create-or-link against
  `/organizations/{org}/members`); `OnboardingStepHandlers` connects both to the
  ADR 0008 workflow and reconciles by stored identifier on retry. What changed
  since the last reconciliation is the schema and only the schema: `V0057` creates
  `iam.principals`, `iam.tenant_membership_links` and
  `iam.identity_reconciliation_runs`, with the two-column foreign key that makes a
  link naming another tenant's organization impossible and the
  `uq_tenant_id_keycloak_organization` constraint it needs, all three granted to
  `horecaos_application`. **No Java reads or writes any of the three.** The linked
  subject and organization still survive only in the tenant row and the onboarding
  step result, membership-level drift is still not reported —
  `IdentityDriftReporter` never emits `MEMBERSHIP_UNVERIFIED`, and its own comment
  claiming the migration is missing is now the stale part — and
  `iam.identity_reconciliation_runs` has never held a row. Also not built:
  organization-scoped role reconciliation — no `ensureOrganizationRoles` exists
  anywhere in `src/main/java`, and the `OrganizationMembershipProvisioner` port
  sketched below was never split out of `OrganizationProvisioner`; the three
  control-plane identity endpoints this record specifies; the four events; and
  the probe stanza reading `horecaos.iam.identity.drift.report.age.seconds`. Every
  claim in this implementation status line was checked against the source tree on
  2026-08-25, not against another document.
  Two more closures on 2026-08-31: `TenantAccessPolicy` now implements the v1
  answer this record already named — tenant management resolves through real
  `iam.grants` capabilities instead of an org-nested role Keycloak cannot grant —
  and `ensureMembership` sets non-blank name attributes while the realm's User
  Profile relaxes the requirement, closing the keycloak#36108 password-grant trap.
  Disable and re-enable closed 2026-09-08, and a wrong assumption caught doing
  it: `OrganizationProvisioner.setOrganizationEnabled` fetches the
  organization, no-ops when the requested state already holds, and otherwise
  `PUT`s the full fetched representation back with only `enabled` flipped —
  never a hand-built body that would drop alias, name, or domains — refusing
  on a vanished id the same way `ensureOrganization` does. The first version of
  this closure, and of this paragraph, claimed a disabled organization's
  members are refused Keycloak authentication, on the strength of this
  record's own "Disable, suspend, and delete" section, never checked against a
  real realm. Proven wrong the first time the integration test ran for real:
  `disablingAnOrganizationDoesNotByItselfBlockDirectGrantAuthentication` (using
  the ADR 0062 `horecaos-staff-login` direct-grant client) shows a member of a
  disabled organization still completing the resource-owner password grant and
  receiving a valid token against a live Keycloak 26.7.0, confirmed independently
  by hand outside the test harness before believing it. Keycloak's Organizations
  API is not an authentication control in this version: **`setOrganizationEnabled`
  reconciles a status flag — the value `IdentityDriftReporter`'s
  `ORGANIZATION_DISABLED` comparison and a console-reading operator see — it does
  not gate sign-in, and must never be treated as tenant suspension's enforcement.**
  Where that enforcement lives is, as of 2026-09-08, nowhere. This paragraph
  first named `TenantAccessPolicy`'s `iam.grants` capability checks, and that is
  wrong for the same reason the Keycloak claim was: it was reasoned from what
  the code ought to do rather than read. `TenantAccessPolicy` contains no
  reference to `TenantStatus` or `SUSPENDED`, and
  `JdbcAuthorizationService.SELECT_GRANTS` filters on `g.status = 'ACTIVE'`,
  `r.status = 'ACTIVE'` and the grant's validity window without ever consulting
  the tenant's own status — so every capability a suspended tenant's staff held
  is still live. Suspending a tenant sets a column in `tenancy` and stops
  nobody. That is a gap this record names and does not close: deciding where
  the check belongs (the grant lookup, `TenantAccessPolicy`, or a request-scoped
  gate) is an ADR 0025 question, and it has to keep a platform administrator
  able to read and reactivate the tenant they just suspended. Two further integration tests cover idempotent retry in both
  directions (`disablingAnAlreadyDisabledOrganizationIsANoOpRatherThanAnError`)
  and that disabling one tenant's organization leaves another's members
  unaffected (`disablingOneOrganizationDoesNotAffectAnother`). Wired 2026-09-08:
  `TenantControlPlaneService.suspendTenant`/`reactivateTenant` now call
  `setOrganizationEnabled` — after their status transaction commits, never
  inside it. `setOrganizationEnabled` is a blocking HTTPS round trip and the
  status write is a database transaction; spanning both in one would hold a
  pooled connection for as long as Keycloak takes to answer, which is exactly
  what `ExternalCallTransactionBoundaryTests` exists to police, and it gained a
  case (`tenantSuspensionDoesNotHoldAConnection`) proving this call holds none
  — the same split `PaymentAttemptService` and `OnboardingService` already use,
  for the same reason: a method calling its own `@Transactional` method skips
  the proxy, so committing before calling out needs an explicit
  `TransactionTemplate`. The tenant status row commits regardless of whether
  Keycloak answers; a reconciliation failure is caught, logged, and left rather
  than retried inline or allowed to undo or block the status change, because a
  suspension whose Keycloak flag failed to flip is still a suspension in every
  way that matters — its enforcement was never this method, as the previous
  paragraph found the hard way. What closes the resulting gap is
  `IdentityDriftReporter`, extended the same day to compare both directions
  instead of one: `ORGANIZATION_DISABLED` for a live tenant wrongly disabled,
  and the new `ORGANIZATION_ENABLED_WHILE_SUSPENDED` for a suspended tenant
  whose reconciliation never landed. `POST
  /api/v1/control-plane/tenants/{tenantId}/suspend` and `.../reactivate` also
  now exist on `TenantControlPlaneController` (platform-admin `TENANT_WRITE` at
  `PLATFORM` scope, `Idempotency-Key` required), so a suspension is reachable
  over HTTP and not only from code — but not yet from an operator's screen:
  nothing under `frontend/control-plane/src` calls either endpoint.
  `ControlPlaneWiringIntegrationTests` proves the full round trip live over
  HTTP with no local Keycloak running, specifically because that is the
  condition under which the reconciliation call is expected to fail and the
  status change is not.
- Date proposed: 2026-08-19
- Date decided: 2026-08-20
- Deciders: Ayubkhon Abbosov (platform architecture), security
- Depends on: ADR 0003, ADR 0008, ADR 0028
- Supersedes / Superseded by: —
- Open inputs: none
- Closed inputs: Two service accounts with a verified narrow role set, approved 2026-08-21

## Context

Qoida validates Keycloak organization claims and can manually link an immutable
organization ID to a tenant. SaaS onboarding still lacks automatic organization
creation/reconciliation, tenant-owner invitation/linking, membership drift
detection, and safe disable/re-enable behavior.

## Decision

Implement Keycloak administration behind IAM application ports. The tenancy
onboarding workflow invokes those ports but never imports Keycloak Admin Client
DTOs. Use a dedicated confidential service-account client with least-privilege
realm-management roles and credentials supplied by the environment secrets
manager.

Use direct HTTPS through Spring's HTTP client for the first Keycloak adapter;
Camel is unnecessary unless mediation, independent scaling, or protocol policy
later justifies it. All calls remain idempotent onboarding steps.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| Route Keycloak administration through Camel | Camel earns its place where mediation, throttling, protocol translation, or independent scaling apply. Keycloak administration is a small set of authenticated JSON calls on a control path, so a direct HTTP client is simpler to read and test. This is the deliberate counter-example to ADR 0007 | Keycloak calls need independent scaling, queueing, or protocol policy |
| Use Keycloak Admin Client DTOs directly in the tenancy module | A provider type leak straight into the domain. A Keycloak upgrade would become a tenancy change | Never |
| Manage tenant organizations with Terraform or the Keycloak provider | Correct for realm, client, and mapper configuration and still recommended there. Wrong for runtime per-tenant provisioning: no reconciliation loop, no API for onboarding, and a plan-apply cycle in a customer signup path | Never for runtime organizations; keep using it for realm-level configuration |
| Create a replacement organization when the stored ID is missing externally | Produces two identities for one tenant and silently orphans memberships. Stopping with a drift report is slower and correct | Never |
| Build invitations inside Qoida | A second credential and token system to secure, expire, and audit, duplicating what Keycloak already provides | Keycloak's organization invitation flow proves insufficient for a required product behavior |
| Treat HTTP 409 from Keycloak as success | A conflict may be an unrelated object with the same alias. Reading back and verifying is the only safe interpretation | Never |
| Automatically correct detected drift | Destructive automation against identity data, where a false positive removes a real user's access | Drift categories are proven safe individually, each with its own approval |

## Verified service-account roles

Decided 2026-08-21: **two accounts, not one**. The drift report runs unattended
on a timer, and that is exactly when a credential should not carry write
capability it never uses.

| Client | Purpose | `realm-management` roles |
|---|---|---|
| `horecaos-provisioning` | Create organizations, create and link owners, assign organization-scoped roles | `manage-organizations`, `manage-users`, `view-users`, `query-users` |
| `horecaos-identity-reader` | Scheduled drift report | `view-organizations`, `query-organizations`, `view-users`, `query-users` |

Neither holds `manage-realm`, `realm-admin`, `manage-clients`, or
`impersonation`.

These were verified against a running Keycloak 26.7 rather than assumed, because
a role set that looks right and grants too much is indistinguishable from one
that is right until it is abused:

```text
provisioning  create organization      201
provisioning  create user              201
provisioning  add organization member  201
provisioning  update realm             403   <- least privilege holds
reader        list organizations       200
reader        create organization      403   <- read-only holds
```

The two 403s are the result that matters. A provisioning credential able to
change the realm could create administrators and rewrite authentication flows; a
drift credential able to write could quietly alter the memberships it exists to
report on.

## Implementation notes

The adapter speaks plain HTTPS and keeps every Keycloak type inside itself, so a
Keycloak upgrade cannot become a tenancy change. Tokens are client-credentials
with a cached value refreshed thirty seconds before expiry — fetching per
request would add a round trip to every onboarding step, and caching to the
exact expiry would fail intermittently on clock skew.

The provisioning client secret is resolved from the ADR 0028 manager at call
time, so rotation takes effect without a restart.

**A gap the full build caught.** `OnboardingServiceTests` constructed the
handler graph by hand and passed, while the real application context could not
start at all because no `OrganizationProvisioner` bean existed. A test that
bypasses the container proves less than it appears to — the same lesson as the
ADR 0031 idempotency bug. Two module boundary fixes came with it: a
`@NamedInterface` on a parent package does not cover its sub-packages, so
`iam.api.organizations`, `iam.api.protection`, and `iam.api.secrets` each need
their own or they stay internal to `iam`.

Not yet delivered: organization-scoped role reconciliation and the drift report
using the read-only account. Disable and re-enable handling closed 2026-09-08
(see Implementation status).

## IAM ports

```java
interface OrganizationProvisioner {
    OrganizationRef ensureOrganization(EnsureOrganization command);
    OrganizationSnapshot getOrganization(String organizationId);
    void setOrganizationEnabled(String organizationId, boolean enabled);
}

interface OrganizationMembershipProvisioner {
    MembershipRef ensureMembership(EnsureMembership command);
    void ensureOrganizationRoles(EnsureOrganizationRoles command);
    MembershipSnapshot getMembership(String organizationId, String subjectId);
}
```

Commands carry tenant ID, stable idempotency key, desired alias/name, subject,
roles, correlation, and actor context. Provider response types do not escape the
adapter.

## Persistence

Retain `tenant.tenants.keycloak_organization_id` as the authoritative immutable
link. Add IAM-owned reconciliation evidence:

### `iam.principals`

```text
id, keycloak_realm, keycloak_subject_id
status, created_at, updated_at
unique(keycloak_realm, keycloak_subject_id)
```

### `iam.tenant_membership_links`

```text
id, tenant_id, principal_id
keycloak_organization_id, keycloak_membership_id
status, last_reconciled_at, version
created_at, updated_at
unique(tenant_id, principal_id)
```

### `iam.identity_reconciliation_runs`

Store operation type, desired hash, external references, attempts, result,
error code, timestamps, correlation, and onboarding step ID. Never store access
tokens, client secrets, passwords, or invitation tokens.

## Organization algorithm

1. If the tenant has an immutable organization ID, fetch it by ID.
2. If it exists, verify alias/name/enabled state and reconcile allowed mutable
   fields.
3. If the ID is missing externally, stop with drift requiring operations; do
   not silently create a replacement organization.
4. If the tenant has no ID, search only by the deterministic configured alias
   as pre-create reconciliation.
5. If exactly one compatible organization exists, link its immutable ID after
   ownership validation.
6. If none exists, create with the onboarding idempotency marker, read back,
   and persist the immutable ID.
7. If multiple/conflicting organizations exist, fail safely for manual review.

Never join normal runtime authorization by mutable alias or display name.

## User and invitation policy

- Prefer linking an already authenticated/verified Keycloak subject when the
  onboarding actor supplies one.
- Otherwise invite through a reviewed Keycloak organization flow; do not build
  a second invitation credential system in Qoida.
- Store only immutable subject/membership IDs and invitation status/evidence.
- Assign `tenant-owner`, `tenant-admin`, and `tenant-viewer` inside the exact
  organization scope configured by ADR 0003.
- A global `platform-admin` role is never granted by tenant onboarding.
- Re-sending an invitation or role reconciliation must be idempotent.

## Disable, suspend, and delete

Tenant suspension disables access according to a reviewed policy but preserves
the organization, memberships, and audit evidence. Initial implementation does
not delete Keycloak organizations or users. Permanent deletion requires a
separate retention/privacy workflow and explicit approval.

## Security

- Restrict service-account roles to required organization/user operations.
- Use TLS, issuer/host allowlists, bounded timeouts, and credential rotation.
- Never log Admin API bearer tokens, invitation links, user credentials, or raw
  personal payloads.
- Protect admin endpoints from SSRF by fixed environment base URL.
- Audit organization link, invitation, membership, role, disable, enable, and
  drift-resolution actions.
- Rate-limit onboarding and invitation actions to prevent abuse.

## Failure and reconciliation

Classify timeouts after create/invite as uncertain. Reconcile by immutable ID,
idempotency marker, or exact deterministic alias before another side effect.
HTTP 409 is not automatically success; read and verify the conflicting object.
HTTP 401/403 is configuration/security failure and should not retry forever.

Add a scheduled drift report comparing active Qoida tenants/membership links
with Keycloak IDs and expected scoped roles. It reports drift first; automatic
destructive correction is out of scope.

## APIs and onboarding integration

Keycloak mutations are normally invoked by ADR 0008 steps. Provide narrowly
scoped platform operations for reconcile and readback:

```text
POST /api/v1/control-plane/tenants/{tenantId}/identity/reconcile
GET  /api/v1/control-plane/tenants/{tenantId}/identity/status
POST /api/v1/control-plane/tenants/{tenantId}/memberships/reconcile
```

Do not expose a generic pass-through Keycloak Admin API.

## Events

```text
TenantOrganizationLinked
TenantOwnerLinked
TenantMembershipReconciled
TenantIdentityDriftDetected
```

Events contain immutable Qoida/Keycloak IDs and status, not invitation tokens
or user profile details.

## Testing

- Import the checked-in realm into an isolated supported Keycloak container.
- Create/reconcile organization twice and assert one external object.
- Recover an uncertain create through readback without duplication.
- Link/invite the same owner twice and preserve one membership.
- Ensure roles appear only inside the matching organization claim.
- Verify a multi-tenant user cannot carry one tenant's role into another.
- Test service-account least privilege, invalid credentials, rotation, disable,
  re-enable, missing external object, and alias conflict.
- Verify logs and database contain no tokens, secrets, or invitation links.

## Rollout and rollback

Run in read-only reconciliation mode against a development realm, then create
organizations for disposable tenants, then one internal tenant. Rollback
disables provisioning while preserving immutable links and reconciliation
evidence; do not delete external objects automatically.

## Consequences

### Positive

- Tenant onboarding no longer requires manual work in the Keycloak console.
- Uncertain create and invite outcomes reconcile by immutable ID, so a timeout
  cannot produce two organizations for one tenant.
- Drift between Qoida and Keycloak becomes a report rather than a surprise
  during an incident.

### Negative

- A privileged service account now exists whose compromise would affect tenant
  identity. Its role set, rotation, and audit become permanent obligations.
- Keycloak Admin API changes across versions become an upgrade risk for
  onboarding, requiring integration tests against a pinned realm import.
- Drift detection produces findings that someone must triage, and an unwatched
  drift report is worse than none.

### Accepted trade-offs

- Refusing to auto-create a replacement organization when a stored ID is missing
  means some incidents require manual investigation and stay unresolved longer.
  Silent duplication of identity would be worse.
- Qoida stores only immutable identifiers, so support cannot answer questions
  about user profile attributes without querying Keycloak.

## Implementation checklist

- [ ] Approve the service-account role set, verified against Keycloak 26.7, with secrets delivered from OpenBao per ADR 0028. The role set is approved and the realm export carries none of it: neither service account has a `realm-management` role mapping, so every Admin API call is 403. The local OpenBao seed in `compose.yaml` also holds secrets the realm import never set, so the local application cannot authenticate at all.
- [x] Add IAM principal, membership-link, and reconciliation migrations. `V0057` creates `iam.principals`, `iam.tenant_membership_links` and `iam.identity_reconciliation_runs`, with the two-column foreign key that makes a membership link naming another tenant's organization impossible and the `uq_tenant_id_keycloak_organization` constraint it references, all three granted to `horecaos_application`. The tables are the only thing delivered: no code reads or writes any of them, which is the next box and the one after it.
- [x] Implement HTTP client authentication with cached client-credentials tokens, bounded timeouts, and no credential in any log.
- [x] Implement the organization ensure, read-back, and link algorithm, including the refusal to create a replacement on drift.
- [x] Implement subject link and create-or-link membership. Organization-scoped role reconciliation remains.
- [x] Connect the organization and membership steps to the ADR 0008 workflow, reconciling by stored identifier on retry.
- [x] Implement disable/re-enable (`setOrganizationEnabled`), idempotent by inspection against a real Keycloak. A real password grant against a live Keycloak 26.7.0 proved the opposite of what this box originally assumed: the flag flips and is read back correctly, but Keycloak does not itself refuse sign-in for a member of a disabled organization — see Implementation status, 2026-09-08, and do not read "implemented" here as "authentication is gated." `TenantControlPlaneService.suspendTenant`/`reactivateTenant` call it now, after their status transaction commits — see Implementation status, 2026-09-08, for why that is two steps and never one.
- [ ] Add drift reporting, audit facts, metrics, and alerts. Organization-level drift, its `SECURITY` audit facts and its four metrics are built and tested. Membership-level drift no longer waits on a migration — `V0057` shipped it — but on something writing `iam.tenant_membership_links`, since a drift report over an empty table would report every tenant clean. The probe stanza that reads `horecaos.iam.identity.drift.report.age.seconds` still belongs in `infra/observability/horecaos-probe.sh` and is not there.
- [x] Add isolated Keycloak integration and cross-tenant security tests. `KeycloakOrganizationIntegrationTests` covers double-ensure, uncertain-create readback, refusal to replace a vanished organization, double link, per-organization membership isolation, and both least-privilege 403s — against a real Keycloak. It still skips loudly (`Assumptions.abort`) if Keycloak is absent or the realm grants `horecaos-provisioning` no roles, but as of 2026-08-30 `make up` runs `assign-service-account-roles.sh` with wait/retry, so the normal path runs all 8 rather than skipping.

## Exit criteria

Onboarding can create or reconcile one Keycloak organization, link/invite a
tenant owner, assign organization-scoped roles, resume safely after uncertainty,
and prove cross-tenant denial without manual Keycloak-console work.
