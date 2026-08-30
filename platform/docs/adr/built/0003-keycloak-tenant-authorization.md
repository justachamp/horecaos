# ADR 0003: Keycloak tenant authorization

- Decision status: Accepted
- Implementation status: Built — `V0003` persists
  `tenant.tenants.keycloak_organization_id`; `JwtCurrentActor` reads the signed
  `organization` claim, keys roles by the immutable organization `id` and
  ignores an entry without one, so an alias can never match a tenant;
  `TenantAccessPolicy` matches the URL tenant to that claim, treats membership
  as tenant read context and requires `tenant-owner`/`tenant-admin` from the
  matching organization's nested roles for writes, never a top-level role;
  `platform-admin` is the single global `horecaos-api` client role.
  `SecurityConfiguration` validates the JWT against `issuer-uri` and
  `audiences: horecaos-api` from `application.yml`.
  `infra/keycloak/realm/horecaos-realm.json` has `organizationsEnabled` with the
  `oidc-organization-membership-mapper` (`addOrganizationId: true`) and the
  `oidc-organization-group-membership-mapper` (`addGroupRoleMappings: true`) on
  the `organization` scope. Fine-grained grants stay in `iam.grants` (`V0008`)
  rather than the token. Note for operators, and it has changed: since ADR 0025
  came into force `requireTenantRead` also demands a `TENANT_READ` capability
  grant, but `TenantAccessPolicy.requireTenantRead` returns early for the
  `platform-admin` realm role, and `JdbcAuthorizationService` now carries a
  deliberately narrow bootstrap bypass conferring exactly one capability —
  `IAM_GRANT_MANAGE` — and only on the calling actor holding that role. A fresh
  deployment with no grant row is therefore no longer shut: a platform admin can
  read a tenant and create the estate's first grants through the ordinary
  audited API, then must grant themselves everything else. Neither bypass adds a
  tenant write path or an organization-scoped role, so this record's own rules
  are unchanged; `GrantManagementService` still refuses to confer
  `platform-admin`, which stays Keycloak's to issue and to revoke.
- Date proposed: 2026-08-19
- Date decided: 2026-08-19
- Deciders: Ayubkhon Abbosov (platform architecture), security
- Depends on: ADR 0002
- Supersedes / Superseded by: — / [ADR 0051](../built/0051-customer-session-authentication.md)
  supersedes this record's "build an in-house identity provider — never" row for the
  **customer** principal only. Staff authentication, tenant matching, the organization
  claim and the single trusted JWT issuer are unchanged, and a customer is deliberately
  not a realm user.
- Open inputs: none

## Context

One Qoida principal may belong to multiple SaaS tenants. Tenant context cannot
come from an arbitrary HTTP header, and a role granted for one organization
must not authorize the same user in another organization. Keycloak owns B2B
organization membership and coarse roles, while Qoida owns tenant, brand,
location, entitlement, and resource relationships.

## Decision

- Map one Keycloak Organization to one Qoida tenant and persist the immutable
  Keycloak organization ID on the tenant record.
- Keep the tenant ID in versioned resource URLs. Match that tenant to the
  authenticated token's signed `organization` claim before reading or changing
  tenant data.
- Configure Keycloak's organization mapper to include organization IDs and its
  organization-group mapper to include roles inside each organization entry.
- Frontends request `organization:<alias>` for their selected tenant.
  Administrative clients may explicitly request `organization:*` when they
  need multiple memberships.
- Use `platform-admin` as a global `horecaos-api` client role. It is the only
  current global control-plane role.
- Treat organization membership as sufficient for tenant reads. Require
  `tenant-owner` or `tenant-admin` from the matching organization's nested role
  claim for tenant writes.
- Never treat a top-level `tenant-owner`, `tenant-admin`, or `tenant-viewer`
  role as authorization for every tenant. Top-level role aggregation can
  include roles originating from another organization.
- Continue to validate token signature, issuer, expiry, and the `horecaos-api`
  audience with Spring Security.
- Keep fine-grained brand, location, plan, entitlement, and resource grants in
  Qoida projections rather than placing unbounded relationship lists in JWTs.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| One Keycloak realm per tenant | Realms are heavyweight isolation units. Practitioner guidance puts realm counts around one hundred before performance and memory degrade, while Organizations are designed for thousands of tenants inside one realm. Realm-per-tenant also duplicates clients, flows, mappers, and themes per tenant and makes a multi-tenant user impossible without identity brokering | One specific tenant contractually requires an isolated realm with its own administrators and login flows. Give that tenant a dedicated realm as an exception, not the model |
| Tenant ID from a subdomain or `X-Tenant-Id` header | Unsigned tenant context is a privilege-escalation primitive: any authenticated user could address any tenant. Tenant context must come from a signed claim matched to a persisted immutable ID | Never |
| Group-per-tenant with role paths | The established pre-Organizations pattern, and workable, but group paths are mutable strings and carry no membership, invitation, or per-tenant identity-provider semantics. Organizations provide those natively in Keycloak 26 | Never; migrate remaining group conventions into Organizations |
| Put every fine-grained grant into the token via Keycloak Authorization Services | Token size grows without bound as brands, locations, and resources multiply, and per-request policy evaluation puts an external dependency on the hot path | Never for relationship grants. Coarse roles stay in the token; ADR 0025 owns the rest |
| Match tenants by organization alias | Aliases are mutable display-level identifiers. Renaming an organization would silently redirect authorization | Never |
| Build an in-house identity provider | Credentials, MFA, brokering, and session security are solved problems and are not where Qoida differentiates | Never |

## Consequences

- A token without the organization scope cannot access a tenant merely because
  it is otherwise authenticated.
- Alias-only organization claims are rejected for tenant matching because
  aliases are not the immutable foreign identity key.
- Multi-tenant users remain safe even when a token deliberately contains more
  than one organization: each request is matched to one persisted organization
  ID and its own nested roles.
- Tenant creation precedes Keycloak provisioning. Until the organization ID is
  reconciled, only a global platform administrator can access that tenant.
- Keycloak organization provisioning, invitations, membership reconciliation,
  and application-owned fine-grained grants remain separate idempotent
  onboarding steps.

## References

- [Keycloak organization claims](https://www.keycloak.org/docs/latest/server_admin/#_mapping-organization-claims)
- [ADR 0002: SaaS domain model](../partial/0002-saas-domain-model.md)
