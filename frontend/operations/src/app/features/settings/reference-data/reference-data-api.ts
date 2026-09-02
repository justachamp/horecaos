import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../../core/api/api-client';
import { command } from '../../../core/api/idempotency';
import { LocationScope } from '../../../core/api/operations-paths';
import { settingsPaths } from '../../../core/api/settings-paths';

export type OutcomeReasonKind = 'CANCELLATION' | 'COMPLETION';
export type StockDisposition = 'RELEASE' | 'RETURN_TO_STOCK' | 'WRITE_OFF' | 'NO_EFFECT';
export type LiabilityParty = 'TENANT' | 'CUSTOMER' | 'COURIER_PARTNER' | 'PLATFORM';
export type CustomerRefund = 'FULL' | 'NONE' | 'DISCRETIONARY';

/** Mirrors uz.horecaos.platform.ordering.web.OrderOutcomeReasonController.ReasonResponse. */
export interface ReasonResponse {
  readonly id: string;
  readonly kind: OutcomeReasonKind;
  readonly systemCategory: string;
  readonly internalName: string;
  readonly stockDisposition: StockDisposition | null;
  readonly liabilityParty: LiabilityParty | null;
  readonly customerRefund: CustomerRefund | null;
  readonly allowedFulfillmentModes: readonly string[] | null;
  readonly customerTexts: Readonly<Record<string, string>>;
  readonly status: string;
  readonly version: number;
  readonly updatedAt: string;
}

export interface ReasonRequest {
  readonly kind: OutcomeReasonKind;
  readonly systemCategory: string;
  readonly internalName: string;
  readonly stockDisposition?: StockDisposition;
  readonly liabilityParty?: LiabilityParty;
  readonly customerRefund?: CustomerRefund;
  readonly allowedFulfillmentModes?: readonly string[];
  readonly customerTexts: Readonly<Record<string, string>>;
}

/**
 * 10.10 Reference data — the cancellation/completion reason registry
 * (`OrderOutcomeReasonController`, ADR 0039). Cross-surface — see
 * `settings-paths.ts`'s own doc comment.
 */
@Injectable({ providedIn: 'root' })
export class ReferenceDataApi {
  private readonly api = inject(ApiClient);

  async list(scope: LocationScope, kind: OutcomeReasonKind): Promise<readonly ReasonResponse[]> {
    const result = await firstValueFrom(
      this.api.get<readonly ReasonResponse[]>(settingsPaths.orderOutcomeReasons(scope), {
        params: { kind, activeOnly: true },
      }),
    );
    return result.value ?? [];
  }

  async categories(scope: LocationScope, kind: OutcomeReasonKind): Promise<readonly string[]> {
    const result = await firstValueFrom(
      this.api.get<readonly string[]>(settingsPaths.orderOutcomeReasonCategories(scope), {
        params: { kind },
      }),
    );
    return result.value ?? [];
  }

  async create(scope: LocationScope, request: ReasonRequest): Promise<string> {
    const response = await firstValueFrom(
      this.api.post<ReasonRequest, { id: string }>(
        settingsPaths.orderOutcomeReasons(scope),
        command(request),
      ),
    );
    return response.id;
  }

  async archive(scope: LocationScope, reasonId: string, expectedVersion: number): Promise<void> {
    await firstValueFrom(
      this.api.send<null, void>(
        'DELETE',
        settingsPaths.orderOutcomeReason(scope, reasonId),
        command(null),
        {
          expectedVersion,
        },
      ),
    );
  }
}
