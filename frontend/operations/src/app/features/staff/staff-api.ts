import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { command } from '../../core/api/idempotency';
import { LocationScope } from '../../core/api/operations-paths';
import { staffPaths } from '../../core/api/staff-paths';
import { Scope } from './scope-coverage';
import { ManagedPlace, StaffReach } from './staff-reach';

/** `ResourceScope.ScopeType` — re-exported so callers do not reach into `operations-paths.ts` for it. */
export type ScopeType = 'PLATFORM' | 'TENANT' | 'BRAND' | 'LOCATION';

/**
 * Mirrors `uz.horecaos.platform.iam.application.GrantManagementService.GrantView`
 * (V0127 added `reason`, `validFrom`, `validUntil`, `revokedAt`, `revokedBy`,
 * `revokedReason` — the fields staff-and-access.md §2's row states, §3's
 * assignment cards and the restore action all read).
 */
export interface GrantView {
  readonly id: string;
  readonly principalSubject: string;
  readonly roleCode: string;
  readonly scopeType: ScopeType;
  readonly scopeId: string | null;
  readonly status: 'ACTIVE' | 'REVOKED';
  readonly grantedBy: string;
  readonly reason: string;
  readonly validFrom: string;
  readonly validUntil: string | null;
  readonly revokedAt: string | null;
  readonly revokedBy: string | null;
  readonly revokedReason: string | null;
}

/** Mirrors `GrantController.GrantRequest`. */
export interface GrantRequest {
  readonly principalSubject: string;
  readonly roleCode: string;
  readonly brandId?: string;
  readonly locationId?: string;
  readonly reason: string;
  readonly validUntil?: string;
}

/** Mirrors `GrantController.ReasonRequest`. */
export interface ReasonRequest {
  readonly reason: string;
}

/**
 * Mirrors `TenantRoleCatalog.RoleDescriptor` — the eight tenant-visible jobs, capability codes only.
 * A branch's own catalogue (`GrantableRole`, ADR 0103) is the same shape plus `grantable`.
 */
export interface RoleDescriptor {
  readonly code: string;
  readonly scopeType: ScopeType;
  readonly capabilities: readonly string[];
  /** Present only on a branch's or brand's own catalogue: whether the caller may give this job there. Absent means the company-wide catalogue. */
  readonly grantable?: boolean;
}

/** Mirrors `GrantManagementService.PlaceDirectory` — the names a branch manager's team view groups under. */
export interface PlaceDirectory {
  readonly brands: readonly BrandSummary[];
  readonly locations: readonly LocationSummary[];
}

/** Mirrors `StaffInvitationController.StaffInvitationRequest` (ADR 0116, staff-and-access.md §4). */
export interface StaffInvitationRequest {
  readonly firstName: string;
  readonly lastName: string;
  readonly phone: string;
  readonly email?: string;
  readonly roleCode: string;
  readonly brandId?: string;
  readonly locationId?: string;
  readonly reason: string;
  readonly validUntil?: string;
  readonly locale?: 'uz' | 'ru' | 'en';
}

/** Mirrors `StaffInvitationController.StaffInvitationCreatedResponse`. */
export interface StaffInvitationCreated {
  readonly invitationId: string;
  readonly principalSubject: string;
  readonly grantId: string;
  readonly inviteLink: string;
}

/** Mirrors `StaffInvitationService.Outstanding` — the People screen's «Приглашён» pill and filter. */
export interface StaffInvitationOutstanding {
  readonly invitationId: string;
  readonly principalSubject: string;
  readonly state: 'QUEUED' | 'SENT' | 'OPENED' | 'EXPIRED' | (string & {});
  readonly invitedAt: string;
}

/** Mirrors `TelegramStaffLinkService.StaffLinkView`. */
export interface TelegramStaffLinkView {
  readonly id: string;
  readonly principalSubject: string;
  readonly telegramUserId: number;
  readonly linkedAt: string;
}

