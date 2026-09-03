import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  inject,
  signal,
} from '@angular/core';

import { ApiError } from '../../core/api/problem-details';
import { CurrentLocation } from '../../core/auth/current-location';
import { TimeZone, formatClock } from '../../core/format/datetime';
import { I18n } from '../../core/i18n/i18n';
import { TPipe } from '../../core/i18n/t.pipe';
import { CoarseCourier, CourierPin, CourierPositionsApi } from './courier-positions-api';

/** couriers.md §4: "10s refresh". */
const POLL_INTERVAL_MS = 10_000;

/** See `order-queue.ts`'s identical constant — no location carries a timezone on any response this board reaches yet. */
const PLACEHOLDER_TIME_ZONE: TimeZone = 'Asia/Tashkent';

/**
 * IA 3.2 — Live map: every on-duty courier, one canvas.
 *
 * **Built**: `OperationsCourierPositionController.fleet` (ADR 0045, already
 * real — see couriers.md §1's own "half of this section is buildable now"
 * split), 10s refresh, active order count, device battery level, and the
 * honest coarse-courier band for a courier on duty whose fix is too old or
 * too imprecise to draw.
 *
 * **Reduced relative to the spec, deliberately.** IA Part 4's own
 * component-gap list names `MapCanvas` as not built at all — no map
 * primitive exists in this design system. This renders the same fleet read
 * as a coordinate table instead of a canvas, the same honest reduction
 * `dispatch-board-page.ts` takes for its own "map of points and routes".
 * **Not built**: the branch filter (this screen is already one branch, per
 * `CurrentLocation`'s own scope) and in-house-vs-provider-courier
 * distinction — every courier `OperationsCourierPositionController` reads is
 * in-house; ADR 0014's provider couriers carry no position at all.
 */
@Component({
  selector: 'q-live-map-page',
  imports: [TPipe],
  templateUrl: './live-map-page.html',
  styleUrl: './live-map-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class LiveMapPage implements OnInit {
  private readonly positions = inject(CourierPositionsApi);
  private readonly location = inject(CurrentLocation);
  protected readonly i18n = inject(I18n);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly pins = signal<readonly CourierPin[]>([]);
  protected readonly withoutPin = signal<readonly CoarseCourier[]>([]);
  protected readonly firstLoadComplete = signal(false);
  protected readonly denied = signal(false);
  protected readonly lastError = signal<ApiError | null>(null);
  protected readonly lastUpdatedAt = signal<Date | null>(null);

  private pollHandle: ReturnType<typeof setInterval> | null = null;

  ngOnInit(): void {
    this.pollHandle = setInterval(() => {
      if (document.visibilityState === 'visible') {
        void this.refresh();
      }
    }, POLL_INTERVAL_MS);
    this.destroyRef.onDestroy(() => {
      if (this.pollHandle !== null) {
        clearInterval(this.pollHandle);
      }
    });
    void this.start();
  }

  private async start(): Promise<void> {
    await this.location.ensureLoaded();
    await this.refresh();
  }

  protected manualRefresh(): void {
    void this.refresh();
  }

  private async refresh(): Promise<void> {
    const scope = this.location.scope();
    if (!scope) {
      this.denied.set(this.location.denied());
      this.firstLoadComplete.set(true);
      return;
    }
    try {
      const fleet = await this.positions.fleet(scope);
      this.pins.set(fleet.pins);
      this.withoutPin.set(fleet.withoutPin);
      this.lastUpdatedAt.set(new Date());
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

  protected coordinateLabel(pin: CourierPin): string {
    return `${pin.latitude.toFixed(5)}, ${pin.longitude.toFixed(5)}`;
  }

  protected capturedAtLabel(iso: string): string {
    return formatClock(new Date(iso), PLACEHOLDER_TIME_ZONE);
  }

  protected batteryLabel(pin: CourierPin): string {
    return pin.batteryPercent === null || pin.batteryPercent === undefined
      ? '—'
      : `${pin.batteryPercent}%${pin.deviceCharging ? ' ⚡' : ''}`;
  }

  protected coarseReasonLabel(reason: string): string {
    switch (reason) {
      case 'ACCURACY_BELOW_MAP_FLOOR':
        return this.i18n.t('delivery.liveMap.coarse.ACCURACY_BELOW_MAP_FLOOR');
      case 'LAST_FIX_TOO_OLD':
        return this.i18n.t('delivery.liveMap.coarse.LAST_FIX_TOO_OLD');
      default:
        return reason;
    }
  }

  protected formatUpdatedAt(): string | null {
    const updated = this.lastUpdatedAt();
    return updated
      ? this.i18n.t('delivery.liveMap.updated', {
          time: formatClock(updated, PLACEHOLDER_TIME_ZONE),
        })
      : null;
  }
}
