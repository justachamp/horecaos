import { Injectable, inject } from '@angular/core';

import { ApiClient } from '../api/api-client';
import { APP_CONFIG } from '../config/app-config';
import type { CourierProfile } from './courier-profile';

/**
 * The courier's own surface: `/api/v1/courier/tenants/{tenantId}/...`. Every call here resolves
 * the courier from the signed token and never from a parameter, so there is no id in this class
 * that could name somebody else.
 */
@Injectable({ providedIn: 'root' })
export class CourierApi {
  private readonly api = inject(ApiClient);
  private readonly config = inject(APP_CONFIG);

  me(): Promise<CourierProfile> {
    return this.api.get<CourierProfile>(`${this.base()}/me`);
  }

  /** The tenant prefix every courier call shares. */
  base(): string {
    return `/courier/tenants/${this.config.tenantId}`;
  }
}
