import { Injectable, inject } from '@angular/core';

import { ApiClient } from '../core/api/api-client';
import { APP_CONFIG } from '../core/config/app-config';
import { isNotFound } from '../core/api/problem-details';

/** `StorefrontPickupLocationController.LocationProfileView` -- a branch's public name and address. */
export interface LocationProfile {
  readonly locationId: string;
  readonly brandName: string;
  readonly locationName: string;
  readonly addressLine: string;
  readonly district: string;
  readonly city: string;
}

/**
 * Reads a branch's public profile, by id (2026-09-21 audit follow-up (d)).
 *
 * Built for a pickup order's own detail: `OrderResponse.locationId` names the
 * branch, and this turns that id into something to show -- "Central kitchen,
 * 1 Demo Street" rather than a UUID a customer never asked to see.
 *
 * A 404 (the location does not exist for this tenant/brand, or has since gone
 * inactive) resolves to `null` rather than throwing: this is a "show the
 * branch if we can" read, not a load-bearing one, and a screen that cannot
 * resolve a profile falls back to its pre-existing generic wording rather
 * than failing to render the order at all.
 *
 * A resolved read -- found or not -- is cached per location for the session,
 * because the answer does not change while a customer is looking at one
 * order. A rejected read (a network failure, not a 404) is never cached, so
 * a transient failure can be retried rather than replaying the same failure
 * for the rest of the session.
 */
@Injectable({ providedIn: 'root' })
export class LocationProfileService {
  private readonly api = inject(ApiClient);
  private readonly config = inject(APP_CONFIG);

  private readonly resolved = new Map<string, LocationProfile | null>();
  private readonly pending = new Map<string, Promise<LocationProfile | null>>();

  async profile(locationId: string): Promise<LocationProfile | null> {
    if (this.resolved.has(locationId)) {
      return this.resolved.get(locationId) ?? null;
    }
    const inFlight = this.pending.get(locationId);
    if (inFlight) {
      return inFlight;
    }
    const request = this.load(locationId);
    this.pending.set(locationId, request);
    try {
      const result = await request;
      this.resolved.set(locationId, result);
      return result;
    } finally {
      this.pending.delete(locationId);
    }
  }

  private async load(locationId: string): Promise<LocationProfile | null> {
    try {
      return await this.api.get<LocationProfile>(
        `/storefront/tenants/${this.config.tenantId}/brands/${this.config.brandId}` +
          `/locations/${locationId}/profile`,
        { anonymous: true },
      );
    } catch (failure) {
      if (isNotFound(failure)) {
        return null;
      }
      throw failure;
    }
  }
}
