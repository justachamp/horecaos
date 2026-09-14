import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';

import { CurrentLocation } from '../../core/auth/current-location';
import { I18n } from '../../core/i18n/i18n';
import { MessageKey } from '../../core/i18n/messages.en';
import { TPipe } from '../../core/i18n/t.pipe';
import { Combobox, ComboboxOption } from '../../shared/ui/combobox';
import { FilterBar, FilterBarChip } from '../../shared/ui/filter-bar';
import { FiscalizationApi, LegalEntityView } from '../settings/fiscalization/fiscalization-api';
import { LocationView, LocationsApi } from '../settings/locations/locations-api';
import {
  PaymentMethodView,
  PaymentMethodsApi,
} from '../settings/payment-methods/payment-methods-api';
import { ChannelView, SalesChannelsApi } from '../settings/sales-channels/sales-channels-api';
import { Granularity, PeriodPreset, ReportsFilterState } from './reports-filter-state';

/**
 * The Reports section's frame (IA §7). `ReportsFilterState` is provided here,
 * one instance per visit to `/statistics`, so the period and slice a manager
 * sets on the overview survives switching tabs beside it (statistics.md
 * §1.1: "the bar's state is shared across all 7.x views").
 *
 * **Wave P27 re-points the bar at `q-filter-bar` directly and deletes the
 * `q-reports-filter-bar` wrapper P07 left compiling.** The bar's own markup
 * — period, custom range, granularity, fulfilment type, branch, channel,
 * legal entity, payment method — now lives here rather than in a second
 * component, exactly as that wrapper's own doc said this wave would do; it
 * is not extracted a second time. Every axis is applied server-side by the
 * pages that read it (see `business-overview-page.ts` and
 * `order-reports-page.ts`'s own docs) and round-trips through the URL via
 * `ReportsFilterState` itself.
 *
 * 7.1 Business overview and 7.2 Order reports (tier P, wave 33) are joined
 * over subsequent waves by every tier-2 row the IA lists: 7.3 Branch & SLA,
 * 7.4 Courier (T11, wave 139), 7.5/7.5a Staff (T12), 7.7 Product analytics
 * and 7.9 Marketing reports (T15) are real reads (see those pages' own docs
 * for exactly what "real" covers); 7.6 Customer analytics is the one row
 * still routing to the shared `NotBuiltPage`, naming the fact family it is
 * missing rather than shipping a chart over nothing.
 *
 * **7.8 Demand stays off this bar.** Its unit is not a date span but "the
 * most recent occurrences of one weekday", an axis the bar has no control
 * for — the same selective applicability 7.3 and 7.7 already have (they read
 * `range()` only, not branch/channel/legal-entity yet; wiring those tabs to
 * the fuller state is those waves' own follow-up, not reopened here).
 */
