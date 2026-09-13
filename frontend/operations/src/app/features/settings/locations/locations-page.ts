import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { ActivatedRoute, Router, RouterOutlet } from '@angular/router';

import { LocationScope } from '../../../core/api/operations-paths';
import { ApiError } from '../../../core/api/problem-details';
import { CurrentLocation } from '../../../core/auth/current-location';
import { I18n } from '../../../core/i18n/i18n';
import { TPipe } from '../../../core/i18n/t.pipe';
import { describeApiError } from '../../orders/order-errors';
import {
  ChangeServiceStateRequest,
  LocationServiceStateView,
  LocationsApi,
  LocationView,
  ServiceMode,
} from './locations-api';

type StateFilter = 'ALL' | ServiceMode;

/**
 * 10.2a Location list — `docs/operations-spec/settings.md` §10.2a.
 *
 * Wave P32 closed the gap `locations-page.ts`'s own earlier comment named:
 * `OperationsBrandController.locations` returned the profile only, so a
 * state column, a state filter, or a close/open row action would each have
 * been an n+1 (`serviceSummary` per row). `LocationsApi.serviceStates` now
 * batches the whole brand's `location_service_state` in one call
 * (`OperationsBrandController.locationServiceStates`), so all three are real
 * here. Still simplified relative to the spec: no severity sort
 * (forced-closed first), no channel or INN filter, and closing several
 * branches at once is still one call per branch rather than a bulk endpoint
 * — named rather than pretended away.
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
  protected readonly serviceStates = signal<ReadonlyMap<string, LocationServiceStateView>>(
    new Map(),
  );
  protected readonly docked = signal(false);

  protected readonly stateFilter = signal<StateFilter>('ALL');

  /** The one row currently showing its inline "why are you closing this" reason field. */
  protected readonly closingLocationId = signal<string | null>(null);
  protected readonly closeReasonCode = signal('');
  protected readonly stateActionSaving = signal<string | null>(null);
  protected readonly stateActionError = signal<string | null>(null);

  protected readonly filteredLocations = computed(() => {
    const filter = this.stateFilter();
    if (filter === 'ALL') {
      return this.locations();
    }
    return this.locations().filter((location) => this.effectiveModeOf(location.id) === filter);
  });

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

  protected effectiveModeOf(locationId: string): ServiceMode {
    return this.serviceStates().get(locationId)?.effectiveMode ?? 'FOLLOW_SCHEDULE';
  }

  protected stateLabel(mode: ServiceMode): string {
    switch (mode) {
      case 'FOLLOW_SCHEDULE':
        return this.i18n.t('settings.locations.list.state.open');
      case 'FORCE_OPEN':
        return this.i18n.t('settings.locations.list.state.forceOpen');
      case 'FORCE_CLOSED':
        return this.i18n.t('settings.locations.list.state.closed');
    }
  }

  protected reasonCodeOf(locationId: string): string | null {
    return this.serviceStates().get(locationId)?.reasonCode ?? null;
  }

  protected startClosing(locationId: string): void {
    this.closingLocationId.set(locationId);
    this.closeReasonCode.set('');
    this.stateActionError.set(null);
  }

  protected cancelClosing(): void {
    this.closingLocationId.set(null);
  }

  protected async confirmClose(location: LocationView): Promise<void> {
    const reason = this.closeReasonCode().trim();
    if (!reason) {
      this.stateActionError.set(this.i18n.t('settings.locations.hours.reasonRequired'));
      return;
    }
    const closed = await this.changeState(location.id, {
      mode: 'FORCE_CLOSED',
      reasonCode: reason,
    });
    if (closed) {
      this.closingLocationId.set(null);
    }
  }

  protected async reopen(location: LocationView): Promise<void> {
    await this.changeState(location.id, { mode: 'FOLLOW_SCHEDULE' });
  }

  /** @returns whether the write succeeded, so a caller can close its own inline editor only then */
  private async changeState(
    locationId: string,
    request: ChangeServiceStateRequest,
  ): Promise<boolean> {
    const base = this.location.scope();
    if (!base || this.stateActionSaving()) {
      return false;
    }
    const scope: LocationScope = { tenantId: base.tenantId, brandId: base.brandId, locationId };
    this.stateActionSaving.set(locationId);
    this.stateActionError.set(null);
    try {
      await this.api.changeServiceState(scope, request);
      const states = await this.api.serviceStates(scope);
      this.serviceStates.set(new Map(states.map((state) => [state.locationId, state])));
      return true;
    } catch (error) {
      this.stateActionError.set(this.describe(error));
      return false;
    } finally {
      this.stateActionSaving.set(null);
    }
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
      const [locations, states] = await Promise.all([
        this.api.list(scope),
        this.api.serviceStates(scope),
      ]);
      this.locations.set(locations);
      this.serviceStates.set(new Map(states.map((state) => [state.locationId, state])));
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

  private describe(error: unknown): string {
    if (error instanceof ApiError) {
      return describeApiError(error, (key, values) => this.i18n.t(key, values));
    }
    return this.i18n.t('error.unknown.noReference');
  }
}
