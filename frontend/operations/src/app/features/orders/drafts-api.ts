import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { LocationScope, operationsPaths } from '../../core/api/operations-paths';

/** Mirrors `OperationsOrderController.DraftCartResponse` (IA 1.4). */
export interface DraftCartResponse {
  readonly cartId: string;
  readonly createdAt: string;
  readonly channelId: string;
  readonly locationId: string;
  readonly customerAccountId?: string | null;
  readonly guestReferenceHash?: string | null;
  readonly expiresAt: string;
  /** `ACTIVE` | `EXPIRED` | `ABANDONED`. */
  readonly status: string;
  readonly lineCount: number;
}

export interface DraftsQuery {
  readonly from?: string;
  readonly to?: string;
  readonly channelId?: string;
}

/**
 * IA 1.4 — Drafts and abandoned carts. `OperationsOrderController.drafts`,
 * this wave's new read over `JdbcCartStore.listDrafts`.
 */
@Injectable({ providedIn: 'root' })
export class DraftsApi {
  private readonly api = inject(ApiClient);

  async list(scope: LocationScope, query: DraftsQuery = {}): Promise<readonly DraftCartResponse[]> {
    const result = await firstValueFrom(
      this.api.get<readonly DraftCartResponse[]>(operationsPaths.orderDrafts(scope), {
        params: {
          ...(query.from ? { from: query.from } : {}),
          ...(query.to ? { to: query.to } : {}),
          ...(query.channelId ? { channelId: query.channelId } : {}),
        },
      }),
    );
    return result.value ?? [];
  }
}
