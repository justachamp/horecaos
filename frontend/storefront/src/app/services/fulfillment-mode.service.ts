import { Injectable, inject } from '@angular/core';

import { ApiClient } from '../core/api/api-client';
import { APP_CONFIG } from '../core/config/app-config';
import type { FulfillmentMode } from './cart.service';

/**
 * `StorefrontFulfillmentModeController.FulfillmentModeAvailability`,
 * transcribed.
 *
 * `sold` and `serviceable` are deliberately two different facts:
 * `sold = false` means this channel never offers the mode at this location
 * (there is no tab to show); `sold = true, serviceable = false` means the
 * mode exists but cannot be ordered *right now* (closed, outside hours, at
 * capacity, no live menu) -- a tab a customer may still see, with `reason`
 * explaining why picking it will not go anywhere yet.
 */
export interface FulfillmentModeAvailability {
  readonly mode: FulfillmentMode;
  readonly sold: boolean;
  readonly serviceable: boolean;
  /** A `ServiceabilityReason` name, present whenever `serviceable` is false. */
  readonly reason: string | null;
}

interface FulfillmentModesResponse {
  readonly modes?: readonly FulfillmentModeAvailability[];
}

/**
 * Which fulfilment modes this channel actually sells at this location, and
 * which of those can be ordered right now.
 *
 * Backs the home screen's delivery/pickup switch: before this read existed,
 * the switch offered every mode unconditionally and defaulted to `DELIVERY`
 * even for a channel that only ever sold `PICKUP`. `GET .../fulfillment-modes`
 * is public, like the menu and the serviceability read it is built from --
 * choosing a mode to browse in must not require an account.
 */
@Injectable({ providedIn: 'root' })
export class FulfillmentModeService {
  private readonly api = inject(ApiClient);
  private readonly config = inject(APP_CONFIG);

  async modes(locationId?: string): Promise<readonly FulfillmentModeAvailability[]> {
    const location = locationId ?? this.config.defaultLocationId;
    if (!location) {
      throw new Error('No location is configured for this storefront.');
    }
    const response = await this.api.get<FulfillmentModesResponse>(
      `/storefront/tenants/${this.config.tenantId}/brands/${this.config.brandId}` +
        `/locations/${location}/fulfillment-modes`,
      {
        query: { channel: this.config.channel },
        anonymous: true,
      },
    );
    return response.modes ?? [];
  }
}
