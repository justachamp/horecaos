import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { LocationScope, operationsPaths } from '../../core/api/operations-paths';
import { RejectReasonOption } from './order-reject-reason-dialog';

/**
 * `GET .../orders/reject-reasons` — the platform's curated reject-reason list
 * (wave 24, V0119). Read-only, matching `OperationsOrderController.rejectReasons`:
 * platform reference data, the same eight reasons for every tenant, no
 * per-tenant authoring endpoint to fall back to.
 *
 * A separate small class rather than a method on {@link OrderActionsApi},
 * mirroring the same split `OrderCounts` already keeps between reads and
 * mutations of `orders.md`'s own endpoints.
 */
@Injectable({ providedIn: 'root' })
export class RejectReasonsApi {
  private readonly api = inject(ApiClient);

  async list(scope: LocationScope): Promise<readonly RejectReasonOption[]> {
    const result = await firstValueFrom(
      this.api.get<readonly RejectReasonOption[]>(operationsPaths.orderRejectReasons(scope)),
    );
    return result.value ?? [];
  }
}
