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
import { describeApiError, errorReference } from '../orders/order-errors';
import { BranchLoad, LiveBoard, LiveBoardSnapshot, MixSlice } from './live-board';

/** §1.6's polling fallback, same interval as the order board until ADR 0045's COUNTERS stream is wired into a client. */
const POLL_INTERVAL_MS = 10_000;

/** Same placeholder as `order-queue.ts` — no call in this chain returns a tenant timezone yet. */
const PLACEHOLDER_TIME_ZONE: TimeZone = 'Asia/Tashkent';

/**
 * IA 0.1 — Live board: the shift supervisor's wall of numbers.
 *
 * **What this screen is not.** Not a wallboard shell — Part 4 of the IA lists
 * `WallboardTile`/TV-distance scale as a component that does not exist yet,
 * and this renders inside the same operator console shell as every other
 * screen, using `.q-display` (the closed type scale's largest step) for the
 * two headline counters rather than inventing an off-scale font size. Not a
 * report — 7.1 Business overview owns "how did today go" against ADR 0043's
 * closed, versioned facts; this owns "what does right now look like", which
 * `docs/operations-spec/statistics.md` §0 and §3 both say by name belongs
 * here and nowhere else.
 *
 * **Where every number comes from**, because none of it is computed twice:
 * `LiveBoard` composes the same `GET .../orders/counts` the order board's
 * own tab badges already trust, the same order list endpoint the board
 * already polls (filtered to `order-status.ts`'s canonical
 * `IN_PROGRESS_ORDER_STATUSES`), and the brand's location roster Settings
 * 10.2 already reads. See `live-board.ts` for the full accounting and the
 * three-step degrade a partial grant produces.
 */
@Component({
  selector: 'q-today-page',
  imports: [TPipe],
  templateUrl: './today-page.html',
  styleUrl: './today-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class TodayPage implements OnInit {
  private readonly location = inject(CurrentLocation);
  private readonly liveBoard = inject(LiveBoard);
  private readonly i18n = inject(I18n);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly snapshot = signal<LiveBoardSnapshot | null>(null);
  protected readonly lastUpdatedAt = signal<Date | null>(null);
  protected readonly firstLoadComplete = signal(false);
  protected readonly refreshing = signal(false);
  protected readonly lastError = signal<ApiError | null>(null);
  protected readonly denied = signal(false);

  private pollHandle: ReturnType<typeof setInterval> | null = null;
  private readonly onVisibilityChange = (): void => {
    if (document.visibilityState === 'visible') {
      void this.refresh();
    }
  };

  ngOnInit(): void {
    document.addEventListener('visibilitychange', this.onVisibilityChange);

    this.pollHandle = setInterval(() => {
      if (document.visibilityState === 'visible') {
        void this.refresh();
      }
    }, POLL_INTERVAL_MS);

    this.destroyRef.onDestroy(() => {
      document.removeEventListener('visibilitychange', this.onVisibilityChange);
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

  /** Also the manual refresh control and the error band's retry button. */
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

    this.refreshing.set(true);
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
      this.refreshing.set(false);
      this.firstLoadComplete.set(true);
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

  protected branchesShown(): number {
    return this.snapshot()?.branchesShown ?? 0;
  }

  protected branchesTotal(): number {
    return this.snapshot()?.branchesTotal ?? 0;
  }

  /** orders.md §2.11's "Показаны N из M" state, carried over from the branch filter to this table. */
  protected branchesPartial(): boolean {
    return this.branchesAvailable() && this.branchesShown() < this.branchesTotal();
  }

  /** The bar width for one mix slice, as a share of its own band — never of the other band's total. */
  protected mixShare(slice: MixSlice, band: readonly MixSlice[]): number {
    const total = band.reduce((sum, entry) => sum + entry.count, 0);
    return total === 0 ? 0 : Math.round((slice.count / total) * 100);
  }

  /**
   * Channel codes are tenant data (`order-queue.ts`'s `typeLabel` renders
   * them raw for the same reason: i18n's own rule that content names are
   * never keys) — only the fulfilment-mode mix needs a translated label.
   */
  protected typeMixLabel(mode: string): string {
    switch (mode) {
      case 'DELIVERY':
        return this.i18n.t('orders.fulfillmentMode.DELIVERY');
      case 'PICKUP':
        return this.i18n.t('orders.fulfillmentMode.PICKUP');
      case 'DINE_IN':
        return this.i18n.t('orders.fulfillmentMode.DINE_IN');
      default:
        // Unknown fulfilment mode, or the em-dash bucket for "none on the order" — render harmlessly.
        return mode;
    }
  }

  protected formatUpdatedAt(): string | null {
    const updated = this.lastUpdatedAt();
    return updated
      ? this.i18n.t('today.updated', { time: formatClock(updated, PLACEHOLDER_TIME_ZONE) })
      : null;
  }

  protected errorMessage(error: ApiError): string {
    return describeApiError(error, (key, values) => this.i18n.t(key, values));
  }

  protected errorReference(error: ApiError): string {
    return errorReference(error);
  }
}
