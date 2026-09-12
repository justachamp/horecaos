import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../../core/api/api-client';
import { settingsPaths } from '../../../core/api/settings-paths';

/** Mirrors `CustomerController.TenantErasureRequestResponse`. */
export interface TenantErasureRequest {
  readonly id: string;
  readonly customerAccountId: string;
  readonly status: 'PENDING' | 'COMPLETED' | 'CANCELLED';
  readonly requestedVia: 'STOREFRONT' | 'OPERATIONS';
  readonly requestedByActorType: string;
  readonly requestedByActorId: string;
  readonly requestedAt: string;
  readonly completedAt: string | null;
  readonly completedByActorId: string | null;
  readonly cancelledAt: string | null;
  readonly cancelledByActorId: string | null;
}

/** Mirrors `ConsentTypeController.ConsentTypeResponse`. */
export interface ConsentType {
  readonly id: string;
  readonly code: string;
  readonly labelRu: string;
  readonly labelUz: string;
  readonly labelEn: string;
  readonly description: string | null;
  readonly channelSpecific: boolean;
  readonly policyVersion: string;
  readonly active: boolean;
  readonly updatedAt: string;
}

/**
 * Settings 10.11's own two genuinely new reads (ADR 0109): the tenant-wide
 * DSAR worklist and the consent-type registry. Retention periods reuse
 * `ConfigurationApi` directly — they are ADR 0030 keys, not a bespoke table.
 */
@Injectable({ providedIn: 'root' })
export class DataPrivacyApi {
  private readonly api = inject(ApiClient);

  async erasureWorklist(
    tenantId: string,
    status?: 'PENDING' | 'COMPLETED' | 'CANCELLED',
  ): Promise<readonly TenantErasureRequest[]> {
    const result = await firstValueFrom(
      this.api.get<readonly TenantErasureRequest[]>(settingsPaths.erasureRequests(tenantId), {
        params: { status, limit: 200 },
      }),
    );
    return result.value ?? [];
  }

  async consentTypes(tenantId: string): Promise<readonly ConsentType[]> {
    const result = await firstValueFrom(
      this.api.get<readonly ConsentType[]>(settingsPaths.consentTypes(tenantId)),
    );
    return result.value ?? [];
  }
}
