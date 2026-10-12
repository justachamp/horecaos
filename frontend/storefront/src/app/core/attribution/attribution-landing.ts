import { Injectable, inject } from '@angular/core';

import { ApiClient } from '../api/api-client';
import { APP_CONFIG } from '../config/app-config';

/** The shape `ck_attribution_link_token_shape` enforces; anything else is not a token this brand minted. */
const TOKEN_SHAPE = /^[A-Za-z0-9_-]{4,64}$/;

const COUNTED_KEY_PREFIX = 'horecaos.ref.counted.';

/**
 * Counts one click for an attribution link when the storefront is opened from a `?ref=` URL (ADR 0167
 * Decision 4 tier 1, ADR 0044).
 *
 * **What it sends, and what it does not.** One anonymous `POST` naming the brand and the token, and
 * nothing else: no cookie is set, no identifier is minted, nothing about the visitor travels with it,
 * and the platform stores a count per link per day (it keeps no address, user agent or cookie either).
 * It is the only request the storefront makes for measurement, and it fires only on a `?ref=` landing -- a
 * visitor who arrives any other way is measured by nothing here. Visits are deliberately not collected
 * (ADR 0167 Decision 1).
 *
 * **Once per tab session.** A refresh of the landing page is the same visit, so the token is remembered
 * in `sessionStorage` for the life of the tab and a reload does not count again. A browser that refuses
 * storage simply counts each landing; the endpoint is rate limited per visitor in any case.
 *
 * **It never gets in a customer's way.** The call is not awaited by anything that renders, and every
 * failure -- a refused token, a rate limit, a network error -- is swallowed: a counter is not worth a
 * broken page.
 */
@Injectable({ providedIn: 'root' })
export class AttributionLanding {
  private readonly api = inject(ApiClient);
  private readonly config = inject(APP_CONFIG);

  /** @param search the page's query string, `window.location.search` by default */
  async recordLanding(search: string = currentSearch()): Promise<void> {
    const token = refToken(search);
    if (token === null || this.alreadyCounted(token)) {
      return;
    }
    this.remember(token);
    try {
      // `report`, not `mutate`: no Idempotency-Key, no bearer, and no toast when it fails.
      await this.api.report(
        `/storefront/tenants/${encodeURIComponent(this.config.tenantId)}` +
          `/brands/${encodeURIComponent(this.config.brandId)}` +
          `/attribution-links/${encodeURIComponent(token)}/clicks`,
      );
    } catch {
      // See the class comment: a counter must never become the customer's problem.
    }
  }

  private alreadyCounted(token: string): boolean {
    try {
      return sessionStorage.getItem(COUNTED_KEY_PREFIX + token) !== null;
    } catch {
      return false;
    }
  }

  private remember(token: string): void {
    try {
      sessionStorage.setItem(COUNTED_KEY_PREFIX + token, '1');
    } catch {
      // Storage refused: the landing is counted again on reload, which the rate limit bounds.
    }
  }
}

/** The `ref` query parameter if it has the shape of a token, else null. */
export function refToken(search: string): string | null {
  const value = new URLSearchParams(search).get('ref');
  return value !== null && TOKEN_SHAPE.test(value) ? value : null;
}

function currentSearch(): string {
  return typeof window === 'undefined' ? '' : window.location.search;
}
