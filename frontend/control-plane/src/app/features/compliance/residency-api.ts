import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { PendingApprovalResponse } from '../access/access-api';

/** A country the platform trades in, with what a tenant there starts from. */
export interface MarketView {
  readonly code: string;
  readonly name: string;
  readonly defaultCurrency: string;
  readonly defaultTimezone: string;
}

/** Where one tenant trades and what kind of business it is. */
export interface TenantProfileView {
  readonly tenantId: string;
  readonly slug: string;
  readonly displayName: string;
  readonly status: string;
  readonly countryCode: string;
  readonly businessType: string;
  readonly defaultCurrency: string;
  readonly defaultTimezone: string;
}

export interface ResidencyView {
  readonly hostingCountry: string;
  readonly markets: readonly MarketView[];
  readonly tenants: readonly TenantProfileView[];
}

/** What a change of country did. */
export interface CountryChangeView {
  readonly status: 'CHANGED' | 'AWAITING_APPROVAL' | 'DECLINED' | 'UNCHANGED' | (string & {});
  readonly approvalRequestId: string | null;
}

/** A kind of business, its usual handovers, and how many tenants are it. */
export interface BusinessTypeView {
  readonly code: string;
  readonly handovers: readonly string[];
  readonly kitchenDisplay: boolean;
  readonly tenants: number;
}

/**
 * A platform decision waiting for a second signature.
 *
 * `tenantId` is null when the request is itself PLATFORM-scoped: a wallet
 * correction, bonus grant, refund or deposit reversal is HorecaOS's own
 * decision about a tenant's account rather than the tenant's, so it is raised
 * above every tenant's queue and carries no tenant at all (ADR 0095). Such a
 * row is decided through `decidePlatform`, not through a tenant's route.
 */
export interface PlatformPendingApproval {
  readonly tenantId: string | null;
  readonly request: PendingApprovalResponse;
}

/**
 * Where tenants trade, what kind of business each is, and the platform
 * decisions waiting for a second signature.
 */
@Injectable({ providedIn: 'root' })
export class ResidencyApi {
  private readonly api = inject(ApiClient);

  async residency(): Promise<ResidencyView> {
    return firstValueFrom(this.api.get<ResidencyView>('/api/v1/control-plane/residency'));
  }

  /** The first call raises the approval; the same call after it is approved makes the change. */
  async changeCountry(tenantId: string, countryCode: string, reason: string): Promise<CountryChangeView> {
    return firstValueFrom(
      this.api.post<CountryChangeView>(`/api/v1/control-plane/tenants/${tenantId}/country-change`, {
        countryCode,
        reason,
      }),
    );
  }

  async businessTypes(): Promise<BusinessTypeView[]> {
    return firstValueFrom(this.api.get<BusinessTypeView[]>('/api/v1/control-plane/business-types'));
  }

  async setBusinessType(tenantId: string, businessType: string, reason: string): Promise<void> {
    await firstValueFrom(
      this.api.put<void>(`/api/v1/control-plane/tenants/${tenantId}/business-type`, { businessType, reason }),
    );
  }

  async platformApprovals(): Promise<PlatformPendingApproval[]> {
    return firstValueFrom(this.api.get<PlatformPendingApproval[]>('/api/v1/control-plane/approval-requests'));
  }

  /**
   * Decides a request that belongs to no tenant. The tenant routes are keyed
   * on the tenant and cannot reach one; this one cannot reach a tenant's own.
   */
  async decidePlatform(
    requestId: string,
    decision: 'APPROVE' | 'DECLINE',
    reason: string,
  ): Promise<{ id: string; actionCode: string; status: string }> {
    return firstValueFrom(
      this.api.post<{ id: string; actionCode: string; status: string }>(
        `/api/v1/control-plane/approval-requests/${requestId}/decision`,
        { decision, reason },
      ),
    );
  }
}