/** Mirrors `TelegramStaffLinkCodeController.LinkCodeResponse`. */
export interface TelegramLinkCodeResponse {
  readonly code: string;
  readonly command: string;
}

/** Mirrors `TenantControlPlaneService.BrandView`, the fields this screen needs. */
export interface BrandSummary {
  readonly id: string;
  readonly displayName: string;
}

/** Mirrors `LocationsApi.LocationView`, the fields this screen needs. */
export interface LocationSummary {
  readonly id: string;
  readonly brandId: string;
  readonly displayName: string;
}

/** Where a job is given — resolved names, for the "Где работает" column and scope picker. */
export interface ScopeDirectory {
  readonly brands: readonly BrandSummary[];
  readonly locations: readonly LocationSummary[];
}

/** Mirrors `OperatorTodayCountsController.OperatorTodayCountsResponse` (Staff 9.2d). */
export interface OperatorTodayCounts {
  readonly createdCount: number;
  readonly acceptedCount: number;
  readonly businessDayFrom: string;
  readonly businessDayTo: string;
}

/**
 * The Staff section's one API seam: grants (Люди, Карточка), the role
 * catalogue (Должности), and staff Telegram links.
 */
@Injectable({ providedIn: 'root' })
export class StaffApi {
  private readonly api = inject(ApiClient);
  private readonly reach = inject(StaffReach);

  /**
   * What the last scoped list said about each grant, so a revoke can name the route
   * its own place belongs to. A revoke on the company-wide route takes only the id;
   * a branch's takes the branch, and a grant row carries only its own level's id.
   */
  private readonly grantPlaces = new Map<
    string,
    { scopeType: ScopeType; scopeId: string | null }
  >();
  /** Which brand each branch of the last scoped directory belongs to — a branch route names its brand too. */
  private readonly locationBrands = new Map<string, string>();

  /** Active grants only unless `includeInactive` — see V0127's own doc on why the default stayed active-only. */
  async listGrants(tenantId: string, includeInactive = false): Promise<readonly GrantView[]> {
    if (this.reach.scoped()) {
      return this.listGrantsWithin(includeInactive);
    }
    const result = await firstValueFrom(
      this.api.get<readonly GrantView[]>(staffPaths.grants(tenantId), {
        params: { includeInactive },
      }),
    );
    return result.value ?? [];
  }

  /**
   * @param correlationId Staff 9.3c: shared across a bulk fan-out — see
   *                       {@link ApiClient}'s `MutateOptions.correlationId` —
   *                       so N grants made under one operator action audit as
   *                       one action, not N. Omit for an ordinary single grant.
   */
  async grant(
    tenantId: string,
    request: GrantRequest,
    correlationId?: string,
  ): Promise<{ grantId: string }> {
    if (this.reach.scoped()) {
      return this.grantWithin(tenantId, request, correlationId);
    }
    return firstValueFrom(
      this.api.post<GrantRequest, { grantId: string }>(
        staffPaths.grants(tenantId),
        command(request),
        { correlationId },
      ),
    );
  }

  /** @param correlationId see {@link grant}'s own doc — the same bulk-fan-out case, for revoke. */
  async revoke(
    tenantId: string,
    grantId: string,
    reason: string,
    correlationId?: string,
  ): Promise<{ changed: boolean; outcome: string }> {
    const path = this.reach.scoped()
      ? this.scopedGrantPath(tenantId, grantId)
      : staffPaths.grant(tenantId, grantId);
    const response = await firstValueFrom(
      this.api.send<ReasonRequest, { changed: boolean; outcome: string }>(
        'DELETE',
        path,
        command({ reason }),
        { correlationId },
      ),
    );
    return response.body as { changed: boolean; outcome: string };
  }

  async roles(tenantId: string): Promise<readonly RoleDescriptor[]> {
    const place = this.reach.scoped() ? this.reach.places()[0] : undefined;
    const result = await firstValueFrom(
      this.api.get<readonly RoleDescriptor[]>(
        place ? this.placeRolesPath(place) : staffPaths.roles(tenantId),
      ),
    );
    return result.value ?? [];
  }

