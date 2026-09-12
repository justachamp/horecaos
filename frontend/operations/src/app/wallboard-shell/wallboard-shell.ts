import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  inject,
  signal,
} from '@angular/core';

import { ApiError } from '../core/api/problem-details';
import { CurrentLocation } from '../core/auth/current-location';
import { I18n } from '../core/i18n/i18n';
import { TPipe } from '../core/i18n/t.pipe';
import { describeApiError, errorReference } from '../features/orders/order-errors';
import { BranchLoad, LiveBoard, LiveBoardSnapshot, MixSlice } from '../features/today/live-board';
import { WallboardTile } from './wallboard-tile';

/** Same cadence as `today-page.ts` — this reads the identical `LiveBoard` on the identical schedule. */
const POLL_INTERVAL_MS = 10_000;

/** One missed tick still reads as live at a glance. */
const FRESH_THRESHOLD_MS = POLL_INTERVAL_MS * 1.5;
/** Past this the board has not merely slowed down, it has stopped — the banner says so in words, not only in colour. */
const STALE_THRESHOLD_MS = POLL_INTERVAL_MS * 3;
/** Its own one-second clock, independent of the ten-second poll: a hung request must turn the banner amber and then red without waiting for a poll tick that a broken connection will never deliver. */
const CLOCK_TICK_MS = 1_000;

export type WallboardFreshness = 'loading' | 'fresh' | 'aging' | 'stale';

/**
 * IA `0.1e` / `X/X.3` — the wallboard: `0.1`'s Live board, presented for a
 * screen mounted above the pass and read from several metres away instead of
 * a desk.
 *
 * **A shell over `LiveBoard`, not a rewrite of it.** Every number here comes
 * from `LiveBoard.load` — the same one request a tick `today-page.ts` already
 * makes — rendered at the TV-distance type step (`.q-display-tv`, added to
 * `frontend/design-tokens/tokens.css` by this wave) instead of the
 * desk-distance scale that tops out at `.q-display`'s 42px. No new endpoint
 * and no new query: `ORDER_READ` and `LOCATION_READ` are exactly what the
 * desk view already holds, at the same location scope `CurrentLocation`
 * already resolves.
 *
 * **Outside the console `Shell`, on purpose.** `app.routes.ts` declares
 * `/wallboard` as a sibling of the shell's own route, not a child of it, so
 * this renders no rail and no top bar — a supervisor's TV has no keyboard to
 * drive eleven rail entries with and no brand switcher to click. This is the
 * third template `frontend-information-architecture.md`'s "Templates
 * needed" section names — operator console, device/KDS fullscreen, wallboard
 * — and the first of the three built as a distinct shell rather than reusing
 * the operator console at a different type scale.
 *
 * **The freshness stamp is the fix, not a decoration.** The desk view already
 * renders one (`today-page.ts`'s `.today__stamp`) — at `.q-caption`, 12px,
 * illegible from the pass. That is exactly the defect `X/X.3` names: "a
 * frozen wallboard is indistinguishable from a quiet shift". {@link
 * freshnessLabel} renders the same fact — how long ago the board last heard
 * from the server — at the same `.q-display-tv` step as the counters,
 * coloured by {@link freshnessState}, and ticks its own one-second clock
 * ({@link CLOCK_TICK_MS}) independent of the ten-second poll so a hung
 * request still turns the banner red without waiting for a poll tick that
 * will never arrive.
 *
 * **The operator leaderboard stays a locked note.** Nothing here builds IA
 * `0.1d` — no staff person record exists to turn `acceptedByActorId` into a
 * display name (see `live-board.ts`'s own doc) — so the operators band
 * renders the same honest "not yet" `today-page.ts` renders, not a Keycloak
 * subject id blown up to TV size.
 *
 * **Unattended fullscreen.** A shift supervisor sets this up once and walks
 * away for the rest of service; nothing in this codebase called
 * `requestFullscreen` before this wave. {@link enterFullscreen} is that one
 * call, behind a button a human presses once — the Fullscreen API refuses to
 * be invoked without a user gesture, so "fully unattended from page load" is
 * not a thing any browser lets this shell be; "unattended once someone taps
 * the button" is, and is what a real wallboard deployment needs anyway.
 */
