import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../../core/api/api-client';
import { command } from '../../../core/api/idempotency';
import { settingsPaths } from '../../../core/api/settings-paths';

export type AcceptanceMode = 'AUTO_CONFIRM' | 'RESTAURANT_APPROVAL';
export type ApprovalChannel = 'NONE' | 'HORECAOS_OPERATIONS' | 'POS' | 'EITHER';
export type ApprovalTimeoutAction = 'AUTO_REJECT' | 'AUTO_CONFIRM';

/** Mirrors uz.horecaos.platform.ordering.web.OrderAcceptancePolicyController.AcceptancePolicyResponse. */
export interface AcceptancePolicyResponse {
  readonly mode: AcceptanceMode;
  readonly approvalChannel: ApprovalChannel;
  readonly approvalTimeoutSeconds: number;
  readonly timeoutAction: ApprovalTimeoutAction;
  readonly rejectionReasonRequired: boolean;
  readonly notifyCustomerWhilePending: boolean;
  readonly isPlatformDefault: boolean;
  readonly policyId: string | null;
  readonly policyVersion: number;
}

export interface AuthorAcceptancePolicyFields {
  readonly mode: AcceptanceMode;
  readonly approvalChannel: ApprovalChannel;
  readonly approvalTimeoutSeconds: number;
  readonly timeoutAction: ApprovalTimeoutAction;
  readonly rejectionReasonRequired: boolean;
  readonly notifyCustomerWhilePending: boolean;
  readonly reason: string;
}

interface AuthorAcceptancePolicyRequest extends AuthorAcceptancePolicyFields {
  readonly brandId: string | null;
  readonly locationId: string | null;
}

/**
 * 10.3 Order policy, Card 1 (`OrderAcceptancePolicyController`, ADR 0002 /
 * ADR 0030). Cross-surface — see `settings-paths.ts`.
 *
 * <p>Wave P46 (gap map row `10.3b`): every method used to hard-code {@code
 * brandId: scope.brandId} from {@code CurrentLocation}'s always-concrete
 * scope, which is exactly what kept this card at BRAND resolution even
 * though the controller's own {@code scopeOf} already builds TENANT (neither
 * id), BRAND (brandId only) and LOCATION (both) — see that controller's own
 * doc. {@code brandId}/{@code locationId} are now independent, nullable
 * parameters the caller supplies from `SettingsScope`'s picker, matching the
 * shape every ConfigurationApi call already uses.
 */
@Injectable({ providedIn: 'root' })
export class OrderPolicyApi {
  private readonly api = inject(ApiClient);

  /**
   * Omit both ids for TENANT resolution, `brandId` alone for BRAND, both for
   * LOCATION — mirrors {@code OrderAcceptancePolicyController.effective}'s own rule.
   */
  async getEffective(
    tenantId: string,
    brandId: string | null,
    locationId: string | null,
  ): Promise<AcceptancePolicyResponse> {
    const params: Record<string, string> = {};
    if (brandId) {
      params['brandId'] = brandId;
    }
    if (locationId) {
      params['locationId'] = locationId;
    }
    const result = await firstValueFrom(
      this.api.get<AcceptancePolicyResponse>(this.path(tenantId), { params }),
    );
    return result.value;
  }

  async publish(
    tenantId: string,
    brandId: string | null,
    locationId: string | null,
    fields: AuthorAcceptancePolicyFields,
  ): Promise<AcceptancePolicyResponse> {
    const request: AuthorAcceptancePolicyRequest = { ...fields, brandId, locationId };
    return firstValueFrom(
      this.api.post<AuthorAcceptancePolicyRequest, AcceptancePolicyResponse>(
        this.path(tenantId),
        command(request),
      ),
    );
  }

  /** `settingsPaths.orderAcceptancePolicy` only ever reads `scope.tenantId`; brand/location travel as query params or body fields. */
  private path(tenantId: string): string {
    return settingsPaths.orderAcceptancePolicy({ tenantId, brandId: '', locationId: '' });
  }
}
