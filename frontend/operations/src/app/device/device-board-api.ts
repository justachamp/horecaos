import { Injectable, inject } from '@angular/core';

import { newIdempotencyKey } from '../core/api/idempotency';
import { LocationScope, operationsPaths } from '../core/api/operations-paths';
import type {
  BoardResponse,
  ItemResponse,
  VduBoardResponse,
} from '../features/kitchen/kitchen-api';
import { environment } from '../../environments/environment';
import { DeviceProfile, toDeviceProfile } from './device-profile';
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
 * Every endpoint an enrolled kitchen device calls, over a plain `fetch`
 * carrying only {@link DeviceSession}'s own bearer, never
 * `core/api/api-client.ts`'s `HttpClient`. See `device-session.ts`'s own
 * doc for why that boundary is deliberate.
 *
 * A touch KDS (ADR 0079) calls `kitchen.ticket.read` (the board) and
 * `kitchen.ticket.advance` (start/ready a line). A wall display (ADR 0151)
 * calls exactly two things and nothing else: {@link me}, its own record, and
 * {@link vdu}, the projection, which carries the lateness policy it colours
 * from. Nothing here reads the station list, `order.read` or the push stream:
 * a wall holds one capability, so a read added to it later fails a spec
 * instead of failing quietly on a 403.
 *
 * Path builders are reused from `core/api/operations-paths.ts` — pure
 * string functions with no dependency on `ApiClient` — so this file and the
 * staff console's own `kitchen-api.ts` cannot silently disagree about where
 * an endpoint lives.
 */
@Injectable({ providedIn: 'root' })
export class DeviceBoardApi {
  private readonly session = inject(DeviceSession);

  /**
   * The calling device's own record (`GET /api/v1/devices/me`): its branch and zone and, for a wall,
   * its station. Throws {@link DeviceBoardError} on a refusal and when the body is not the record.
   */
  async me(): Promise<DeviceProfile> {
    const body = await this.authorizedFetch<unknown>(operationsPaths.deviceSelf(), 'GET');
    const profile = toDeviceProfile(body);
    if (!profile) {
      throw new DeviceBoardError(502, 'The device record was not understood');
    }
    return profile;
  }

  /**
   * The VDU projection. No station parameter: for a wall the server applies the station it holds for the
   * device and ignores a request's, so the URL cannot repoint a wall (ADR 0151).
   */
  vdu(scope: LocationScope): Promise<VduBoardResponse> {
    return this.authorizedFetch<VduBoardResponse>(operationsPaths.kitchenVdu(scope), 'GET');
  }

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
