import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  computed,
  inject,
  signal,
  viewChild,
} from '@angular/core';

import { ApiError } from '../../core/api/problem-details';
import { CurrentLocation, LocationOption } from '../../core/auth/current-location';
import { TimeZone, formatClock, formatDateTime } from '../../core/format/datetime';
import { I18n } from '../../core/i18n/i18n';
import { TPipe } from '../../core/i18n/t.pipe';
import { MessageKey } from '../../core/i18n/messages.en';
import { CouriersApi } from '../couriers/couriers-api';
import { describeApiError } from '../orders/order-errors';
import { boundsOf } from '../../shared/ui/map/geometry';
import { MapArea, MapCanvas, MapMarker } from '../../shared/ui/map/map-canvas';
import { LatLng, MapBounds } from '../../shared/ui/map/map-provider';
import { Toasts } from '../../shared/ui/toast';
import {
  CoarseCourier,
  CourierPin,
  CourierPositionsApi,
  TrackRevealResponse,
} from './courier-positions-api';
import { DeliveryZonesApi, ZoneOutlineResponse } from './delivery-zones-api';
import { FALLBACK_MAP_CENTRE, MapRegionService } from './map-region';
import { TrackRevealDialog, TrackRevealSubmission } from './track-reveal-dialog';
import { outerRings } from './zone-geometry';

/** couriers.md §4: "10s refresh". */
const POLL_INTERVAL_MS = 10_000;

/**
 * couriers.md §4 "Filters": `On shift` / `Carrying` / `Idle` / `No signal`. Each courier in the fleet
 * is in exactly one of the last three: no signal is the band the platform could not honestly draw
 * (whatever it is carrying, because a courier with orders and no signal is the one a dispatcher
 * most needs to find), carrying and idle split the rest by `activeAssignmentCount`. `ON_SHIFT` is
 * everyone, which is also what the screen showed before there were filters.
 */
export type WorkState = 'ON_SHIFT' | 'CARRYING' | 'IDLE' | 'NO_SIGNAL';

export const WORK_STATES: readonly WorkState[] = ['ON_SHIFT', 'CARRYING', 'IDLE', 'NO_SIGNAL'];

const WORK_STATE_KEYS: Readonly<Record<WorkState, MessageKey>> = {
  ON_SHIFT: 'delivery.liveMap.state.ON_SHIFT',
  CARRYING: 'delivery.liveMap.state.CARRYING',
  IDLE: 'delivery.liveMap.state.IDLE',
  NO_SIGNAL: 'delivery.liveMap.state.NO_SIGNAL',
};

/**
 * What to call a courier when the roster cannot say: the first eight characters of the id. A
 * reference is what a person recognises, but the roster takes `courier.read`, which a caller with
 * only `courier.position.read` does not hold, and a blank cell would be worse than a short code.
 */
function shortId(courierId: string): string {
  return courierId.length > 12 ? courierId.slice(0, 8) : courierId;
}

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
 * **On a map (ADR 0145, row `3.2`).** Every drawable courier is a pin on the map above the table,
 * keyed by courier so a courier who moved is one pin that moved, and the brand's live delivery
 * zones are drawn under them (read-only: a zone is edited on the zones page). The table stays: it
 * carries accuracy, heading, speed, battery and the audited reveal, none of which a pin shows, and
 * it is what an environment with no map provider has. The couriers on duty whose fix is too old or
 * too imprecise to draw are not on the map and are listed below it with the reason, never placed
 * on a guess. A pin says the courier's reference and how many orders they carry, never a name
 * (ADR 0029: the protected name is behind its own reveal).
 *
 * **Couriers by reference (row 3.2, batch 21).** A pin, a table row, a no-signal row and the title of
 * a revealed track say the courier's `displayReference` from the roster, as the dispatch board does,
 * and not the UUID the position read carries. A caller without `courier.read` still has the fleet
 * and sees a short id instead.
 *
 * **Work-state filters (couriers.md §4).** `On shift`, `Carrying`, `Idle`, `No signal`, each with a
 * count; the choice holds through the ten-second refresh and applies to the map and the table alike.
 *
 * **Not a gap**: in-house-vs-provider-courier distinction — every courier
 * `OperationsCourierPositionController` reads is in-house; ADR 0045 records partner couriers with
 * no position at all as a rejected alternative.
 *
 * **The reveal renders what it decrypts, not a route.** A revealed window is a list of timestamped
 * points rather than a drawn path: drawing one stored track on the live map would put a person's
 * movement history next to everyone's present position, which is the combination ADR 0045 keeps
 * apart.
 */
