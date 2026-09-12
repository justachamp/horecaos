import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from './api-client';
import { Money } from '../format/money';

/** Mirrors `OrderNumberLookupController.OrderNumberMatchResponse`. */
export interface OrderNumberMatchView {
  readonly orderId: string;
  readonly publicOrderNumber: string;
  readonly brandId: string;
  readonly locationId: string;
  readonly status: string;
  readonly total: Money;
  readonly createdAt: string;
}

function encode(value: string): string {
  return encodeURIComponent(value);
}

/**
 * Resolving the number an operator actually has to the order(s) it names
 * (`OrderNumberLookupController`, ADR 0019).
 *
 * Lives in `core/api` rather than under `features/finance` because it is not
 * a finance read — `public_order_number` is an ordering concept — and
 * `payments-page` and the fiscal queue are only its first two callers.
 *
 * **Why a list.** `uq_order_number` is `(tenant_id, location_id,
 * public_order_number)`, not `(tenant_id, public_order_number)`, and the
 * counter behind it resets every business day per location. Two branches can
 * hand out "0042" the same afternoon, so a caller with only the number has to
 * be shown every candidate and pick — the same shape `payments-page`'s own
 * candidate list already uses for an ambiguous phone lookup elsewhere in this
 * app.
 */
@Injectable({ providedIn: 'root' })
export class OrderLookupApi {
  private readonly api = inject(ApiClient);

  async byNumber(
    tenantId: string,
    publicOrderNumber: string,
  ): Promise<readonly OrderNumberMatchView[]> {
    const result = await firstValueFrom(
      this.api.get<{ count: number; matches: readonly OrderNumberMatchView[] }>(
        `/api/v1/tenants/${encode(tenantId)}/orders/by-number`,
        { params: { publicOrderNumber } },
      ),
    );
    return result.value.matches;
  }
}