  async telegramLinks(tenantId: string): Promise<readonly TelegramStaffLinkView[]> {
    if (this.reach.scoped()) {
      // The company-wide link list is a tenant-scope read a branch's grant never covers.
      return [];
    }
    const result = await firstValueFrom(
      this.api.get<readonly TelegramStaffLinkView[]>(staffPaths.telegramStaffLinks(tenantId)),
    );
    return result.value ?? [];
  }

  /**
   * `TelegramStaffLinkCodeController.issue` — the self-service `/link <code>`
   * card (staff-and-access.md §10, operations-gap-map.md `9/X.1`). Mints a
   * code for the caller's own principal; there is no "issue for someone else".
   *
   * With a `location` the capability is checked at that branch — the only
   * route a brand or branch member's grant covers; without one it is checked
   * at tenant scope, which only a tenant-wide grant satisfies.
   */
  async issueTelegramLinkCode(
    tenantId: string,
    location: LocationScope | null = null,
  ): Promise<TelegramLinkCodeResponse> {
    return firstValueFrom(
      this.api.post<Record<string, never>, TelegramLinkCodeResponse>(
        location
          ? staffPaths.telegramStaffLinkCodesAtLocation(location)
          : staffPaths.telegramStaffLinkCodes(tenantId),
        command({}),
      ),
    );
  }

  /** `TelegramStaffLinkCodeController.unlink` — an administrator's «Отвязать» on the Безопасность tab. */
  async revokeTelegramLink(
    tenantId: string,
    linkId: string,
    reason: string,
  ): Promise<{ changed: boolean; outcome: string }> {
    const response = await firstValueFrom(
      this.api.send<ReasonRequest, { changed: boolean; outcome: string }>(
        'DELETE',
        staffPaths.telegramStaffLink(tenantId, linkId),
        command({ reason }),
      ),
    );
    return response.body as { changed: boolean; outcome: string };
  }

  /**
   * Every brand and location in the tenant, for the "Где работает" column and
   * the job dialog's scope picker. No single endpoint returns "every
   * location" — `OperationsBrandController.locations` is per-brand — so this
   * fans out to one call per brand, which is the tenant's own brand count
   * (single digits for the pilot's single-location tenants and not
   * meaningfully more for the multi-brand ones this wave does not target).
   */
  async scopeDirectory(tenantId: string): Promise<ScopeDirectory> {
    if (this.reach.scoped()) {
      return this.placesWithin();
    }
    const brandsResult = await firstValueFrom(
      this.api.get<readonly BrandSummary[]>(staffPaths.brands(tenantId)),
    );
    const brands = brandsResult.value ?? [];

    const perBrand = await Promise.all(
      brands.map((brand) =>
        firstValueFrom(
          this.api.get<readonly LocationSummary[]>(staffPaths.brandLocations(tenantId, brand.id)),
        ).then((result) => result.value ?? []),
      ),
    );

    return { brands, locations: perBrand.flat() };
  }

  /**
   * `StaffInvitationController.invite` — staff-and-access.md §4. Creates the
   * account, the membership and the grant, and returns the invite link once
   * (never stored, never fetchable again after this call — the manager
   * copies it now or resends later for a fresh one).
   */
  async invite(tenantId: string, request: StaffInvitationRequest): Promise<StaffInvitationCreated> {
    if (this.reach.scoped()) {
      return this.inviteWithin(tenantId, request);
    }
    return firstValueFrom(
      this.api.post<StaffInvitationRequest, StaffInvitationCreated>(
        staffPaths.staffInvitations(tenantId),
        command(request),
      ),
    );
  }

