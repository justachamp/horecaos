import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { Page } from '../../core/api/page';

/** PlatformGrantAuthority.PlatformGrantView. */
export interface PlatformGrantView {
  readonly id: string;
  readonly principalSubject: string;
  readonly roleCode: string;
  readonly status: string;
  readonly grantedBy: string;
}

/** PlatformGrantController.PlatformGrantResponse. */
export interface PlatformGrantResponse {
  readonly outcome: 'GRANTED' | 'REVOKED' | 'NO_CHANGE' | 'AWAITING_APPROVAL' | (string & {});
  readonly grantId: string | null;
  readonly approvalRequestId: string | null;
}

/** GrantManagementService.GrantView. */
export interface TenantGrantView {
  readonly id: string;
  readonly principalSubject: string;
  readonly roleCode: string;
  readonly scopeType: string;
  readonly scopeId: string;
  readonly status: string;
  readonly grantedBy: string;
}

/** ApprovalRequestController.PendingApprovalResponse. */
export interface PendingApprovalResponse {
  readonly id: string;
  readonly actionCode: string;
  readonly parametersHash: string;
  readonly scopeType: string;
  readonly scopeId: string;
  readonly thresholdDescription: string;
  readonly policyVersion: number;
  readonly requiredApproverCapability: string;
  readonly requestedBy: string;
  readonly requestedAt: string;
  readonly expiresAt: string;
  readonly mayDecide: boolean;
}

/** CapabilityRegistryController.CapabilityDescriptor. */
export interface CapabilityDescriptor {
  readonly code: string;
  readonly resourceType: string;
  readonly action: string;
}

/** AuditQueryService.AuditEventView. */
export interface AuditEventView {
  readonly id: string;
  readonly recordedAt: string;
  readonly tenantId: string;
  readonly auditClass: string;
  readonly actionCode: string;
  readonly actorType: string;
  readonly actorSubject: string;
  readonly actorDisplay: string | null;
  readonly scopeType: string;
  readonly scopeId: string | null;
  readonly targetType: string | null;
  readonly targetId: string | null;
  readonly outcome: string;
  readonly reason: string | null;
  readonly capabilityUsed: string | null;
}

/**
 * IA §7 Access & security -- `PlatformGrantController`, `GrantController`,
 * `ApprovalRequestController`, `CapabilityRegistryController`,
 * `AuditController` composed in one place.
 *
 * 7.1 Staff & roles folds in the checker half of the maker-checker journey
 * (`ApprovalRequestController`'s pending queue and decision) rather than a
 * separate "Approvals" screen: 6.5 Approvals is wave 2 for the breadth this
 * IA row does not cover (residency change, bulk export, retention override),
 * but the pending-approval queue itself is exactly what "roles, tenant
 * scoping" already administers -- granting a role IS the maker action this
 * queue's checker half completes, and this wave's exit criterion needs both
 * reachable from one screen.
 */
@Injectable({ providedIn: 'root' })
export class AccessApi {
  private readonly api = inject(ApiClient);

  async listPlatformGrants(): Promise<PlatformGrantView[]> {
    return firstValueFrom(this.api.get<PlatformGrantView[]>('/api/v1/control-plane/grants'));
  }

  async grantPlatform(
    principalSubject: string,
    roleCode: string,
    reason: string,
    validUntil?: string,
  ): Promise<PlatformGrantResponse> {
    return firstValueFrom(
      this.api.post<PlatformGrantResponse>('/api/v1/control-plane/grants', {
        principalSubject,
        roleCode,
        reason,
        validUntil,
      }),
    );
  }

  async revokePlatformGrant(grantId: string, reason: string): Promise<PlatformGrantResponse> {
    return firstValueFrom(
      this.api.delete<PlatformGrantResponse>(`/api/v1/control-plane/grants/${grantId}`, { reason }),
    );
  }

  async listTenantGrants(tenantId: string): Promise<TenantGrantView[]> {
    return firstValueFrom(
      this.api.get<TenantGrantView[]>(`/api/v1/control-plane/tenants/${tenantId}/grants`),
    );
  }

  async grantTenant(
    tenantId: string,
    principalSubject: string,
    roleCode: string,
    reason: string,
    brandId?: string,
    locationId?: string,
  ): Promise<{ grantId: string }> {
    return firstValueFrom(
      this.api.post<{ grantId: string }>(`/api/v1/control-plane/tenants/${tenantId}/grants`, {
        principalSubject,
        roleCode,
        brandId,
        locationId,
        reason,
      }),
    );
  }

  async pendingApprovals(tenantId: string): Promise<Page<PendingApprovalResponse>> {
    return firstValueFrom(
      this.api.getPage<PendingApprovalResponse>(
        `/api/v1/control-plane/tenants/${tenantId}/approval-requests`,
      ),
    );
  }

  async decide(
    tenantId: string,
    requestId: string,
    decision: 'APPROVE' | 'DECLINE',
    reason: string,
  ): Promise<{ id: string; actionCode: string; status: string }> {
    return firstValueFrom(
      this.api.post<{ id: string; actionCode: string; status: string }>(
        `/api/v1/control-plane/tenants/${tenantId}/approval-requests/${requestId}/decision`,
        { decision, reason },
      ),
    );
  }

  async capabilityRegistry(): Promise<CapabilityDescriptor[]> {
    return firstValueFrom(
      this.api.get<CapabilityDescriptor[]>('/api/v1/control-plane/capabilities'),
    );
  }

  async auditEvents(tenantId: string): Promise<Page<AuditEventView>> {
    return firstValueFrom(
      this.api.getPage<AuditEventView>(`/api/v1/control-plane/tenants/${tenantId}/audit-events`),
    );
  }
}
