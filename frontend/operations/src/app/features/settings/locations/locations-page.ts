import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { ActivatedRoute, Router, RouterOutlet } from '@angular/router';

import { LocationScope } from '../../../core/api/operations-paths';
import { ApiError } from '../../../core/api/problem-details';
import { CurrentLocation } from '../../../core/auth/current-location';
import { I18n } from '../../../core/i18n/i18n';
import { TPipe } from '../../../core/i18n/t.pipe';
import { describeApiError } from '../../orders/order-errors';
import {
  BulkServiceStateItemView,
  ChangeServiceStateRequest,
  LocationLegalEntityView,
  LocationServiceStateView,
  LocationsApi,
  LocationView,
  ServiceMode,
} from './locations-api';

type StateFilter = 'ALL' | ServiceMode;
const ALL = 'ALL';

/** Forced-closed first, everything else after — Settings 10.2a's severity sort. */
function severityRank(mode: ServiceMode): number {
  return mode === 'FORCE_CLOSED' ? 0 : 1;
}

/**
 * 10.2a Location list — `docs/operations-spec/settings.md` §10.2a.
 *
 * Wave P32 closed the gap `locations-page.ts`'s own earlier comment named:
 * `OperationsBrandController.locations` returned the profile only, so a
 * state column, a state filter, or a close/open row action would each have
 * been an n+1 (`serviceSummary` per row). `LocationsApi.serviceStates` now
 * batches the whole brand's `location_service_state` in one call
 * (`OperationsBrandController.locationServiceStates`), so all three are real
 * here.
 *
 * Wave 9 closes the rest of that gap: the channel and INN filters
 * ({@link channelFilter}/{@link legalEntityFilter}, over {@link
 * LocationsApi.channels}/{@link LocationsApi.legalEntities}, both batched the
 * same way {@link LocationsApi.serviceStates} already is), the severity sort
 * (forced-closed branches always sort first, see {@link severityRank}), and
 * the bulk close/open bar ({@link selected}, {@link bulkClose}, {@link
 * bulkReopen}) over {@link LocationsApi.bulkChangeServiceState} — the same
 * batched service-state write `OperationsBrandController` exposes beside its
 * batched read, reporting one outcome per selected branch rather than one
 * call per branch.
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
  protected readonly legalEntities = signal<ReadonlyMap<string, LocationLegalEntityView>>(
    new Map(),
  );
  protected readonly channelsByLocation = signal<ReadonlyMap<string, readonly string[]>>(
    new Map(),
  );
  protected readonly docked = signal(false);

  protected readonly stateFilter = signal<StateFilter>('ALL');
  protected readonly channelFilter = signal<string>(ALL);
  protected readonly legalEntityFilter = signal<string>(ALL);

  /** The one row currently showing its inline "why are you closing this" reason field. */
  protected readonly closingLocationId = signal<string | null>(null);
  protected readonly closeReasonCode = signal('');
  protected readonly stateActionSaving = signal<string | null>(null);
  protected readonly stateActionError = signal<string | null>(null);

  /** The bulk close/open bar's own selection, keyed by location id. */
  protected readonly selected = signal<ReadonlySet<string>>(new Set());
  protected readonly bulkClosing = signal(false);
  protected readonly bulkReasonCode = signal('');
  protected readonly bulkSaving = signal(false);
  protected readonly bulkError = signal<string | null>(null);
  protected readonly bulkOutcomes = signal<readonly BulkServiceStateItemView[] | null>(null);

  /** Every channel code any of the brand's locations currently sells on, sorted, for the channel filter's own options. */
  protected readonly availableChannels = computed(() => {
    const codes = new Set<string>();
    for (const perLocation of this.channelsByLocation().values()) {
      for (const code of perLocation) {
        codes.add(code);
      }
    }
    return [...codes].sort();
  });

  /** Every legal entity code currently assigned to any of the brand's locations, sorted, for the INN filter's own options. */
  protected readonly availableLegalEntities = computed(() => {
    const codes = new Set<string>();
    for (const entity of this.legalEntities().values()) {
      codes.add(entity.legalEntityCode);
    }
    return [...codes].sort();
  });

  protected readonly filteredLocations = computed(() => {
    const stateFilter = this.stateFilter();
    const channelFilter = this.channelFilter();
    const legalEntityFilter = this.legalEntityFilter();
    const channels = this.channelsByLocation();
    const entities = this.legalEntities();

    const filtered = this.locations().filter((location) => {
      if (stateFilter !== ALL && this.effectiveModeOf(location.id) !== stateFilter) {
        return false;
      }
      if (channelFilter !== ALL && !(channels.get(location.id) ?? []).includes(channelFilter)) {
        return false;
      }
      if (legalEntityFilter !== ALL && entities.get(location.id)?.legalEntityCode !== legalEntityFilter) {
        return false;
      }
      return true;
    });

    // Settings 10.2a's severity sort: a forced-closed branch is the one thing
    // an operator opening this list needs to see first, wherever it falls
    // alphabetically or by code.
    return [...filtered].sort(
      (a, b) => severityRank(this.effectiveModeOf(a.id)) - severityRank(this.effectiveModeOf(b.id)),
    );
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

  protected channelCodesOf(locationId: string): readonly string[] {
    return this.channelsByLocation().get(locationId) ?? [];
  }

  protected legalEntityCodeOf(locationId: string): string | null {
    return this.legalEntities().get(locationId)?.legalEntityCode ?? null;
  }

  // ------------------------------------------------------------- selection

  protected isSelected(locationId: string): boolean {
    return this.selected().has(locationId);
  }

  protected toggleOne(locationId: string): void {
    const next = new Set(this.selected());
    if (next.has(locationId)) {
      next.delete(locationId);
    } else {
      next.add(locationId);
    }
    this.selected.set(next);
  }

  /** Selects every currently-filtered row, or clears the selection when every one is already selected. */
  protected toggleSelectAll(): void {
    const ids = this.filteredLocations().map((location) => location.id);
    if (ids.length === 0) {
      return;
    }
    const allSelected = ids.every((id) => this.selected().has(id));
    this.selected.set(allSelected ? new Set() : new Set(ids));
  }

  protected clearSelection(): void {
    this.selected.set(new Set());
    this.bulkClosing.set(false);
    this.bulkOutcomes.set(null);
  }

  // -------------------------------------------------------- bulk close/open

  protected startBulkClosing(): void {
    this.bulkClosing.set(true);
    this.bulkReasonCode.set('');
    this.bulkError.set(null);
  }

  protected cancelBulkClosing(): void {
    this.bulkClosing.set(false);
  }

  protected async confirmBulkClose(): Promise<void> {
    const reason = this.bulkReasonCode().trim();
    if (!reason) {
      this.bulkError.set(this.i18n.t('settings.locations.hours.reasonRequired'));
      return;
    }
    const closed = await this.bulkChangeState({ mode: 'FORCE_CLOSED', reasonCode: reason });
    if (closed) {
      this.bulkClosing.set(false);
    }
  }

  protected async bulkReopen(): Promise<void> {
    await this.bulkChangeState({ mode: 'FOLLOW_SCHEDULE' });
  }

  /** @returns whether the request was sent at all, so a caller can close its own inline editor only then */
  private async bulkChangeState(request: ChangeServiceStateRequest): Promise<boolean> {
    const base = this.location.scope();
    const locationIds = [...this.selected()];
    if (!base || locationIds.length === 0 || this.bulkSaving()) {
      return false;
    }
    this.bulkSaving.set(true);
    this.bulkError.set(null);
    try {
      const response = await this.api.bulkChangeServiceState(base, { ...request, locationIds });
      this.bulkOutcomes.set(response.items);
      const states = await this.api.serviceStates(base);
      this.serviceStates.set(new Map(states.map((state) => [state.locationId, state])));
      this.selected.set(new Set());
      return true;
    } catch (error) {
      this.bulkError.set(this.describe(error));
      return false;
    } finally {
      this.bulkSaving.set(false);
    }
  }

  protected displayNameOf(locationId: string): string {
    return this.locations().find((location) => location.id === locationId)?.displayName ?? locationId;
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
      const [locations, states, entities, channels] = await Promise.all([
        this.api.list(scope),
        this.api.serviceStates(scope),
        this.api.legalEntities(scope),
        this.api.channels(scope),
      ]);
      this.locations.set(locations);
      this.serviceStates.set(new Map(states.map((state) => [state.locationId, state])));
      this.legalEntities.set(new Map(entities.map((entity) => [entity.locationId, entity])));
      this.channelsByLocation.set(
        new Map(channels.map((entry) => [entry.locationId, entry.channelCodes])),
      );
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
