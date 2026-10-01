import { Injectable, effect, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../api/api-client';
import { LocationScope } from '../api/operations-paths';
import { settingsPaths } from '../api/settings-paths';
import { CurrentLocation } from '../auth/current-location';
import { RegionalFormats, applyRegionalFormats, resetRegionalFormats } from './regional-format';

/**
 * How a brand writes amounts and phones, as `LocationServiceOperationsController.regionalFormats`
 * answers it. Every field may be absent: an older platform sends none of them, and that reads as
 * the defaults.
 */
type BrandFormatsView = Partial<Record<keyof RegionalFormats, string | null>>;

/**
 * Keeps the console's formatters on the brand the operator is working in
 * (Settings 10.12).
 *
 * `formatMoney` and `formatPhone` read one signal (`regional-format.ts`); this
 * is what writes it. The shell injects it once, and from then on it follows
 * {@link CurrentLocation.scope}: whenever the operator's brand resolves or
 * changes — a location picked in another brand — it reads that brand's formats
 * and applies them.
 *
 * **Read at the location, not at the brand.** The brand read needs `BRAND_READ`,
 * and the people who read money and phone numbers all day — the cashier, the
 * kitchen lead, the branch manager — hold `LOCATION_READ` at their own branch
 * and nothing at the brand. So this asks the location's own endpoint
 * (`settingsPaths.locationRegionalFormats`), which every operator role can read;
 * reading the brand instead left exactly those roles on the defaults, with a 403
 * on every shell load that the fallback below hid.
 *
 * **Best effort, on purpose.** A network error or an older platform that sends no
 * formats leaves the formatters on the defaults, which is how this console always
 * showed money. A price must never fail to render because a display preference
 * could not be fetched.
 *
 * **Stale replies are dropped.** Switching from brand A to brand B while A's
 * read is in flight must not let A's late answer overwrite B's formats.
 */
@Injectable({ providedIn: 'root' })
export class RegionalFormatSync {
  private readonly api = inject(ApiClient);
  private readonly location = inject(CurrentLocation);

  /** The brand the formatters currently follow; a stale reply is told apart by it. */
  private followedBrandId: string | null = null;

  constructor() {
    effect(() => {
      const scope = this.location.scope();
      if (scope === null) {
        return;
      }
      void this.follow(scope);
    });
  }

  /** Reads the brand's formats and applies them, unless the operator has already moved on to another brand. */
  async follow(scope: LocationScope): Promise<void> {
    if (this.followedBrandId === scope.brandId) {
      return;
    }
    this.followedBrandId = scope.brandId;
    try {
      const result = await firstValueFrom(
        this.api.get<BrandFormatsView>(settingsPaths.locationRegionalFormats(scope)),
      );
      if (this.followedBrandId === scope.brandId) {
        applyRegionalFormats(result.value);
      }
    } catch {
      if (this.followedBrandId === scope.brandId) {
        resetRegionalFormats();
        // Let the next scope change, or the next screen, try again.
        this.followedBrandId = null;
      }
    }
  }

  /** The brand-profile screen just saved new formats for this brand: use them at once. */
  applySaved(scope: LocationScope, formats: BrandFormatsView | null | undefined): void {
    this.followedBrandId = scope.brandId;
    applyRegionalFormats(formats);
  }
}
