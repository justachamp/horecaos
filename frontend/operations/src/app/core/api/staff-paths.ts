import { LocationScope } from './operations-paths';

/**
 * Where the Staff section's endpoints live (operations IA §9.1, ADR 0025).
 *
 * All three are `GrantController`/`TelegramStaffLinkCodeController` methods —
 * two on the `control-plane` OpenAPI surface (ADR 0057's own grouping is
 * path-prefix-based and predates any frontend importing a generated client,
 * so a tenant-administration read living under `/control-plane/tenants/**`
 * and being called from *this* app is already the established shape:
 * `settings-paths.ts`'s own doc comment says the same thing about the
 * pre-existing legal-entity, sales-channel and order-acceptance-policy
 * reads) — and one already on `/api/v1/tenants/**` (`operations`).
 */
const CONTROL_PLANE = '/api/v1/control-plane';
const OPERATIONS = '/api/v1';

export const staffPaths = {
  /** `GrantController.list`/`.grant` — active-only unless `includeInactive` is passed as a query param. */
  grants(tenantId: string): string {
    return `${CONTROL_PLANE}/tenants/${enc(tenantId)}/grants`;
  },

  grant(tenantId: string, grantId: string): string {
    return `${this.grants(tenantId)}/${enc(grantId)}`;
  },

  /** `GrantController.roles` — the eight tenant-visible `PlatformRole` bundles. */
  roles(tenantId: string): string {
    return `${CONTROL_PLANE}/tenants/${enc(tenantId)}/roles`;
  },

  /**
   * `ScopedGrantController` (ADR 0103) — a branch or brand manager's own team. The
   * tenant-wide `grants` route above needs `iam.grant.manage` at TENANT scope, which
   * a grant at her branch never covers (ADR 0025); these name the place in the path,
   * which is what her grant does cover.
   */
  locationGrants(scope: LocationScope): string {
    return `${OPERATIONS}/operations/tenants/${enc(scope.tenantId)}/brands/${enc(scope.brandId)}/locations/${enc(scope.locationId)}/grants`;
  },

  locationGrant(scope: LocationScope, grantId: string): string {
    return `${this.locationGrants(scope)}/${enc(grantId)}`;
  },

  /** The jobs, each with whether the caller may give it at this branch. */
  locationGrantRoles(scope: LocationScope): string {
    return `${OPERATIONS}/operations/tenants/${enc(scope.tenantId)}/brands/${enc(scope.brandId)}/locations/${enc(scope.locationId)}/grant-roles`;
  },

  /** The names of this branch and its brand. */
  locationGrantPlaces(scope: LocationScope): string {
    return `${OPERATIONS}/operations/tenants/${enc(scope.tenantId)}/brands/${enc(scope.brandId)}/locations/${enc(scope.locationId)}/grant-places`;
  },

  locationStaffInvitations(scope: LocationScope): string {
    return `${OPERATIONS}/operations/tenants/${enc(scope.tenantId)}/brands/${enc(scope.brandId)}/locations/${enc(scope.locationId)}/staff/invitations`;
  },

  brandGrants(tenantId: string, brandId: string): string {
    return `${OPERATIONS}/operations/tenants/${enc(tenantId)}/brands/${enc(brandId)}/grants`;
  },

  brandGrant(tenantId: string, brandId: string, grantId: string): string {
    return `${this.brandGrants(tenantId, brandId)}/${enc(grantId)}`;
  },

  brandGrantRoles(tenantId: string, brandId: string): string {
    return `${OPERATIONS}/operations/tenants/${enc(tenantId)}/brands/${enc(brandId)}/grant-roles`;
  },

  brandGrantPlaces(tenantId: string, brandId: string): string {
    return `${OPERATIONS}/operations/tenants/${enc(tenantId)}/brands/${enc(brandId)}/grant-places`;
  },

  brandStaffInvitations(tenantId: string, brandId: string): string {
    return `${OPERATIONS}/operations/tenants/${enc(tenantId)}/brands/${enc(brandId)}/staff/invitations`;
  },

  /** `StaffMemberController.listForBrand` — the people of one brand, for a holder of `staff.profile.read` there. */
  brandMembers(tenantId: string, brandId: string): string {
    return `${OPERATIONS}/operations/tenants/${enc(tenantId)}/brands/${enc(brandId)}/staff/members`;
  },

  /** `StaffMemberController.detailForLocation` — one person of one branch, in full. */
  locationMember(scope: LocationScope, memberId: string): string {
    return `${this.locationMembers(scope)}/${enc(memberId)}`;
  },

  /** `StaffMemberController.detailForBrand`. */
  brandMember(tenantId: string, brandId: string, memberId: string): string {
    return `${this.brandMembers(tenantId, brandId)}/${enc(memberId)}`;
  },

  /** `TelegramStaffLinkCodeController.listLinks` — already on the operations surface. */
  telegramStaffLinks(tenantId: string): string {
    return `${OPERATIONS}/tenants/${enc(tenantId)}/staff/telegram/links`;
  },

  /** `TelegramStaffLinkCodeController.unlink` — wave T20, an administrator's «Отвязать». */
  telegramStaffLink(tenantId: string, linkId: string): string {
    return `${this.telegramStaffLinks(tenantId)}/${enc(linkId)}`;
  },

  /**
   * `TelegramStaffLinkCodeController.issue` — wave T20's self-service card: a
   * staff member mints her own `/link <code>` command. Self-service by
   * construction (the server mints it for the caller, never for a subject
   * the caller names), so this needs no id in the path.
   */
  telegramStaffLinkCodes(tenantId: string): string {
    return `${OPERATIONS}/tenants/${enc(tenantId)}/staff/telegram/link-codes`;
  },

  /**
   * `TelegramStaffLinkCodeController.issueAtLocation` — the same self-service
   * code, checked at the caller's own branch. A brand or branch member holds
   * `integration.telegram-staff-link.issue` at their own scope, and a grant
   * covers only the routes whose path names its level (ADR 0025), so the
   * tenant-scope route above refuses them; this one is what they can reach.
   */
  telegramStaffLinkCodesAtLocation(scope: LocationScope): string {
    return `${OPERATIONS}/tenants/${enc(scope.tenantId)}/brands/${enc(scope.brandId)}/locations/${enc(scope.locationId)}/staff/telegram/link-codes`;
  },

  /** `OperationsBrandController.list` — reused from `settings-paths.ts`'s own tree; see this file's doc. */
  brands(tenantId: string): string {
    return `${OPERATIONS}/operations/tenants/${enc(tenantId)}/brands`;
  },

  /** `OperationsBrandController.locations` — one brand's branches. */
  brandLocations(tenantId: string, brandId: string): string {
    return `${this.brands(tenantId)}/${enc(brandId)}/locations`;
  },

  /**
   * `AuditController.operationsSearch`/`.operationsDetail` — 9.3's activity
   * log, added this wave on the `/api/v1/operations/**` prefix (the
   * pre-existing control-plane route this wave mirrors was never reachable
   * from this app's OpenAPI group; ADR 0057).
   */
  auditEvents(tenantId: string): string {
    return `${OPERATIONS}/operations/tenants/${enc(tenantId)}/audit-events`;
  },

  auditEvent(tenantId: string, eventId: string): string {
    return `${this.auditEvents(tenantId)}/${enc(eventId)}`;
  },

  /**
   * `ApprovalRequestController.operationsPending`/`.operationsDecide` — 9.4's
   * approvals worklist (wave 45), added on the `/api/v1/operations/**` prefix
   * for the same reason `auditEvents` was: the pre-existing control-plane
   * route (`ApprovalRequestController.pending`/`.decide`) serves HorecaOS
   * staff working a tenant on its behalf and was never reachable from this
   * app's OpenAPI group (ADR 0057). Both mappings call the same
   * `ApprovalDecisionService`, so nothing about who may decide what differs.
   */
  approvalRequests(tenantId: string): string {
    return `${OPERATIONS}/operations/tenants/${enc(tenantId)}/approval-requests`;
  },

  approvalDecision(tenantId: string, requestId: string): string {
    return `${this.approvalRequests(tenantId)}/${enc(requestId)}/decision`;
  },

  /** `ApprovalRequestController.decided` — Staff 9.4's decided-history read (ADR 0109). */
  approvalRequestsDecided(tenantId: string): string {
    return `${this.approvalRequests(tenantId)}/decided`;
  },

  /**
   * `GrantController.accessCheck` — Staff 9.5's "Проверка доступа" (ADR
   * 0109), deliberately on `/api/v1/tenants/**` rather than
   * `/control-plane/**`: see that method's own doc for why the latter would
   * be unreachable from this app's OpenAPI client.
   */
  accessCheck(tenantId: string): string {
    return `${OPERATIONS}/tenants/${enc(tenantId)}/access-check`;
  },

  /**
   * `StaffInvitationController.invite`/`.outstanding` — staff-and-access.md
   * §4, ADR 0116. `/api/v1/operations/**`, not `/control-plane/**`: this is
   * a tenant's own manager inviting a colleague, the same surface
   * `auditEvents`/`approvalRequests` above already use for that reason.
   */
  staffInvitations(tenantId: string): string {
    return `${OPERATIONS}/operations/tenants/${enc(tenantId)}/staff/invitations`;
  },

  /** `StaffInvitationController.resend` — staff-access-dialog's «Отправить повторно». */
  staffInvitationResend(tenantId: string, invitationId: string): string {
    return `${this.staffInvitations(tenantId)}/${enc(invitationId)}/resend`;
  },

  /** `StaffInvitationController.revoke` — staff-access-dialog's revoke. */
  staffInvitation(tenantId: string, invitationId: string): string {
    return `${this.staffInvitations(tenantId)}/${enc(invitationId)}`;
  },

  /**
   * `OperatorTodayCountsController.today` — Staff 9.2d's "how many orders
   * did this person take today", read from the person's own card.
   * `/api/v1/tenants/**`, not `/api/v1/operations/tenants/**`: it lives on
   * `ordering.web`'s own tenant-scoped `orders/**` tree — the same one
   * `OrderNumberLookupController` uses — not the operations-surface mirror
   * `auditEvents`/`approvalRequests` above need for their own history.
   */
  operatorTodayOrderCounts(tenantId: string, subject: string): string {
    return `${OPERATIONS}/tenants/${enc(tenantId)}/orders/operators/${enc(subject)}/today-counts`;
  },

  /**
   * `StaffMemberController` (ADR 0139) -- the tenant's own record of each
   * person. `/api/v1/operations/**`, tenant-wide: the People screen is
   * reached by the owner and the administrator, who hold `staff.profile.*`
   * at tenant scope. A branch has routes of its own ({@link
   * locationMembers}) because a branch manager's grant never covers a
   * tenant route (ADR 0025).
   */
  members(tenantId: string): string {
    return `${OPERATIONS}/operations/tenants/${enc(tenantId)}/staff/members`;
  },

  member(tenantId: string, memberId: string): string {
    return `${this.members(tenantId)}/${enc(memberId)}`;
  },

  /** `StaffMemberController.endEmployment` -- sets ENDED and revokes every job in the same act. */
  memberEndEmployment(tenantId: string, memberId: string): string {
    return `${this.member(tenantId, memberId)}/end-employment`;
  },

  /** `StaffMemberController.emergencyContactsForTenant`/`replaceEmergencyContactsForTenant` -- the read is audited. */
  memberEmergencyContacts(tenantId: string, memberId: string): string {
    return `${this.member(tenantId, memberId)}/emergency-contacts`;
  },

  /**
   * `StaffMemberController.listForLocation` -- the people who work at one
   * branch, for a holder of `staff.profile.read` at that branch. The colleague
   * picker of a branch's contact persons reads it.
   */
  locationMembers(scope: LocationScope): string {
    return `${OPERATIONS}/operations/tenants/${enc(scope.tenantId)}/brands/${enc(scope.brandId)}/locations/${enc(scope.locationId)}/staff/members`;
  },

  /**
   * `StaffSelfController` -- the caller's own record. There is no member id in
   * the path on purpose: the server resolves the row from the token's
   * subject, so no request can address anyone else's.
   */
  me(tenantId: string): string {
    return `${OPERATIONS}/operations/tenants/${enc(tenantId)}/staff/me`;
  },

  /** `StaffSelfController.setPhoto` -- the body is the image itself, at most 1 MiB. */
  mePhoto(tenantId: string): string {
    return `${this.me(tenantId)}/photo`;
  },

  /** `LocationContactPersonController` -- who to call about a branch (row 9.2b). */
  locationContactPersons(scope: LocationScope): string {
    return `${OPERATIONS}/operations/tenants/${enc(scope.tenantId)}/brands/${enc(scope.brandId)}/locations/${enc(scope.locationId)}/contact-persons`;
  },
} as const;

function enc(value: string): string {
  return encodeURIComponent(value);
}
