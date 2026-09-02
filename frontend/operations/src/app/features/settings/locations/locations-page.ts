import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { ActivatedRoute, Router, RouterOutlet } from '@angular/router';

import { ApiError } from '../../../core/api/problem-details';
import { CurrentLocation } from '../../../core/auth/current-location';
import { I18n } from '../../../core/i18n/i18n';
import { TPipe } from '../../../core/i18n/t.pipe';
import { describeApiError } from '../../orders/order-errors';
import { LocationsApi, LocationView } from './locations-api';

/**
 * 10.2a Location list — `docs/operations-spec/settings.md` §10.2a.
 *
 * Simplified relative to the spec: no status-tab counts, no severity sort
 * (forced-closed first), no bulk actions — those read `location_service_state`
 * per row, which this list does not fetch (`OperationsBrandController.locations`
 * returns the profile only, not the live service state; fetching it per row
 * would be an n+1 the spec's own "computed before filtering" principle warns
 * against without a dedicated summary read). A plain, real table beats one
 * that fakes severity from data it does not have.
 *
 * The docked detail (`:locationId`) is a routed child, the same shape
 * `order-queue`/`order-detail-pane` already use in this app.
 */
@Component({
  selector: 'q-locations-page',
  imports: [TPipe, RouterOutlet],
  templateUrl: './locations-page.html',
  styleUrl: './locations-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class LocationsPage {
  private readonly api = inject(LocationsApi);
  private readonly location = inject(CurrentLocation);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  protected readonly i18n = inject(I18n);

  protected readonly loading = signal(true);
  protected readonly denied = signal(false);
  protected readonly loadError = signal<string | null>(null);
  protected readonly locations = signal<readonly LocationView[]>([]);
  protected readonly docked = signal(false);

  constructor() {
    void this.load();
  }

  protected openLocation(location: LocationView): void {
    void this.router.navigate([location.id], { relativeTo: this.route });
  }

  /** Bound to `<router-outlet (activate) (deactivate)>` — see `orders-page.ts` for the same idiom. */
  protected onOutletActivate(): void {
    this.docked.set(true);
  }

  protected onOutletDeactivate(): void {
    this.docked.set(false);
  }

  private async load(): Promise<void> {
    this.loading.set(true);
    await this.location.ensureLoaded();
    const scope = this.location.scope();
    if (!scope) {
      this.denied.set(this.location.denied());
      this.loading.set(false);
      return;
    }
    try {
      this.locations.set(await this.api.list(scope));
    } catch (error) {
      if (error instanceof ApiError && error.status === 403) {
        this.denied.set(true);
      } else if (error instanceof ApiError) {
        this.loadError.set(describeApiError(error, (key, values) => this.i18n.t(key, values)));
      } else {
        throw error;
      }
    } finally {
      this.loading.set(false);
    }
  }
}
