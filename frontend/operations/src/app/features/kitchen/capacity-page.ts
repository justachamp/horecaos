import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { CurrentLocation } from '../../core/auth/current-location';
import { I18n } from '../../core/i18n/i18n';
import { TPipe } from '../../core/i18n/t.pipe';
import { ApiError } from '../../core/api/problem-details';
import { describeApiError } from '../orders/order-errors';
import { CapacityApi, CapacityWindowResponse, NewCapacityWindow } from './capacity-api';
import { KitchenApi, StationResponse } from './kitchen-api';

const WEEKDAYS: readonly number[] = [1, 2, 3, 4, 5, 6, 7];

/**
 * IA §2.6 — Capacity & buffer settings. `docs/frontend-information-architecture.md`
 * describes the row as "max preparations per hour per product per branch;
 * cook headcount output"; ADR 0041 had already decided a coarser, station-level
 * shape (`kitchen.station_capacity` — station, weekday, local time window,
 * portions per hour) and deliberately left it unbuilt: "Configuration no code
 * reads is worse than no configuration." That objection was about one specific
 * reader — the release scheduler's `station_queue_offset`, which this wave
 * still does not build (see `KitchenStationService.createCapacityWindow`'s own
 * doc) — not about this screen, which is itself a real reader: a manager
 * comparing a station's ceiling against the board by eye.
 *
 * **Built (Card 1)**: throughput ceilings — create and list, per station, per
 * weekday, per local time window, in portions per hour. There is no edit or
 * delete in this release, the same discipline `kitchen-station-service.ts`'s
 * `routing-rules` already keeps: nothing here is ever removed once created.
 *
 * **Not built (Card 2), and named rather than faked**: "cook headcount
 * output." Computing a cook count needs two things nothing in this build
 * supplies — a demand forecast (there is no forecasting model anywhere in the
 * platform; `statistics.md`'s own scope excludes it) and a policy for how many
 * portions one cook produces an hour (a business decision nobody has made,
 * varies by station and cuisine, and is not this wave's to invent). Delever's
 * own "Прогнозирование" is marked unverified in the parity matrix for the same
 * reason. This card says so rather than rendering a number this build cannot
 * honestly compute.
 */
@Component({
  selector: 'q-capacity-page',
  imports: [TPipe],
  templateUrl: './capacity-page.html',
  styleUrl: './capacity-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CapacityPage implements OnInit {
  private readonly location = inject(CurrentLocation);
  private readonly capacityApi = inject(CapacityApi);
  private readonly kitchenApi = inject(KitchenApi);
  protected readonly i18n = inject(I18n);

  protected readonly WEEKDAYS = WEEKDAYS;

  protected readonly firstLoadComplete = signal(false);
  protected readonly denied = signal(false);
  protected readonly lastError = signal<ApiError | null>(null);

  protected readonly stations = signal<readonly StationResponse[]>([]);
  protected readonly windows = signal<readonly CapacityWindowResponse[]>([]);

  protected readonly stationsById = computed(
    () => new Map(this.stations().map((station) => [station.stationId, station])),
  );

  protected readonly formStationId = signal('');
  protected readonly formWeekday = signal(1);
  protected readonly formFrom = signal('09:00');
  protected readonly formTo = signal('12:00');
  protected readonly formPortionsPerHour = signal(20);
  protected readonly formTouched = signal(false);
  protected readonly formSubmitting = signal(false);
  protected readonly formError = signal<string | null>(null);

  protected readonly formValid = computed(
    () => this.formStationId() !== '' && this.formPortionsPerHour() > 0 && this.formTo() > this.formFrom(),
  );

  async ngOnInit(): Promise<void> {
    await this.location.ensureLoaded();
    const scope = this.location.scope();
    if (!scope) {
      this.denied.set(this.location.denied());
      this.firstLoadComplete.set(true);
      return;
    }
    try {
      const [stations, windows] = await Promise.all([
        this.kitchenApi.stations(scope),
        this.capacityApi.list(scope),
      ]);
      this.stations.set(stations);
      this.windows.set(windows);
      if (stations.length > 0) {
        this.formStationId.set(stations[0].stationId);
      }
      this.denied.set(false);
      this.lastError.set(null);
    } catch (error) {
      if (error instanceof ApiError && error.status === 403) {
        this.denied.set(true);
        this.lastError.set(null);
      } else if (error instanceof ApiError) {
        this.lastError.set(error);
      } else {
        throw error;
      }
    } finally {
      this.firstLoadComplete.set(true);
    }
  }

  protected stationLabel(stationId: string): string {
    const station = this.stationsById().get(stationId);
    if (!station) {
      return stationId;
    }
    switch (this.i18n.locale()) {
      case 'uz-Latn':
        return station.displayNameUz;
      case 'en':
        return station.displayNameEn;
      default:
        return station.displayNameRu;
    }
  }

  protected weekdayLabel(weekday: number): string {
    switch (weekday) {
      case 1:
        return this.i18n.t('kitchen.capacity.weekday.1');
      case 2:
        return this.i18n.t('kitchen.capacity.weekday.2');
      case 3:
        return this.i18n.t('kitchen.capacity.weekday.3');
      case 4:
        return this.i18n.t('kitchen.capacity.weekday.4');
      case 5:
        return this.i18n.t('kitchen.capacity.weekday.5');
      case 6:
        return this.i18n.t('kitchen.capacity.weekday.6');
      case 7:
        return this.i18n.t('kitchen.capacity.weekday.7');
      default:
        return String(weekday);
    }
  }

  protected windowLabel(window: CapacityWindowResponse): string {
    return `${window.windowStart.slice(0, 5)}–${window.windowEnd.slice(0, 5)}`;
  }

  protected sortedWindows(): readonly CapacityWindowResponse[] {
    return [...this.windows()].sort((a, b) => {
      if (a.weekday !== b.weekday) {
        return a.weekday - b.weekday;
      }
      return a.windowStart.localeCompare(b.windowStart);
    });
  }

  protected async submit(): Promise<void> {
    this.formTouched.set(true);
    const scope = this.location.scope();
    if (!scope || !this.formValid()) {
      return;
    }
    const body: NewCapacityWindow = {
      stationId: this.formStationId(),
      weekday: this.formWeekday(),
      windowStart: `${this.formFrom()}:00`,
      windowEnd: `${this.formTo()}:00`,
      portionsPerHour: this.formPortionsPerHour(),
    };
    this.formSubmitting.set(true);
    this.formError.set(null);
    try {
      const created = await firstValueFrom(this.capacityApi.create(scope, body));
      this.windows.update((current) => [...current, created]);
      this.formTouched.set(false);
    } catch (error) {
      this.formError.set(
        error instanceof ApiError
          ? describeApiError(error, (key, values) => this.i18n.t(key, values))
          : this.i18n.t('error.unknown.noReference'),
      );
    } finally {
      this.formSubmitting.set(false);
    }
  }
}
