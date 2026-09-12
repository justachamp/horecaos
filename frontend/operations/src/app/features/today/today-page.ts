import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  inject,
  signal,
} from '@angular/core';
import { RouterLink } from '@angular/router';

import { ApiError } from '../../core/api/problem-details';
import { CurrentLocation } from '../../core/auth/current-location';
import { TimeZone, formatClock, formatDateTime } from '../../core/format/datetime';
import { I18n } from '../../core/i18n/i18n';
import { TPipe } from '../../core/i18n/t.pipe';
import { BarChart } from '../../shared/ui/charts/bar-chart';
import { ChartCategory } from '../../shared/ui/charts/chart-model';
import { describeApiError, errorReference } from '../orders/order-errors';
import { BranchLoad, LiveBoard, LiveBoardSnapshot, MixSlice } from './live-board';

/**
 * §1.6's polling fallback, at the order board's interval.
 *
 * ADR 0045's `COUNTERS` stream is built on the server and has no client — it is
 * not missing, it is unwired, and wave `P08` wires it (IA 0.1f). Until then a
 * number on this board can be up to ten seconds old and nothing distinguishes a
 * quiet branch from a broken connection; the staleness indicator belongs to that
 * wave, not this one.
 */
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
 * `LiveBoard` makes one request a tick — the brand's own counts endpoint, or
 * the location's when the grant stops at one branch — and both answer the
 * counters, the two mixes and the branch loads together. The mixes are exact
 * server-side aggregates, not a count over a fetched page; the branch names
 * come from the location roster Settings 10.2 already reads, held after the
 * first read because a branch does not rename itself between ticks. See
 * `live-board.ts` for the full accounting and the degrade a partial grant
 * produces.
 *
 * **Which period the numbers cover is stated, not assumed.** «Отменено» and
 * «Завершено» are this trading day's, cut at the tenant's own business-day
 * boundary (ADR 0043) rather than at midnight, and the caption under the
 * counters renders where that cut falls — a supervisor reading a counter has
 * to be able to see what it counts.
 *
 * **IA 0.2 (My work) is an honest not-built page, linked from the toolbar
 * here.** Its whole "Owns" list depends on data this build does not have:
 * `created_by_actor_id`/`accepted_by_actor_id` do not exist on
 * `ordering.orders` at all (ADR 0039, orders.md §11.5 — "blocks... the
 * operator leaderboard"), so "my own queue" cannot be filtered, and even
 * `staff-and-access.md`'s own placement of these statistics on Home 0.2
 * assumes a staff person record (§11.1) that does not exist either — every
 * name would render as a raw Keycloak subject UUID. UI personalization
 * (interface language, a column picker) is listed under the same "Личные
 * данные... Not built — §11.1" line. Nothing here can be built as a partial
 * screen without fabricating data this backend cannot produce.
 */
@Component({
  selector: 'q-today-page',
  imports: [TPipe, RouterLink, BarChart],
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

  /**
   * Where the trading day was cut, in words.
   *
   * Null until the first snapshot lands, and null for `ALL_TIME` — a lifetime
   * figure has no window to state, and inventing one ("since the beginning")
   * would be a sentence nobody asked a question of. The instant is rendered in
   * the tenant's zone like every other time on this console.
   */
  protected periodLabel(): string | null {
    const snapshot = this.snapshot();
    if (!snapshot || snapshot.period !== 'BUSINESS_DAY' || !snapshot.periodFrom) {
      return null;
    }
    return this.i18n.t('today.period.businessDay', {
      from: formatDateTime(new Date(snapshot.periodFrom), PLACEHOLDER_TIME_ZONE),
    });
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

  /** Channel codes are tenant data — the bar's own label is the raw code, same as the retired mix-row. */
  protected sourceMixItems(): readonly ChartCategory[] {
    return this.sourceMix().map((slice) => mixItem(slice, slice.key));
  }

  protected typeMixItems(): readonly ChartCategory[] {
    return this.typeMix().map((slice) => mixItem(slice, this.typeMixLabel(slice.key)));
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

function mixItem(slice: MixSlice, label: string): ChartCategory {
  return { key: slice.key, label, value: slice.count };
}
