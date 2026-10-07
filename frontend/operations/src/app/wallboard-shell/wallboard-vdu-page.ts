import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';

import { ApiError } from '../core/api/problem-details';
import { CurrentLocation } from '../core/auth/current-location';
import { TimeZone } from '../core/format/datetime';
import { LatenessPolicy, PLATFORM_DEFAULT_LATENESS_POLICY } from '../core/lateness-policy';
import { latenessPolicyFromWire } from '../core/lateness-policy-api';
import { I18n } from '../core/i18n/i18n';
import { TPipe } from '../core/i18n/t.pipe';
import { RealtimeClient } from '../core/realtime/realtime-client';
import { ConnectionStateBanner } from '../shared/ui/connection-state-banner';
import { LiveBadge } from '../shared/ui/live-badge';
import { KitchenApi, StationResponse, VduTicketResponse } from '../features/kitchen/kitchen-api';
import { VduWall, VduWallPhase, WallboardVduFreshness } from './vdu-wall';

export type { WallboardVduFreshness } from './vdu-wall';

/** The mandated polling fallback (ADR 0045) — the stream is only ever an accelerator on top of this. */
const POLL_INTERVAL_MS = 10_000;
const FRESH_THRESHOLD_MS = POLL_INTERVAL_MS * 1.5;
const STALE_THRESHOLD_MS = POLL_INTERVAL_MS * 3;
const CLOCK_TICK_MS = 1_000;

/**
 * Every live location this console reaches is Tashkent today — same placeholder `kitchen-queue-page.ts`
 * and `vdu-page.ts` carry. A real wall does not use it: the device shell reads its branch's own zone
 * from `GET /api/v1/devices/me` (ADR 0151).
 */
const PLACEHOLDER_TIME_ZONE: TimeZone = 'Asia/Tashkent';

/**
 * Row 2.4 — the VDU wall projection (ADR 0041 rollout step 4), hosted like
 * the wallboard (`wallboard-shell.ts`, IA `0.1e`) rather than inside the
 * operator console `Shell` the desk `vdu-page.ts` still renders in.
 *
 * **This is the manager's preview of a wall, not the wall (ADR 0151).** It runs on a signed-in staff
 * session, with a station `<select>` a reload forgets, the realtime stream and the station list: all
 * things a wall display does not have. A TV in a kitchen is enrolled as a `KITCHEN_VDU` device from
 * Kitchen → Devices and runs in the device shell (`device/device-shell.ts`), which draws the same
 * {@link VduWall} but reads only its own record and the projection. The page says so on screen.
 *
 * **A dedicated, narrower read**, `KitchenApi.vdu` / `KitchenBoardController
 * .vdu` — not the same `board()` call `vdu-page.ts` and `kitchen-queue-page
 * .ts` share. See `kitchen-api.ts`'s own doc on {@link VduTicketResponse}
 * for exactly what it omits: no order id, no buffer-management fields, no
 * per-line identifiers a read-only wall never needs, no dish name and no
 * note.
 *
 * **The station filter** (this wave's other named gap) is a plain `<select>`
 * over the branch's own stations, re-fetching with `station` set — a wall
 * mounted over one pass shows only that station's lines, and a ticket that
 * never routed there does not appear at all (`KitchenBoardController.vdu`'s
 * own rule).
 *
 * **No controls**, same as the desk `VduPage`: no start/ready/recall
 * anywhere on this page, because a screen read from across the room is not
 * one a customer or a passing cook should be able to act from.
 */