@Component({
  selector: 'q-wallboard-shell',
  imports: [TPipe, WallboardTile],
  templateUrl: './wallboard-shell.html',
  styleUrl: './wallboard-shell.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class WallboardShell implements OnInit {
  private readonly location = inject(CurrentLocation);
  private readonly liveBoard = inject(LiveBoard);
  protected readonly i18n = inject(I18n);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly snapshot = signal<LiveBoardSnapshot | null>(null);
  protected readonly lastUpdatedAt = signal<Date | null>(null);
  protected readonly firstLoadComplete = signal(false);
  protected readonly lastError = signal<ApiError | null>(null);
  protected readonly denied = signal(false);
  /** Ticks every {@link CLOCK_TICK_MS} so {@link freshnessState} ages even between polls. */
  protected readonly now = signal(Date.now());
  protected readonly isFullscreen = signal(false);

  private pollHandle: ReturnType<typeof setInterval> | null = null;
  private clockHandle: ReturnType<typeof setInterval> | null = null;

  private readonly onVisibilityChange = (): void => {
    if (document.visibilityState === 'visible') {
      void this.refresh();
    }
  };

  private readonly onFullscreenChange = (): void => {
    this.isFullscreen.set(document.fullscreenElement !== null);
  };

  ngOnInit(): void {
    document.addEventListener('visibilitychange', this.onVisibilityChange);
    document.addEventListener('fullscreenchange', this.onFullscreenChange);

    this.pollHandle = setInterval(() => {
      if (document.visibilityState === 'visible') {
        void this.refresh();
      }
    }, POLL_INTERVAL_MS);

    this.clockHandle = setInterval(() => this.now.set(Date.now()), CLOCK_TICK_MS);

    this.destroyRef.onDestroy(() => {
      document.removeEventListener('visibilitychange', this.onVisibilityChange);
      document.removeEventListener('fullscreenchange', this.onFullscreenChange);
      if (this.pollHandle !== null) {
        clearInterval(this.pollHandle);
      }
      if (this.clockHandle !== null) {
        clearInterval(this.clockHandle);
      }
    });

    void this.start();
  }

  private async start(): Promise<void> {
    await this.location.ensureLoaded();
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
      const snapshot = await this.liveBoard.load(scope);
      this.snapshot.set(snapshot);
      this.lastUpdatedAt.set(new Date());
      this.lastError.set(null);
      this.denied.set(false);
    } catch (error) {
      if (error instanceof ApiError) {
        if (error.status === 403) {
          this.denied.set(true);
          this.lastError.set(null);
        } else {
          this.lastError.set(error);
        }
      } else {
        throw error;
      }
    } finally {
      this.firstLoadComplete.set(true);
    }
  }

  /**
   * The unattended entry point: nothing in this codebase called
   * `requestFullscreen` before this wave — see the class doc. Best-effort —
   * a browser can refuse (no permission, no user-activation in this exact
   * call stack) and the wallboard keeps running windowed either way.
   */
  protected async enterFullscreen(): Promise<void> {
    const root = document.documentElement;
    if (typeof root.requestFullscreen !== 'function') {
      return;
    }
    try {
      await root.requestFullscreen();
    } catch {
      // Refused — stays windowed. Nothing else to do about it here.
    }
  }

  protected inProgressCount(): number {
    return this.snapshot()?.counts.totalNonTerminal ?? 0;
  }

  protected cancelledCount(): number {
    return this.snapshot()?.counts.cancelled ?? 0;
  }

  protected sourceMix(): readonly MixSlice[] {
    return this.snapshot()?.sourceMix ?? [];
  }

  protected typeMix(): readonly MixSlice[] {
    return this.snapshot()?.typeMix ?? [];
  }

  protected branches(): readonly BranchLoad[] {
    return this.snapshot()?.branches ?? [];
  }

  protected branchesAvailable(): boolean {
    return this.snapshot()?.branchesAvailable ?? false;
  }

  /** The bar width for one mix slice, as a share of its own band — same rule as `today-page.ts`. */
  protected mixShare(slice: MixSlice, band: readonly MixSlice[]): number {
    const total = band.reduce((sum, entry) => sum + entry.count, 0);
    return total === 0 ? 0 : Math.round((slice.count / total) * 100);
  }

  /** Channel codes are tenant data and render raw; only the fulfilment-mode mix needs translation — same rule as `today-page.ts`. */
  protected typeMixLabel(mode: string): string {
    switch (mode) {
      case 'DELIVERY':
        return this.i18n.t('orders.fulfillmentMode.DELIVERY');
      case 'PICKUP':
        return this.i18n.t('orders.fulfillmentMode.PICKUP');
      case 'DINE_IN':
        return this.i18n.t('orders.fulfillmentMode.DINE_IN');
      default:
        return mode;
    }
  }

  /**
   * {@link WallboardFreshness}, derived purely from elapsed time since the
   * last successful load, ticked by {@link now}. A stalled poll — network
   * down, a rejected request, a hung tab — shows up here on its own: nothing
   * bumps {@link lastUpdatedAt}, so the age keeps growing past the two
   * thresholds and the banner degrades from green to amber to red without
   * needing a separate "connection" flag to fall out of sync with reality.
   */
  protected freshnessState(): WallboardFreshness {
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

  /**
   * Rendered at the TV type step (`.q-display-tv`) — see this class's own
   * doc for why that is the fix and not a style choice.
   */
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

  protected errorMessage(error: ApiError): string {
    return describeApiError(error, (key, values) => this.i18n.t(key, values));
  }

  protected errorReference(error: ApiError): string {
    return errorReference(error);
  }
}
