import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';

/**
 * Wave 9 w4-reports-distance-crm (7.2a): the console order log's CRM columns —
 * customer, operator, courier — from `GET /api/v1/tenants/{tenantId}/orders/crm-log`
 * (`Capability.ORDER_READ`, `ordering` module). Deliberately not part of
 * {@link ../../core/api/reports-paths | reportsPaths}/`ReportingApi`: that
 * class's own doc says it forwards nothing but `reporting`'s own read, and this
 * endpoint is the other half precisely because `reporting` carries no PERSONAL
 * field at all (ADR 0029) — see `OrderCrmLogController`'s own doc on the
 * server. The console joins this response to `GET .../reporting/orders`'s own
 * rows by `orderId`, in the browser, never by combining the two requests into
 * one.
 *
 * <p>The cursor params (`afterOccurredAt`/`afterOrderId`) and the `maybeMore`
 * response shape match `ReportingApi.orders` exactly, so `order-reports-page.ts`
 * pages both reads from the same cursor.
 */
function crmLogPath(tenantId: string): string {
  return `/api/v1/tenants/${encodeURIComponent(tenantId)}/orders/crm-log`;
}

export interface CrmLogRowResponse {
  readonly orderId: string;
  readonly occurredAt: string;
  readonly locationId: string;
  readonly customerType: string;
  readonly anonymized: boolean;
  /** Full name, decrypted; null for a guest order or one with no snapshot. */
  readonly customerName: string | null;
  /** Masked server-side (orders.md §1.5) — never the plaintext phone. */
  readonly customerPhone: string | null;
  readonly operatorPrincipalId: string;
  /** The courier's non-PII handle ("K-014"), never their decrypted name. */
  readonly courierDisplayReference: string | null;
}

export interface CrmLogListResponse {
  readonly rows: readonly CrmLogRowResponse[];
  readonly maybeMore: boolean;
}

export interface CrmLogParams {
  readonly from: string;
  readonly to: string;
  readonly locationId?: readonly string[];
  readonly limit?: number;
  readonly afterOccurredAt?: string;
  readonly afterOrderId?: string;
}

@Injectable({ providedIn: 'root' })
export class OrderCrmLogApi {
  private readonly api = inject(ApiClient);

  async log(tenantId: string, params: CrmLogParams): Promise<CrmLogListResponse> {
    const result = await firstValueFrom(
      this.api.get<CrmLogListResponse>(crmLogPath(tenantId), {
        params: {
          from: params.from,
          to: params.to,
          locationId: params.locationId,
          limit: params.limit,
          afterOccurredAt: params.afterOccurredAt,
          afterOrderId: params.afterOrderId,
        },
      }),
    );
    return result.value;
  }
}
