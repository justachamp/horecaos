import { Injectable, effect, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../api/api-client';
import { LocationScope } from '../api/operations-paths';
import { settingsPaths } from '../api/settings-paths';
import { CurrentLocation } from '../auth/current-location';
import { RegionalFormats, applyRegionalFormats, resetRegionalFormats } from './regional-format';

/** The one field this reads from `OperationsBrandController.get`'s `BrandView`. */
interface BrandFormatsView {
  readonly regionalFormats?: Partial<Record<keyof RegionalFormats, string | null>> | null;
}

/**
 * Keeps the console's formatters on the brand the operator is working in
 * (Settings 10.12).
 *
 * `formatMoney` and `formatPhone` read one signal (`regional-format.ts`); this
 * is what writes it. The shell injects it once, and from then on it follows
 * {@link CurrentLocation.scope}: whenever the operator's brand resolves or
 * changes — a location picked in another brand — it reads that brand's formats
 * from the brand read every screen already makes and applies them.
 *
 * **Best effort, on purpose.** A brand the operator cannot read, a network
 * error or an older platform that sends no formats all leave the formatters on
 * the defaults, which is how this console always showed money. A price must
 * never fail to render because a display preference could not be fetched.
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
        this.api.get<BrandFormatsView>(settingsPaths.brand(scope)),
      );
      if (this.followedBrandId === scope.brandId) {
        applyRegionalFormats(result.value?.regionalFormats);
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
  applySaved(scope: LocationScope, formats: BrandFormatsView['regionalFormats']): void {
    this.followedBrandId = scope.brandId;
    applyRegionalFormats(formats);
  }
}