@Component({
  selector: 'q-reports-shell',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, TPipe, FilterBar, Combobox],
  providers: [ReportsFilterState],
  templateUrl: './reports-shell.html',
  styleUrl: './reports-shell.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ReportsShell implements OnInit {
  protected readonly state = inject(ReportsFilterState);
  private readonly location = inject(CurrentLocation);
  private readonly locationsApi = inject(LocationsApi);
  private readonly channelsApi = inject(SalesChannelsApi);
  private readonly fiscalizationApi = inject(FiscalizationApi);
  private readonly paymentMethodsApi = inject(PaymentMethodsApi);
  private readonly i18n = inject(I18n);

  protected readonly periods: readonly {
    readonly id: PeriodPreset;
    readonly labelKey: MessageKey;
  }[] = [
    { id: 'today', labelKey: 'reports.filter.period.today' },
    { id: 'yesterday', labelKey: 'reports.filter.period.yesterday' },
    { id: '7d', labelKey: 'reports.filter.period.7d' },
    { id: 'month', labelKey: 'reports.filter.period.month' },
    { id: 'custom', labelKey: 'reports.filter.period.custom' },
  ];

  protected readonly fulfilmentTypes: readonly {
    readonly id: 'ALL' | 'DELIVERY' | 'PICKUP' | 'DINE_IN';
    readonly labelKey: MessageKey;
  }[] = [
    { id: 'ALL', labelKey: 'reports.filter.fulfilment.all' },
    { id: 'DELIVERY', labelKey: 'reports.filter.fulfilment.delivery' },
    { id: 'PICKUP', labelKey: 'reports.filter.fulfilment.pickup' },
    { id: 'DINE_IN', labelKey: 'reports.filter.fulfilment.dineIn' },
  ];

  protected readonly granularities: readonly {
    readonly id: Granularity;
    readonly labelKey: MessageKey;
  }[] = [
    { id: 'day', labelKey: 'reports.filter.granularity.day' },
    { id: 'week', labelKey: 'reports.filter.granularity.week' },
    { id: 'month', labelKey: 'reports.filter.granularity.month' },
  ];

  protected readonly locations = signal<readonly LocationView[]>([]);
  protected readonly channels = signal<readonly ChannelView[]>([]);
  protected readonly legalEntities = signal<readonly LegalEntityView[]>([]);
  protected readonly paymentMethods = signal<readonly PaymentMethodView[]>([]);

  protected readonly locationOptions = computed<readonly ComboboxOption[]>(() =>
    this.locations().map((location) => ({ id: location.id, label: location.displayName })),
  );
  protected readonly channelOptions = computed<readonly ComboboxOption[]>(() =>
    this.channels().map((channel) => ({ id: channel.code, label: channel.displayName })),
  );
  protected readonly legalEntityOptions = computed<readonly ComboboxOption[]>(() =>
    this.legalEntities().map((entity) => ({
      id: entity.id,
      label: entity.shortName ?? entity.legalName,
    })),
  );
  protected readonly paymentMethodOptions = computed<readonly ComboboxOption[]>(() =>
    this.paymentMethods().map((method) => ({
      id: method.code,
      label: method.localizedNames[this.i18n.locale()] ?? method.displayName,
    })),
  );

  protected readonly selectedLocations = computed<readonly ComboboxOption[]>(() =>
    this.locationOptions().filter((option) => this.state.locationIds().includes(option.id)),
  );
  protected readonly selectedChannels = computed<readonly ComboboxOption[]>(() =>
    this.channelOptions().filter((option) => this.state.channelCodes().includes(option.id)),
  );
  protected readonly selectedLegalEntities = computed<readonly ComboboxOption[]>(() =>
    this.legalEntityOptions().filter((option) => this.state.legalEntityIds().includes(option.id)),
  );
  protected readonly selectedPaymentMethods = computed<readonly ComboboxOption[]>(() =>
    this.paymentMethodOptions().filter((option) =>
      this.state.paymentMethodCodes().includes(option.id),
    ),
  );

  /** Whether more than one branch exists at all — a single-location tenant gets no branch control (ADR 0055). */
  protected readonly showLocationFilter = computed(() => this.locations().length > 1);
  protected readonly showLegalEntityFilter = computed(() => this.legalEntities().length > 1);

  protected readonly chips = computed<readonly FilterBarChip[]>(() => {
    const chips: FilterBarChip[] = [];
    if (this.state.fulfilmentType() !== 'ALL') {
      chips.push({
        id: 'fulfilment',
        label: this.i18n.t(
          this.fulfilmentTypes.find((option) => option.id === this.state.fulfilmentType())
            ?.labelKey ?? 'reports.filter.fulfilment.all',
        ),
      });
    }
    for (const location of this.selectedLocations()) {
      chips.push({ id: `location:${location.id}`, label: location.label });
    }
    for (const channel of this.selectedChannels()) {
      chips.push({ id: `channel:${channel.id}`, label: channel.label });
    }
    for (const entity of this.selectedLegalEntities()) {
      chips.push({ id: `entity:${entity.id}`, label: entity.label });
    }
    for (const method of this.selectedPaymentMethods()) {
      chips.push({ id: `payment:${method.id}`, label: method.label });
    }
    if (this.state.granularity() !== 'day') {
      chips.push({
        id: 'granularity',
        label: this.i18n.t(
          this.granularities.find((option) => option.id === this.state.granularity())?.labelKey ??
            'reports.filter.granularity.day',
        ),
      });
    }
    return chips;
  });

  ngOnInit(): void {
    void this.loadFilterOptions();
  }

  protected selectPeriod(period: PeriodPreset): void {
    if (period === 'custom') {
      // Seeds the custom fields from the currently-resolved range rather
      // than leaving them at whatever they last held, so switching to
      // "Custom" reads as "start from what you were just looking at".
      this.state.setCustomRange(this.state.range());
      return;
    }
    this.state.setPeriod(period);
  }

  protected onCustomFromChange(value: string): void {
    this.state.setCustomRange({ ...this.state.customRange(), from: value });
  }

  protected onCustomToChange(value: string): void {
    this.state.setCustomRange({ ...this.state.customRange(), to: value });
  }

  protected selectFulfilmentType(type: 'ALL' | 'DELIVERY' | 'PICKUP' | 'DINE_IN'): void {
    this.state.setFulfilmentType(type);
  }

  protected selectGranularity(granularity: Granularity): void {
    this.state.setGranularity(granularity);
  }

  protected onLocationsChange(selected: readonly ComboboxOption[]): void {
    this.state.setLocationIds(selected.map((option) => option.id));
  }

  protected onChannelsChange(selected: readonly ComboboxOption[]): void {
    this.state.setChannelCodes(selected.map((option) => option.id));
  }

  protected onLegalEntitiesChange(selected: readonly ComboboxOption[]): void {
    this.state.setLegalEntityIds(selected.map((option) => option.id));
  }

  protected onPaymentMethodsChange(selected: readonly ComboboxOption[]): void {
    this.state.setPaymentMethodCodes(selected.map((option) => option.id));
  }

  /**
   * Statistics.md §1.1's arrow-key stepping. Bound to the period row itself
   * so it only fires while a manager's focus is actually on the filter bar,
   * never as a page-wide shortcut that would fight a text field elsewhere.
   */
  protected onPeriodRowKeydown(event: KeyboardEvent): void {
    if (event.key === 'ArrowLeft') {
      event.preventDefault();
      this.state.stepPeriod(-1);
    } else if (event.key === 'ArrowRight') {
      event.preventDefault();
      this.state.stepPeriod(1);
    }
  }

  protected onChipRemoved(chipId: string): void {
    if (chipId === 'fulfilment') {
      this.state.setFulfilmentType('ALL');
    } else if (chipId === 'granularity') {
      this.state.setGranularity('day');
    } else if (chipId.startsWith('location:')) {
      const id = chipId.slice('location:'.length);
      this.state.setLocationIds(this.state.locationIds().filter((existing) => existing !== id));
    } else if (chipId.startsWith('channel:')) {
      const id = chipId.slice('channel:'.length);
      this.state.setChannelCodes(this.state.channelCodes().filter((existing) => existing !== id));
    } else if (chipId.startsWith('entity:')) {
      const id = chipId.slice('entity:'.length);
      this.state.setLegalEntityIds(
        this.state.legalEntityIds().filter((existing) => existing !== id),
      );
    } else if (chipId.startsWith('payment:')) {
      const id = chipId.slice('payment:'.length);
      this.state.setPaymentMethodCodes(
        this.state.paymentMethodCodes().filter((existing) => existing !== id),
      );
    }
  }

  protected onResetFilters(): void {
    this.state.resetFilters();
  }

  private async loadFilterOptions(): Promise<void> {
    await this.location.ensureLoaded();
    const scope = this.location.scope();
    if (!scope) {
      return;
    }
    const [locations, channels, legalEntities, paymentMethods] = await Promise.all([
      this.locationsApi.list(scope).catch(() => [] as readonly LocationView[]),
      this.channelsApi.list(scope).catch(() => [] as readonly ChannelView[]),
      this.fiscalizationApi.listLegalEntities(scope).catch(() => [] as readonly LegalEntityView[]),
      this.paymentMethodsApi.list(scope).catch(() => [] as readonly PaymentMethodView[]),
    ]);
    this.locations.set(locations);
    this.channels.set(channels);
    this.legalEntities.set(legalEntities.filter((entity) => entity.status === 'ACTIVE'));
    this.paymentMethods.set(
      paymentMethods
        .filter((method) => method.status === 'ACTIVE')
        .sort((a, b) => a.sortOrder - b.sortOrder),
    );
  }
}
