import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { LocationScope, operationsPaths } from '../../core/api/operations-paths';

/** One bar of {@link MyWorkChannelMixResponse.channelMix} — mirrors `OrderMixSliceResponse`. */
export interface MyWorkChannelMixSlice {
  readonly key: string;
  readonly orders: number;
}

/** `GET .../orders/my-work/channel-mix` (IA 0.2a) — mirrors `OperationsOrderController.MyWorkChannelMixResponse`. */
export interface MyWorkChannelMixResponse {
  readonly periodFrom: string;
  readonly periodTo: string;
  readonly channelMix: readonly MyWorkChannelMixSlice[];
}

/**
 * IA 0.2a's one call: the signed-in operator's own orders today, by sales
 * channel. Self-scoped server-side (`MyWorkQueryService`) — this class sends
 * no actor identifier of any kind, because the whole point of the endpoint it
 * calls is that it never needs one.
 */
@Injectable({ providedIn: 'root' })
export class MyWorkApi {
  private readonly api = inject(ApiClient);

  async channelMix(scope: LocationScope): Promise<MyWorkChannelMixResponse> {
    const result = await firstValueFrom(
      this.api.get<MyWorkChannelMixResponse>(operationsPaths.myWorkChannelMix(scope)),
    );
    return result.value;
  }
}