@Component({
  selector: 'q-wallboard-vdu-page',
  imports: [TPipe, ConnectionStateBanner, LiveBadge, VduWall],
  templateUrl: './wallboard-vdu-page.html',
  styleUrl: './wallboard-vdu-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class WallboardVduPage implements OnInit {
  private readonly kitchen = inject(KitchenApi);
  private readonly location = inject(CurrentLocation);
  private readonly realtime = inject(RealtimeClient);
  private readonly destroyRef = inject(DestroyRef);
  protected readonly i18n = inject(I18n);

  protected readonly connectionState = this.realtime.state;

  protected readonly tickets = signal<readonly VduTicketResponse[]>([]);
  protected readonly stations = signal<readonly StationResponse[]>([]);
  protected readonly selectedStation = signal<string>('');
  protected readonly firstLoadComplete = signal(false);
  protected readonly denied = signal(false);
  protected readonly lastUpdatedAt = signal<Date | null>(null);
  protected readonly now = signal(Date.now());

  private pollHandle: ReturnType<typeof setInterval> | null = null;
  private clockHandle: ReturnType<typeof setInterval> | null = null;

  /**
   * The resolved `ordering.lateness` policy, **from the projection itself** (ADR 0151): every poll
   * answers with the policy at the location of the call, so a screen opened once and left up for days
   * follows an edit without a second request, and the page no longer holds a tracker of its own. A
   * response without one (a server that predates ADR 0151) keeps the last policy this page read, or the
   * platform default before the first.
   */
  protected readonly latenessPolicy = signal<LatenessPolicy>(PLATFORM_DEFAULT_LATENESS_POLICY);

  protected readonly timeZone: TimeZone = PLACEHOLDER_TIME_ZONE;

  protected readonly phase = computed<VduWallPhase>(() =>
    this.denied() ? 'denied' : this.firstLoadComplete() ? 'ready' : 'loading',
  );

  protected readonly selectedStationLabel = computed<string | null>(() => {
    const id = this.selectedStation();
    const found = id ? this.stations().find((station) => station.stationId === id) : undefined;
    return found ? this.stationLabel(found) : null;
  });

  ngOnInit(): void {
    this.pollHandle = setInterval(() => void this.refresh(), POLL_INTERVAL_MS);
    this.clockHandle = setInterval(() => this.now.set(Date.now()), CLOCK_TICK_MS);

    const unsubscribeRealtime = this.realtime.onFrame((frame) => {
      if (
        (frame.kind === 'signal' && frame.channel === 'kitchen_board') ||
        frame.kind === 'resync'
      ) {
        void this.refresh();
      }
    });

    this.destroyRef.onDestroy(() => {
      if (this.pollHandle !== null) {
        clearInterval(this.pollHandle);
      }
      if (this.clockHandle !== null) {
        clearInterval(this.clockHandle);
      }
      unsubscribeRealtime();
    });

    void this.start();
  }

  private async start(): Promise<void> {
    await this.location.ensureLoaded();
    const scope = this.location.scope();
    if (scope) {
      try {
        this.stations.set(await this.kitchen.stations(scope));
      } catch {
        // The filter degrades to "all stations" rather than blocking the board.
      }
    }
    await this.refresh();
  }

  private async refresh(): Promise<void> {
    const scope = this.location.scope();
    if (!scope) {
      this.denied.set(this.location.denied());
      this.firstLoadComplete.set(true);
      return;
    }
    try {
      const board = await this.kitchen.vdu(scope, this.selectedStation() || undefined);
      const policy = latenessPolicyFromWire(board.lateness);
      if (policy) {
        this.latenessPolicy.set(policy);
      }
      this.tickets.set(
        [...board.tickets].sort((a, b) => statusRank(a.status) - statusRank(b.status)),
      );
      this.lastUpdatedAt.set(new Date());
      this.denied.set(false);
    } catch (error) {
      if (error instanceof ApiError && error.status === 403) {
        this.denied.set(true);
      }
      // A wall display swallows other errors and keeps the last-known rows —
      // same rule the desk VduPage already follows.
    } finally {
      this.firstLoadComplete.set(true);
    }
  }

  protected onStationChange(stationId: string): void {
    this.selectedStation.set(stationId);
    void this.refresh();
  }

  protected stationLabel(station: StationResponse): string {
    switch (this.i18n.locale()) {
      case 'uz-Latn':
        return station.displayNameUz;
      case 'en':
        return station.displayNameEn;
      default:
        return station.displayNameRu;
    }
  }

  protected freshnessState(): WallboardVduFreshness {
    if (!this.firstLoadComplete()) {
      return 'loading';
    }
    const updated = this.lastUpdatedAt();
    if (!updated) {
      return 'stale';
    }
    const age = this.now() - updated.getTime();
    if (age < FRESH_THRESHOLD_MS) {
      return 'fresh';
    }
    if (age < STALE_THRESHOLD_MS) {
      return 'aging';
    }
    return 'stale';
  }

  protected freshnessLabel(): string {
    const state = this.freshnessState();
    if (state === 'loading') {
      return this.i18n.t('wallboard.freshness.loading');
    }
    const updated = this.lastUpdatedAt();
    const seconds = updated ? Math.max(0, Math.floor((this.now() - updated.getTime()) / 1000)) : 0;
    if (seconds < 60) {
      return this.i18n.t('wallboard.freshness.seconds', { seconds });
    }
    const minutes = Math.floor(seconds / 60);
    return this.i18n.t('wallboard.freshness.minutes', { minutes });
  }
}

const STATUS_RANK: Readonly<Record<string, number>> = {
  READY: 0,
  IN_PRODUCTION: 1,
  FIRED: 2,
};

function statusRank(status: string): number {
  return STATUS_RANK[status] ?? 3;
}
