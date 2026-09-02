import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../../core/api/api-client';
import { command } from '../../../core/api/idempotency';
import { LocationScope } from '../../../core/api/operations-paths';
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

export interface AuthorAcceptancePolicyRequest {
  readonly brandId?: string;
  readonly locationId?: string;
  readonly mode: AcceptanceMode;
  readonly approvalChannel: ApprovalChannel;
  readonly approvalTimeoutSeconds: number;
  readonly timeoutAction: ApprovalTimeoutAction;
  readonly rejectionReasonRequired: boolean;
  readonly notifyCustomerWhilePending: boolean;
  readonly reason: string;
}

/**
 * 10.3 Order policy, Card 1 (`OrderAcceptancePolicyController`, ADR 0002 /
 * ADR 0030). Cross-surface — see `settings-paths.ts`.
 */
@Injectable({ providedIn: 'root' })
export class OrderPolicyApi {
  private readonly api = inject(ApiClient);

  /** Effective at brand scope — the level `docs/operations-spec/settings.md` §10.3 edits by default. */
  async getEffective(scope: LocationScope): Promise<AcceptancePolicyResponse> {
    const result = await firstValueFrom(
      this.api.get<AcceptancePolicyResponse>(settingsPaths.orderAcceptancePolicy(scope), {
        params: { brandId: scope.brandId },
      }),
    );
    return result.value;
  }

  async publish(
    scope: LocationScope,
    request: Omit<AuthorAcceptancePolicyRequest, 'brandId'>,
  ): Promise<AcceptancePolicyResponse> {
    return firstValueFrom(
      this.api.post<AuthorAcceptancePolicyRequest, AcceptancePolicyResponse>(
        settingsPaths.orderAcceptancePolicy(scope),
        command({ ...request, brandId: scope.brandId }),
      ),
    );
  }
}
