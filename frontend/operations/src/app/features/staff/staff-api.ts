import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { command } from '../../core/api/idempotency';
import { staffPaths } from '../../core/api/staff-paths';
import { Scope } from './scope-coverage';

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

/** Mirrors `TenantRoleCatalog.RoleDescriptor` — the eight tenant-visible jobs, capability codes only. */
export interface RoleDescriptor {
  readonly code: string;
  readonly scopeType: ScopeType;
  readonly capabilities: readonly string[];
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

  /** Active grants only unless `includeInactive` — see V0127's own doc on why the default stayed active-only. */
  async listGrants(tenantId: string, includeInactive = false): Promise<readonly GrantView[]> {
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
    const response = await firstValueFrom(
      this.api.send<ReasonRequest, { changed: boolean; outcome: string }>(
        'DELETE',
        staffPaths.grant(tenantId, grantId),
        command({ reason }),
        { correlationId },
      ),
    );
    return response.body as { changed: boolean; outcome: string };
  }

  async roles(tenantId: string): Promise<readonly RoleDescriptor[]> {
    const result = await firstValueFrom(
      this.api.get<readonly RoleDescriptor[]>(staffPaths.roles(tenantId)),
    );
    return result.value ?? [];
  }

  async telegramLinks(tenantId: string): Promise<readonly TelegramStaffLinkView[]> {
    const result = await firstValueFrom(
      this.api.get<readonly TelegramStaffLinkView[]>(staffPaths.telegramStaffLinks(tenantId)),
    );
    return result.value ?? [];
  }

  /**
   * `TelegramStaffLinkCodeController.issue` — the self-service `/link <code>`
   * card (staff-and-access.md §10, operations-gap-map.md `9/X.1`). Mints a
   * code for the caller's own principal; there is no "issue for someone else".
   */
  async issueTelegramLinkCode(tenantId: string): Promise<TelegramLinkCodeResponse> {
    return firstValueFrom(
      this.api.post<Record<string, never>, TelegramLinkCodeResponse>(
        staffPaths.telegramStaffLinkCodes(tenantId),
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
