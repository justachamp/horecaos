import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';

import { ApiError } from '../../core/api/problem-details';
import { CurrentLocation, LocationOption } from '../../core/auth/current-location';
import { TimeZone, formatClock, formatDateTime } from '../../core/format/datetime';
import { I18n } from '../../core/i18n/i18n';
import { TPipe } from '../../core/i18n/t.pipe';
import { describeApiError } from '../orders/order-errors';
import { Toasts } from '../../shared/ui/toast';
import {
  CoarseCourier,
  CourierPin,
  CourierPositionsApi,
  TrackRevealResponse,
} from './courier-positions-api';
import { TrackRevealDialog, TrackRevealSubmission } from './track-reveal-dialog';

/** couriers.md §4: "10s refresh". */
const POLL_INTERVAL_MS = 10_000;

/** See `order-queue.ts`'s identical constant — no location carries a timezone on any response this board reaches yet. */
const PLACEHOLDER_TIME_ZONE: TimeZone = 'Asia/Tashkent';

/**
 * IA 3.2 — Live map: every on-duty courier, one canvas.
 *
 * **Built**: `OperationsCourierPositionController.fleet` (ADR 0045, already
 * real — see couriers.md §1's own "half of this section is buildable now"
 * split), 10s refresh, active order count, device battery level, the honest
 * coarse-courier band for a courier on duty whose fix is too old or too
 * imprecise to draw, `accuracyMeters`/`headingDegrees`/`speedMps` (on the wire
 * since ADR 0045 and dropped by this table until now), a branch filter, and
 * the audited stored-track reveal (`courier.track.reveal`,
 * `CourierTrackRevealService`) — its path helper had zero call sites before
 * this wave, so a stored track could not be opened for a dispute.
 *
 * **The branch filter reuses `CurrentLocation.selectLocation`** — the same
 * switcher `shell.html`'s topbar already renders — rather than holding a
 * second, page-local "which branch" signal that could disagree with it. A
 * manager scoped above one branch sees one picker, wherever they open it
 * from, and it never drifts between this page and the shell.
 *
 * **Reduced relative to the spec, deliberately.** IA Part 4's own
 * component-gap list names `MapCanvas` as not built at all — no map
 * primitive exists in this design system. This renders the same fleet read
 * as a coordinate table instead of a canvas, the same honest reduction
 * `dispatch-board-page.ts` takes for its own "map of points and routes".
 * **Not built**: zone and work-state filters (couriers.md §4 also asks for
 * these; only the branch filter is this wave's row) and
 * in-house-vs-provider-courier distinction — every courier
 * `OperationsCourierPositionController` reads is in-house; ADR 0045 records
 * partner couriers with no position at all as a rejected alternative, not a
 * gap.
 *
 * **The reveal renders what it decrypts, not a route.** With no map canvas,
 * a revealed window is a list of timestamped points rather than a drawn
 * path — the same honest-reduction stance the fleet table itself takes.
 */
@Component({
  selector: 'q-live-map-page',
  imports: [TPipe, TrackRevealDialog],
  templateUrl: './live-map-page.html',
  styleUrl: './live-map-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class LiveMapPage implements OnInit {
  private readonly positions = inject(CourierPositionsApi);
  private readonly location = inject(CurrentLocation);
  private readonly toasts = inject(Toasts);
  protected readonly i18n = inject(I18n);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly pins = signal<readonly CourierPin[]>([]);
  protected readonly withoutPin = signal<readonly CoarseCourier[]>([]);
  protected readonly firstLoadComplete = signal(false);
  protected readonly denied = signal(false);
  protected readonly lastError = signal<ApiError | null>(null);
  protected readonly lastUpdatedAt = signal<Date | null>(null);

  /** Hidden below two options — same "a picker with nothing to switch between is noise" as `shell.html`. */
  protected readonly branchOptions = computed<readonly LocationOption[]>(() =>
    this.location.options(),
  );
  protected readonly selectedBranchId = computed(() => this.location.scope()?.locationId ?? null);

  protected readonly revealCourierId = signal<string | null>(null);
  protected readonly revealBusy = signal(false);
  protected readonly revealErrorText = signal<string | null>(null);
  protected readonly revealResult = signal<TrackRevealResponse | null>(null);

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

  /** The branch filter — switches the same shared `CurrentLocation` the shell's own picker reads. */
  protected onBranchChange(locationId: string): void {
    if (!locationId) {
      return;
    }
    this.location.selectLocation(locationId);
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

  /** SI unit symbol, not a translated word — «20 m» reads the same in every catalogue, like «km/h» already does. */
  protected accuracyLabel(pin: CourierPin): string {
    return `±${Math.round(pin.accuracyMeters)} m`;
  }

  protected headingLabel(pin: CourierPin): string {
    return pin.headingDegrees === null || pin.headingDegrees === undefined
      ? '—'
      : `${Math.round(pin.headingDegrees)}°`;
  }

  protected speedLabel(pin: CourierPin): string {
    return pin.speedMps === null || pin.speedMps === undefined
      ? '—'
      : `${pin.speedMps.toFixed(1)} m/s`;
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

  protected openReveal(courierId: string): void {
    this.revealCourierId.set(courierId);
    this.revealErrorText.set(null);
  }

  protected closeReveal(): void {
    if (this.revealBusy()) {
      return;
    }
    this.revealCourierId.set(null);
  }

  protected async submitReveal(submission: TrackRevealSubmission): Promise<void> {
    const scope = this.location.scope();
    const courierId = this.revealCourierId();
    if (!scope || !courierId) {
      return;
    }
    this.revealBusy.set(true);
    this.revealErrorText.set(null);
    try {
      const result = await this.positions.revealTrack(
        scope,
        courierId,
        submission.from,
        submission.to,
        submission.purpose,
      );
      this.revealResult.set(result);
      this.revealCourierId.set(null);
      this.toasts.show({
        message: this.i18n.t('delivery.liveMap.reveal.success', {
          windows: result.windows.length,
        }),
        tone: 'success',
      });
    } catch (error) {
      if (error instanceof ApiError) {
        this.revealErrorText.set(describeApiError(error, (key, values) => this.i18n.t(key, values)));
      } else {
        throw error;
      }
    } finally {
      this.revealBusy.set(false);
    }
  }

  protected dismissRevealResult(): void {
    this.revealResult.set(null);
  }

  protected windowLabel(iso: string): string {
    return formatDateTime(new Date(iso), PLACEHOLDER_TIME_ZONE);
  }
}