@Component({
  selector: 'q-live-map-page',
  imports: [TPipe, TrackRevealDialog, MapCanvas],
  templateUrl: './live-map-page.html',
  styleUrl: './live-map-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class LiveMapPage implements OnInit {
  private readonly positions = inject(CourierPositionsApi);
  private readonly couriersApi = inject(CouriersApi);
  private readonly location = inject(CurrentLocation);
  private readonly toasts = inject(Toasts);
  private readonly zonesApi = inject(DeliveryZonesApi);
  private readonly regions = inject(MapRegionService);
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

  // ------------------------------------------------------------- the map
  /** The brand's live zones, drawn under the couriers. Empty for a caller who may not read them. */
  private readonly outlines = signal<readonly ZoneOutlineResponse[]>([]);

  /** Courier id to the reference the roster shows; empty for a caller who may not read the roster. */
  private readonly references = signal<ReadonlyMap<string, string>>(new Map());

  protected readonly workStates = WORK_STATES;
  protected readonly workState = signal<WorkState>('ON_SHIFT');

  /** The pins the chosen work state keeps. `No signal` is the band without a pin, so it keeps none. */
  protected readonly visiblePins = computed<readonly CourierPin[]>(() => {
    const pins = this.pins();
    switch (this.workState()) {
      case 'CARRYING':
        return pins.filter((pin) => pin.activeAssignmentCount > 0);
      case 'IDLE':
        return pins.filter((pin) => pin.activeAssignmentCount === 0);
      case 'NO_SIGNAL':
        return [];
      default:
        return pins;
    }
  });

  protected readonly visibleWithoutPin = computed<readonly CoarseCourier[]>(() =>
    this.workState() === 'ON_SHIFT' || this.workState() === 'NO_SIGNAL' ? this.withoutPin() : [],
  );

  protected readonly workStateCounts = computed<Readonly<Record<WorkState, number>>>(() => {
    const pins = this.pins();
    const carrying = pins.filter((pin) => pin.activeAssignmentCount > 0).length;
    return {
      ON_SHIFT: pins.length + this.withoutPin().length,
      CARRYING: carrying,
      IDLE: pins.length - carrying,
      NO_SIGNAL: this.withoutPin().length,
    };
  });

  protected readonly markers = computed<readonly MapMarker[]>(() =>
    this.visiblePins().map((pin) => ({
      id: pin.courierId,
      position: { latitude: pin.latitude, longitude: pin.longitude },
      label: this.i18n.t('delivery.liveMap.map.pinLabel', {
        courier: this.courierRef(pin.courierId),
        orders: pin.activeAssignmentCount,
      }),
    })),
  );

  protected readonly areas = computed<readonly MapArea[]>(() =>
    this.outlines().flatMap((outline) =>
      outerRings(outline).map((ring, index) => ({
        id: `${outline.zoneId}:${index}`,
        ring,
        label: outline.code,
      })),
    ),
  );

  /**
   * Where the map first opens, fixed when it is built: the couriers' own spread when there are two
   * or more, else the branch's region. Not recomputed on each ten-second refresh: a map that
   * re-fits itself every poll fights a dispatcher who has panned to where they are looking.
   */
  protected readonly openingFit = signal<MapBounds | null>(null);
  protected readonly openingCentre = signal<LatLng>(FALLBACK_MAP_CENTRE);
  protected readonly mapReady = signal(false);
  private readonly canvas = viewChild(MapCanvas);

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
    await Promise.all([
      this.refresh(),
      this.loadZones(),
      this.loadReferences(),
      this.regions.ensureLoaded(),
    ]);
    this.openMap();
  }

  /**
   * The roster, for the references only. Best effort: `courier.read` is a different capability from
   * the one that opens this map, and a dispatcher without it still sees the fleet.
   */
  private async loadReferences(): Promise<void> {
    const scope = this.location.scope();
    if (!scope) {
      return;
    }
    try {
      const roster = await this.couriersApi.roster(scope.tenantId);
      this.references.set(
        new Map(roster.map((entry) => [entry.courierId, entry.displayReference])),
      );
    } catch {
      this.references.set(new Map());
    }
  }

  /** A courier by the reference the roster shows, else by a short id. */
  protected courierRef(courierId: string): string {
    return this.references().get(courierId) ?? shortId(courierId);
  }

  protected stateLabel(state: WorkState): string {
    return this.i18n.t(WORK_STATE_KEYS[state]);
  }

  protected selectState(state: WorkState): void {
    this.workState.set(state);
  }

  /** Best effort: a dispatcher who may not read zones still sees couriers, just not the zones under them. */
  private async loadZones(): Promise<void> {
    const scope = this.location.scope();
    if (!scope) {
      return;
    }
    try {
      this.outlines.set(await this.zonesApi.activeOutlines(scope));
    } catch {
      this.outlines.set([]);
    }
  }

  private openMap(): void {
    const pins = this.pins().map((pin) => ({ latitude: pin.latitude, longitude: pin.longitude }));
    const region = this.regions.primary();
    this.openingFit.set(pins.length >= 2 ? boundsOf(pins) : null);
    this.openingCentre.set(pins[0] ?? region?.centre ?? FALLBACK_MAP_CENTRE);
    this.mapReady.set(true);
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
    void this.refresh().then(() => this.showBranchFleet());
  }

  /** A different branch is a different place: bring its couriers into view, once, when it is chosen. */
  private showBranchFleet(): void {
    const points = this.pins().map((pin) => ({
      latitude: pin.latitude,
      longitude: pin.longitude,
    }));
    if (points.length >= 2) {
      this.openingFit.set(boundsOf(points));
    } else if (points.length === 1) {
      this.canvas()?.recenter(points[0], 14);
    }
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
        this.revealErrorText.set(
          describeApiError(error, (key, values) => this.i18n.t(key, values)),
        );
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
