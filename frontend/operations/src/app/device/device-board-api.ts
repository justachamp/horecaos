import { Injectable, inject } from '@angular/core';

import { newIdempotencyKey } from '../core/api/idempotency';
import { LocationScope, operationsPaths } from '../core/api/operations-paths';
import type { BoardResponse, ItemResponse } from '../features/kitchen/kitchen-api';
import { environment } from '../../environments/environment';
import { DeviceSession } from './device-session';

export class DeviceBoardError extends Error {
  constructor(
    readonly status: number,
    message: string,
  ) {
    super(message);
  }
}

/**
 * The two `KitchenBoardController` endpoints an enrolled ADR 0079 device
 * actually calls — `kitchen.ticket.read` (the board) and
 * `kitchen.ticket.advance` (start/ready a line) — over a plain `fetch`
 * carrying only {@link DeviceSession}'s own bearer, never
 * `core/api/api-client.ts`'s `HttpClient`. See `device-session.ts`'s own
 * doc for why that boundary is deliberate.
 *
 * Path builders are reused from `core/api/operations-paths.ts` — pure
 * string functions with no dependency on `ApiClient` — so this file and the
 * staff console's own `kitchen-api.ts` cannot silently disagree about where
 * an endpoint lives.
 */
@Injectable({ providedIn: 'root' })
export class DeviceBoardApi {
  private readonly session = inject(DeviceSession);

  async board(scope: LocationScope): Promise<BoardResponse> {
    return this.authorizedFetch<BoardResponse>(
      `${operationsPaths.kitchenTickets(scope)}?stream=live&limit=50`,
      'GET',
    );
  }

  start(scope: LocationScope, itemId: string): Promise<ItemResponse> {
    return this.authorizedFetch<ItemResponse>(
      operationsPaths.kitchenTicketItemStart(scope, itemId),
      'POST',
    );
  }

  ready(scope: LocationScope, itemId: string): Promise<ItemResponse> {
    return this.authorizedFetch<ItemResponse>(
      operationsPaths.kitchenTicketItemReady(scope, itemId),
      'POST',
    );
  }

  private async authorizedFetch<T>(path: string, method: 'GET' | 'POST'): Promise<T> {
    const token = await this.session.accessToken();
    const headers: Record<string, string> = { Authorization: `Bearer ${token}` };
    if (method === 'POST') {
      headers['Content-Type'] = 'application/json';
      headers['Idempotency-Key'] = newIdempotencyKey();
    }
    const response = await fetch(`${environment.apiBaseUrl}${path}`, {
      method,
      headers,
      body: method === 'POST' ? '{}' : undefined,
    });
    if (!response.ok) {
      throw new DeviceBoardError(
        response.status,
        `Kitchen board request failed (HTTP ${response.status})`,
      );
    }
    return response.json();
  }
}