  /** `StaffInvitationController.resend` — a fresh link; the one already out stops working. */
  async resendStaffInvitation(
    tenantId: string,
    invitationId: string,
    reason: string,
  ): Promise<{ inviteLink: string }> {
    return firstValueFrom(
      this.api.post<ReasonRequest, { inviteLink: string }>(
        staffPaths.staffInvitationResend(tenantId, invitationId),
        command({ reason }),
      ),
    );
  }

  /** `StaffInvitationController.revoke` — cancels the invitation and revokes the job it was for. */
  async revokeStaffInvitation(
    tenantId: string,
    invitationId: string,
    reason: string,
  ): Promise<{ changed: boolean }> {
    const response = await firstValueFrom(
      this.api.send<ReasonRequest, { changed: boolean }>(
        'DELETE',
        staffPaths.staffInvitation(tenantId, invitationId),
        command({ reason }),
      ),
    );
    return response.body as { changed: boolean };
  }

  /** `StaffInvitationController.outstanding` — every invitation this tenant has open. */
  async staffInvitations(tenantId: string): Promise<readonly StaffInvitationOutstanding[]> {
    if (this.reach.scoped()) {
      // The open-invitation list is company-wide; a branch's has no list of its own yet.
      return [];
    }
    const result = await firstValueFrom(
      this.api.get<readonly StaffInvitationOutstanding[]>(staffPaths.staffInvitations(tenantId)),
    );
    return result.value ?? [];
  }

  /**
   * `OperatorTodayCountsController.today` — Staff 9.2d. Never throws for an
   * operator who has created or accepted nothing today: the endpoint answers
   * two zeroes, not a 404, because "nobody has taken an order yet" is not a
   * missing-resource condition.
   */
  async operatorTodayOrderCounts(tenantId: string, subject: string): Promise<OperatorTodayCounts> {
    const result = await firstValueFrom(
      this.api.get<OperatorTodayCounts>(staffPaths.operatorTodayOrderCounts(tenantId, subject)),
    );
    return result.value;
  }

  // ------------------------------------------------- a branch's or brand's own team (ADR 0103)

  /** Every grant lying in one of her places — one call per place, merged on the grant id. */
  private async listGrantsWithin(includeInactive: boolean): Promise<readonly GrantView[]> {
    // The directory first: it is what lets a later revoke name a branch's brand.
    await this.placesWithin();
    const lists = await Promise.all(
      this.reach.places().map((place) =>
        firstValueFrom(
          this.api.get<readonly GrantView[]>(this.placeGrantsPath(place), {
            params: { includeInactive },
          }),
        ).then((result) => result.value ?? []),
      ),
    );
    const byId = new Map<string, GrantView>();
    for (const grant of lists.flat()) {
      byId.set(grant.id, grant);
      this.grantPlaces.set(grant.id, { scopeType: grant.scopeType, scopeId: grant.scopeId });
    }
    return Array.from(byId.values());
  }

  private async placesWithin(): Promise<ScopeDirectory> {
    const directories = await Promise.all(
      this.reach
        .places()
        .map((place) =>
          firstValueFrom(this.api.get<PlaceDirectory>(this.placePlacesPath(place))).then(
            (result) => result.value,
          ),
        ),
    );
    const brands = new Map<string, BrandSummary>();
    const locations = new Map<string, LocationSummary>();
    for (const directory of directories) {
      directory.brands.forEach((brand) => brands.set(brand.id, brand));
      directory.locations.forEach((location) => {
        locations.set(location.id, location);
        this.locationBrands.set(location.id, location.brandId);
      });
    }
    return { brands: Array.from(brands.values()), locations: Array.from(locations.values()) };
  }

