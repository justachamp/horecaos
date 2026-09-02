import { ChangeDetectionStrategy, Component, effect, inject, input, signal } from '@angular/core';

import { LocationScope } from '../../../core/api/operations-paths';
import { ApiError } from '../../../core/api/problem-details';
import { CurrentLocation } from '../../../core/auth/current-location';
import { I18n } from '../../../core/i18n/i18n';
import { TPipe } from '../../../core/i18n/t.pipe';
import { describeApiError } from '../../orders/order-errors';
import { LocationsApi, LocationView, ServiceSummaryResponse } from './locations-api';

type LocationTab = 'basics' | 'hours' | 'load' | 'fiscal' | 'channels' | 'notifications';

/**
 * 10.2b Location detail — `docs/operations-spec/settings.md` §10.2b. Six
 * tabs, per the spec; three are real, three link out honestly.
 *
 * **Tab 1 (Основное)** reads `LocationServiceOperationsController.profile`
 * (new, operations surface) and writes address/phone/point through
 * `TenantControlPlaneController`'s existing `place` endpoint, cross-surface.
 * name/code/slug/timezone/status stay read-only — nothing writes them.
 *
 * **Tabs 2 and 3 (Часы, Загрузка и приготовление)** read the new
 * `service-summary` endpoint — the manual override, every bound schedule's
 * full grid, preparation bands, live capacity. Writing is scoped to what is
 * simple and real: the manual open/close override and the capacity ceiling.
 * Binding a different schedule, or editing a schedule's own weekly grid, is
 * not built here — `ServiceScheduleController` has no HTTP list of a brand's
 * schedules to pick from (only this location's own summary resolves one),
 * so a picker would be a text field for a raw schedule id with nothing to
 * validate it against, which is worse than naming the gap.
 *
 * **Tabs 4–6** link to the screens that actually own the data (10.7, 10.4,
 * 10.9) rather than duplicating a weaker read of it here, per the spec's own
 * instruction for Tab 4.
 */
@Component({
  selector: 'q-location-detail-pane',
  imports: [TPipe],
  templateUrl: './location-detail-pane.html',
  styleUrl: './location-detail-pane.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class LocationDetailPane {
  private readonly api = inject(LocationsApi);
  private readonly baseLocation = inject(CurrentLocation);
  protected readonly i18n = inject(I18n);

  /** Route param, bound by `withComponentInputBinding()` — see `order-detail-pane.ts` for the same idiom. */
  readonly locationId = input.required<string>();

  protected readonly activeTab = signal<LocationTab>('basics');
  protected readonly loading = signal(true);
  protected readonly denied = signal(false);
  protected readonly loadError = signal<string | null>(null);
  protected readonly profile = signal<LocationView | null>(null);
  protected readonly summary = signal<ServiceSummaryResponse | null>(null);

  protected readonly editingPlace = signal(false);
  protected readonly placeSaving = signal(false);
  protected readonly placeError = signal<string | null>(null);
  protected readonly draftAddressLine = signal('');
  protected readonly draftDistrict = signal('');
  protected readonly draftCity = signal('');
  protected readonly draftContactPhone = signal('');

  protected readonly stateSaving = signal(false);
  protected readonly stateError = signal<string | null>(null);
  protected readonly draftMode = signal<'FOLLOW_SCHEDULE' | 'FORCE_OPEN' | 'FORCE_CLOSED'>(
    'FORCE_CLOSED',
  );
  protected readonly draftReasonCode = signal('');

  protected readonly capacitySaving = signal(false);
  protected readonly capacityError = signal<string | null>(null);
  protected readonly draftCapacity = signal<number | null>(null);

  constructor() {
    // The route reuses this component across a `:locationId` change (default
    // RouteReuseStrategy), so a plain constructor-only load only fires once —
    // the same reason `order-detail-pane.ts`/`inbox-detail-pane.ts` re-read
    // inside an `effect()` keyed on the input signal rather than on init.
    effect(() => {
      const id = this.locationId();
      void this.load(id);
    });
  }

  protected selectTab(tab: LocationTab): void {
    this.activeTab.set(tab);
  }

  protected startEditingPlace(): void {
    const current = this.profile();
    this.draftAddressLine.set(current?.addressLine ?? '');
    this.draftDistrict.set(current?.district ?? '');
    this.draftCity.set(current?.city ?? '');
    this.draftContactPhone.set(current?.contactPhone ?? '');
    this.placeError.set(null);
    this.editingPlace.set(true);
  }

  protected async savePlace(): Promise<void> {
    const scope = this.scope();
    if (!scope || this.placeSaving()) {
      return;
    }
    this.placeSaving.set(true);
    this.placeError.set(null);
    try {
      const updated = await this.api.describePlace(scope, {
        addressLine: this.draftAddressLine().trim() || undefined,
        district: this.draftDistrict().trim() || undefined,
        city: this.draftCity().trim() || undefined,
        contactPhone: this.draftContactPhone().trim() || undefined,
      });
      this.profile.set(updated);
      this.editingPlace.set(false);
    } catch (error) {
      this.placeError.set(this.describe(error));
    } finally {
      this.placeSaving.set(false);
    }
  }

  protected async changeState(): Promise<void> {
    const scope = this.scope();
    if (!scope || this.stateSaving()) {
      return;
    }
    if (this.draftMode() !== 'FOLLOW_SCHEDULE' && this.draftReasonCode().trim().length === 0) {
      this.stateError.set(this.i18n.t('settings.locations.hours.reasonRequired'));
      return;
    }
    this.stateSaving.set(true);
    this.stateError.set(null);
    try {
      await this.api.changeServiceState(scope, {
        mode: this.draftMode(),
        reasonCode:
          this.draftMode() === 'FOLLOW_SCHEDULE' ? undefined : this.draftReasonCode().trim(),
      });
      this.summary.set(await this.api.serviceSummary(scope));
    } catch (error) {
      this.stateError.set(this.describe(error));
    } finally {
      this.stateSaving.set(false);
    }
  }

  protected async saveCapacity(): Promise<void> {
    const scope = this.scope();
    if (!scope || this.capacitySaving()) {
      return;
    }
    this.capacitySaving.set(true);
    this.capacityError.set(null);
    try {
      await this.api.setCapacity(scope, this.draftCapacity());
      this.summary.set(await this.api.serviceSummary(scope));
    } catch (error) {
      this.capacityError.set(this.describe(error));
    } finally {
      this.capacitySaving.set(false);
    }
  }

  private scope(): LocationScope | null {
    const base = this.baseLocation.scope();
    if (!base) {
      return null;
    }
    return { tenantId: base.tenantId, brandId: base.brandId, locationId: this.locationId() };
  }

  private async load(locationId: string): Promise<void> {
    this.loading.set(true);
    this.loadError.set(null);
    await this.baseLocation.ensureLoaded();
    const base = this.baseLocation.scope();
    if (!base) {
      this.denied.set(this.baseLocation.denied());
      this.loading.set(false);
      return;
    }
    const scope: LocationScope = { tenantId: base.tenantId, brandId: base.brandId, locationId };
    try {
      const [profile, summary] = await Promise.all([
        this.api.profile(scope),
        this.api.serviceSummary(scope),
      ]);
      this.profile.set(profile);
      this.summary.set(summary);
      this.draftCapacity.set(summary.maxConcurrentOrders);
    } catch (error) {
      if (error instanceof ApiError && error.status === 403) {
        this.denied.set(true);
      } else {
        this.loadError.set(this.describe(error));
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
