import { Injectable, inject } from '@angular/core';

import { ApiClient } from '../core/api/api-client';
import { APP_CONFIG } from '../core/config/app-config';
import { isNotFound } from '../core/api/problem-details';

/**
 * `StorefrontLocationProfileController`'s response, transcribed: a branch's
 * own published name and address, and nothing else -- the same fields the
 * pickup-location search already returns and a receipt already carries.
 */
export interface LocationProfile {
  readonly displayName: string;
  readonly addressLine?: string;
  readonly district?: string;
  readonly city?: string;
  readonly landmark?: string;
  readonly contactPhone?: string;
  readonly latitude?: number;
  readonly longitude?: number;
}

/**
 * A branch's own name and address -- `GET .../locations/{id}/profile`
 * (unauthenticated, cached 30s server-side per ADR 0033).
 *
 * Backs the pickup confirmation screen, which previously had no read that
 * named *which* branch a customer was collecting from: it showed a generic
 * "you will collect this order from this branch" line with no name or
 * address at all.
 */
@Injectable({ providedIn: 'root' })
export class LocationProfileService {
  private readonly api = inject(ApiClient);
  private readonly config = inject(APP_CONFIG);

  /**
   * Cached per location for this session: the endpoint is a 30s-cacheable
   * public read of essentially static data, and a screen that asks twice for
   * the same branch (the pickup confirmation and, later, an order's own
   * detail) should not spend a second request on it.
   *
   * Only a *resolved* read is kept. A rejected fetch deletes its own cache
   * entry (see below) rather than remembering the failure, so a transient
   * error does not become permanent for the rest of the session.
   */
  private readonly cache = new Map<string, Promise<LocationProfile | null>>();

  /**
   * `null` for an unknown, inactive, or cross-tenant location -- never
   * thrown, so a screen that only wants to *show* the branch name if it can
   * is not forced to handle a rejection just to render the rest of the page.
   */
  profile(locationId: string): Promise<LocationProfile | null> {
    const cached = this.cache.get(locationId);
    if (cached) {
      return cached;
    }
    const promise = this.fetch(locationId).catch((failure) => {
      this.cache.delete(locationId);
      if (isNotFound(failure)) {
        return null;
      }
      throw failure;
    });
    this.cache.set(locationId, promise);
    return promise;
  }

  private fetch(locationId: string): Promise<LocationProfile> {
    return this.api.get<LocationProfile>(
      `/storefront/tenants/${this.config.tenantId}/brands/${this.config.brandId}` +
        `/locations/${locationId}/profile`,
      { anonymous: true },
    );
  }
}