  /** A job at the brand or branch the request names, through that place's own route. */
  private async grantWithin(
    tenantId: string,
    request: GrantRequest,
    correlationId?: string,
  ): Promise<{ grantId: string }> {
    const { brandId, locationId, ...body } = request;
    if (locationId !== undefined && brandId !== undefined) {
      return firstValueFrom(
        this.api.post<typeof body, { grantId: string }>(
          staffPaths.locationGrants({ tenantId, brandId, locationId }),
          command(body),
          { correlationId },
        ),
      );
    }
    if (brandId !== undefined) {
      return firstValueFrom(
        this.api.post<typeof body, { grantId: string }>(
          staffPaths.brandGrants(tenantId, brandId),
          command(body),
          { correlationId },
        ),
      );
    }
    // A company-level job has no route a branch's grant covers; the server would refuse it
    // all the same, but asking for it would only teach her the 403.
    throw new Error('A branch manager gives a job at a branch or a brand, never company-wide');
  }

  private async inviteWithin(
    tenantId: string,
    request: StaffInvitationRequest,
  ): Promise<StaffInvitationCreated> {
    const { brandId, locationId, ...body } = request;
    if (locationId !== undefined && brandId !== undefined) {
      return firstValueFrom(
        this.api.post<typeof body, StaffInvitationCreated>(
          staffPaths.locationStaffInvitations({ tenantId, brandId, locationId }),
          command(body),
        ),
      );
    }
    if (brandId !== undefined) {
      return firstValueFrom(
        this.api.post<typeof body, StaffInvitationCreated>(
          staffPaths.brandStaffInvitations(tenantId, brandId),
          command(body),
        ),
      );
    }
    throw new Error('A branch manager invites into a branch or a brand, never company-wide');
  }

  /** The revoke route of the place a grant itself lies at, which her own grant covers by construction. */
  private scopedGrantPath(tenantId: string, grantId: string): string {
    const remembered = this.grantPlaces.get(grantId);
    if (remembered?.scopeType === 'LOCATION' && remembered.scopeId !== null) {
      const brandId = this.locationBrands.get(remembered.scopeId);
      if (brandId !== undefined) {
        return staffPaths.locationGrant(
          { tenantId, brandId, locationId: remembered.scopeId },
          grantId,
        );
      }
    }
    if (remembered?.scopeType === 'BRAND' && remembered.scopeId !== null) {
      return staffPaths.brandGrant(tenantId, remembered.scopeId, grantId);
    }
    // Not seen in a list this session: fall back to her first place and let the server decide.
    const place = this.reach.places()[0];
    return place.locationId === null
      ? staffPaths.brandGrant(tenantId, place.brandId, grantId)
      : staffPaths.locationGrant(
          { tenantId, brandId: place.brandId, locationId: place.locationId },
          grantId,
        );
  }

  private placeGrantsPath(place: ManagedPlace): string {
    return place.locationId === null
      ? staffPaths.brandGrants(place.tenantId, place.brandId)
      : staffPaths.locationGrants(placeScope(place, place.locationId));
  }

  private placeRolesPath(place: ManagedPlace): string {
    return place.locationId === null
      ? staffPaths.brandGrantRoles(place.tenantId, place.brandId)
      : staffPaths.locationGrantRoles(placeScope(place, place.locationId));
  }

  private placePlacesPath(place: ManagedPlace): string {
    return place.locationId === null
      ? staffPaths.brandGrantPlaces(place.tenantId, place.brandId)
      : staffPaths.locationGrantPlaces(placeScope(place, place.locationId));
  }
}

function placeScope(place: ManagedPlace, locationId: string): LocationScope {
  return { tenantId: place.tenantId, brandId: place.brandId, locationId };
}

/** Builds a `Scope` from a role's level and the picked brand/location, for `scope-coverage.ts` checks. */
export function scopeFor(
  scopeType: ScopeType,
  tenantId: string,
  brandId: string | null,
  locationId: string | null,
): Scope {
  switch (scopeType) {
    case 'PLATFORM':
      return { type: 'PLATFORM', tenantId: null, brandId: null, locationId: null };
    case 'TENANT':
      return { type: 'TENANT', tenantId, brandId: null, locationId: null };
    case 'BRAND':
      return { type: 'BRAND', tenantId, brandId, locationId: null };
    case 'LOCATION':
      return { type: 'LOCATION', tenantId, brandId, locationId };
  }
}
