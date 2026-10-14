import { Injectable, inject } from '@angular/core';

import { ApiClient } from '../api/api-client';
import { APP_CONFIG } from '../config/app-config';

/** Mirrors `StorefrontPageLoadController.EnabledView`. */
interface PageLoadFlag {
  readonly enabled: boolean;
}

const COUNTED_KEY = 'horecaos.pageload.counted';

/**
 * Counts this tab session as one page load, **only** when the platform has switched the page-load count on (ADR 0183 D18,
 * ADR 0167 as amended).
 *
 * **Dark by default, and quiet while dark.** The flag lives on the platform (`reporting.page_load_count_enabled`, off until
 * counsel answers). The first thing this does, once per tab session, is ask `GET .../page-loads` whether it is on: a public
 * read that is the same for every visitor and names nobody. If it is off, nothing else is sent, so a storefront that has not
 * been cleared to count makes no measurement request at all.
 *
 * **What the ping is.** One anonymous `POST` carrying the sales channel code this deployment reports on carts, and nothing
 * else: no cookie, no identifier, no path, nothing about the visitor. The platform adds one to a per-day counter. It is a
 * count of tab sessions, not of people: one person opening the shop twice is two, and the console says «page loads, not
 * people».
 *
 * **Once per tab session.** The decision to look is remembered in `sessionStorage` before the call is made, so a reload
 * neither re-asks nor re-counts, and a platform that refuses is not retried by the next navigation. A browser that refuses
 * storage asks again on reload, which the endpoint's rate limit bounds.
 *
 * **It never gets in a customer's way.** The call is not awaited by anything that renders and every failure is swallowed.
 */
@Injectable({ providedIn: 'root' })
export class PageLoadCounter {
  private readonly api = inject(ApiClient);
  private readonly config = inject(APP_CONFIG);

  async recordSession(): Promise<void> {
    if (this.alreadyAsked()) {
      return;
    }
    this.remember();
    const path =
      `/storefront/tenants/${encodeURIComponent(this.config.tenantId)}` +
      `/brands/${encodeURIComponent(this.config.brandId)}/page-loads`;
    try {
      const flag = await this.api.get<PageLoadFlag>(path, { anonymous: true, quiet: true });
      if (!flag.enabled) {
        return;
      }
      await this.api.report(path, { channel: this.config.channel });
    } catch {
      // A counter is not worth a broken page.
    }
  }

  private alreadyAsked(): boolean {
    try {
      return sessionStorage.getItem(COUNTED_KEY) !== null;
    } catch {
      return false;
    }
  }

  private remember(): void {
    try {
      sessionStorage.setItem(COUNTED_KEY, '1');
    } catch {
      // Storage refused: the next load asks again.
    }
  }
}
