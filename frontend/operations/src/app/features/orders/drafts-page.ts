import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { RouterLink } from '@angular/router';

import { ApiError } from '../../core/api/problem-details';
import { CurrentLocation } from '../../core/auth/current-location';
import { TimeZone, formatClock, formatDateTime } from '../../core/format/datetime';
import { I18n } from '../../core/i18n/i18n';
import { TPipe } from '../../core/i18n/t.pipe';
import { ChannelView, SalesChannelsApi } from '../settings/sales-channels/sales-channels-api';
import { describeApiError } from './order-errors';
import { DraftCartResponse, DraftsApi, DraftsQuery } from './drafts-api';

/** See `order-queue.ts`'s identical constant — no location carries a timezone on any response this board reaches yet. */
const PLACEHOLDER_TIME_ZONE: TimeZone = 'Asia/Tashkent';

type OwnerTypeFilter = 'ALL' | 'ACCOUNT' | 'GUEST';

interface AbandonmentSlice {
  readonly channelId: string;
  readonly channelName: string;
  readonly abandonedCount: number;
  readonly totalCount: number;
  readonly ratePercent: number;
}

/**
 * IA 1.4 — Drafts and abandoned carts.
 *
 * **Built.** `GET .../orders/drafts` over `JdbcCartStore.listDrafts`:
 * `ACTIVE`/`EXPIRED`/`ABANDONED` carts with no converted order, plus the
 * abandonment-by-channel breakdown computed client-side over the same rows —
 * orders.md §6's own framing, "which is the only reason the screen exists".
 * orders.md §6's filters: period and channel reach the server as `from`/`to`/
 * `channelId` (`DraftsApi.list`'s query, unchanged since `OperationsOrder
 * Controller.drafts` accepts no other parameter); owner type narrows the
 * fetched page client-side, the same rows the breakdown already reads.
 * "Location" has no per-page control — this screen is already scoped to one
 * location (the path itself carries `locationId`), so every row shares it;
 * the column still renders it, resolved through `CurrentLocation.options`
 * where available. Sorting is explicit (`visibleDrafts`, newest first) rather
 * than trusting `ORDER BY created_at DESC` server-side to keep holding.
 * Opening the customer links to IA 5.2 for an account cart; a guest cart's
 * `guestReferenceHash` is a keyed hash and is never shown as though it named
 * a person.
 *
 * **The defect this replaced.** The breakdown used to count every draft,
 * `ACTIVE` included, as an "abandonment" — a live in-progress basket
 * inflated the number under a heading that reads «Отказы по каналам» — and
 * it emitted a raw count with no denominator. {@link abandonmentByChannel}'s
 * own doc has the fix.
 *
 * **Not built, honestly.** No first-line product-name preview:
 * `ordering.cart_lines` carries no name snapshot the way an order's lines
 * do, and resolving one would be a cross-module join this module does not
 * own (see `JdbcCartStore.listDrafts`'s own doc). No hand-off to a
 * recovery campaign audience (ADR 0044) — that module is out of this wave's
 * section. No action converts a cart into an order: orders.md §6 is
 * explicit that nobody agreed to that basket, and none is offered here.
 */
@Component({
  selector: 'q-drafts-page',
  imports: [TPipe, RouterLink],
  templateUrl: './drafts-page.html',
  styleUrl: './drafts-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DraftsPage implements OnInit {
  private readonly api = inject(DraftsApi);
  private readonly channelsApi = inject(SalesChannelsApi);
  private readonly location = inject(CurrentLocation);
  protected readonly i18n = inject(I18n);

  protected readonly loading = signal(true);
  protected readonly denied = signal(false);
  protected readonly loadError = signal<string | null>(null);
  protected readonly drafts = signal<readonly DraftCartResponse[]>([]);
  protected readonly channels = signal<readonly ChannelView[]>([]);
  protected readonly channelsById = signal<ReadonlyMap<string, ChannelView>>(new Map());

  /** orders.md §6's period filter, `YYYY-MM-DD`; reaches the server as `from`/`to` in {@link query}. */
  protected readonly fromDate = signal('');
  protected readonly toDate = signal('');
  /** orders.md §6's channel filter; `''` means every channel. Reaches the server as `channelId`. */
  protected readonly channelFilter = signal('');
  /**
   * orders.md §6's owner-type filter. `OperationsOrderController.drafts`
   * takes only `from`/`to`/`channelId` — there is no server-side owner
   * parameter — so this narrows the already-fetched page client-side rather
   * than a second round trip for a filter the endpoint cannot express.
   */
  protected readonly ownerTypeFilter = signal<OwnerTypeFilter>('ALL');

  /**
   * The rows the table and the breakdown above it actually show: the
   * server's page, narrowed by {@link ownerTypeFilter} and sorted
   * explicitly, newest first (orders.md §6: "Sort by `created_at`
   * descending; this is a log, not a queue"). `JdbcCartStore.listDrafts`
   * already orders this way in SQL, but a page that only ever trusts the
   * server's order and never asserts its own breaks silently the day that
   * changes — an index swap, a `UNION` added upstream — and asserting it
   * here costs nothing.
   */
  protected readonly visibleDrafts = computed<readonly DraftCartResponse[]>(() => {
    const owner = this.ownerTypeFilter();
    const filtered = this.drafts().filter((draft) => {
      if (owner === 'ACCOUNT') {
        return draft.customerAccountId != null;
      }
      if (owner === 'GUEST') {
        return draft.customerAccountId == null;
      }
      return true;
    });
    return [...filtered].sort(
      (a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime(),
    );
  });

  async ngOnInit(): Promise<void> {
    await this.location.ensureLoaded();
    await this.load();
  }

  private async load(): Promise<void> {
    this.loading.set(true);
    const scope = this.location.scope();
    if (!scope) {
      this.denied.set(this.location.denied());
      this.loading.set(false);
      return;
    }
    try {
      const [drafts, channels] = await Promise.all([
        this.api.list(scope, this.query()),
        this.channelsApi.list(scope).catch(() => []),
      ]);
      this.drafts.set(drafts);
      this.channels.set(channels);
      this.channelsById.set(new Map(channels.map((channel) => [channel.id, channel])));
      this.denied.set(false);
    } catch (error) {
      if (error instanceof ApiError && error.status === 403) {
        this.denied.set(true);
      } else {
        this.loadError.set(
          error instanceof ApiError
            ? describeApiError(error, (key, values) => this.i18n.t(key, values))
            : this.i18n.t('error.unknown.noReference'),
        );
      }
    } finally {
      this.loading.set(false);
    }
  }

  /** `from`/`to`/`channelId` for {@link DraftsApi.list} — the only three filters the endpoint accepts. */
  private query(): DraftsQuery {
    const channelId = this.channelFilter();
    return {
      from: this.fromDate() ? new Date(this.fromDate()).toISOString() : undefined,
      // Inclusive of the whole end day, not just its midnight.
      to: this.toDate() ? endOfDayIso(this.toDate()) : undefined,
      channelId: channelId ? channelId : undefined,
    };
  }

  protected setFromDate(value: string): void {
    this.fromDate.set(value);
  }

  protected setToDate(value: string): void {
    this.toDate.set(value);
  }

  /** Applies `fromDate`/`toDate` together — one request for the pair, not one per keystroke. */
  protected async applyPeriod(): Promise<void> {
    await this.load();
  }

  protected async onChannelFilterChange(value: string): Promise<void> {
    this.channelFilter.set(value);
    await this.load();
  }

  /** Client-side only — see {@link ownerTypeFilter}'s own doc. No request needed. */
  protected onOwnerTypeFilterChange(value: string): void {
    this.ownerTypeFilter.set(value as OwnerTypeFilter);
  }

  protected channelName(channelId: string): string {
    return this.channelsById().get(channelId)?.displayName ?? channelId;
  }

  /**
   * The abandonment-by-channel breakdown — the only reason the screen
   * exists (orders.md §6: "the number a marketer wants is 'the Telegram bot
   * loses 40% of baskets', not a list").
   *
   * **The defect this replaced.** Every draft, `ACTIVE` included, was
   * counted as an "abandonment", so a live in-progress basket inflated the
   * number under a heading that reads «Отказы по каналам», and the count
   * carried no denominator — a bare `2` answers nothing next to a stated
   * rate. `ACTIVE` is now excluded from the numerator: `EXPIRED` and
   * `ABANDONED` are the two ways a cart actually goes unfinished
   * (`JdbcCartStore.expireStaleCarts` is what turns the first into the
   * second on timeout). The denominator is every draft in the channel,
   * `ACTIVE` included, so the rate reads as "of what started here, how much
   * never finished" — the Telegram-bot number the spec names.
   */
  protected abandonmentByChannel(): readonly AbandonmentSlice[] {
    const totals = new Map<string, number>();
    const abandoned = new Map<string, number>();
    for (const draft of this.visibleDrafts()) {
      totals.set(draft.channelId, (totals.get(draft.channelId) ?? 0) + 1);
      if (draft.status !== 'ACTIVE') {
        abandoned.set(draft.channelId, (abandoned.get(draft.channelId) ?? 0) + 1);
      }
    }
    return [...totals.entries()]
      .map(([channelId, totalCount]) => {
        const abandonedCount = abandoned.get(channelId) ?? 0;
        return {
          channelId,
          channelName: this.channelName(channelId),
          abandonedCount,
          totalCount,
          ratePercent: totalCount > 0 ? Math.round((abandonedCount / totalCount) * 100) : 0,
        };
      })
      .sort((a, b) => b.ratePercent - a.ratePercent || b.totalCount - a.totalCount);
  }

  protected ownerLabel(draft: DraftCartResponse): string {
    return draft.customerAccountId
      ? this.i18n.t('orders.drafts.owner.account')
      : this.i18n.t('orders.drafts.owner.guest');
  }

  protected statusLabel(status: string): string {
    switch (status) {
      case 'ACTIVE':
        return this.i18n.t('orders.drafts.status.ACTIVE');
      case 'EXPIRED':
        return this.i18n.t('orders.drafts.status.EXPIRED');
      case 'ABANDONED':
        return this.i18n.t('orders.drafts.status.ABANDONED');
      default:
        return status;
    }
  }

  protected timeLabel(iso: string): string {
    return formatClock(new Date(iso), PLACEHOLDER_TIME_ZONE);
  }

  /** `expires_at` (orders.md §6's spec'd column) — full date-time, since a cart can outlive the day it started. */
  protected expiresLabel(iso: string): string {
    return formatDateTime(new Date(iso), PLACEHOLDER_TIME_ZONE);
  }

  /**
   * `DraftCartResponse.locationId` (orders.md §6's "location" column). This
   * screen is already scoped to one location —
   * `OperationsOrderController.drafts` takes `locationId` from the path, not
   * a query filter — so every row's `locationId` is the location currently
   * in view; there is no separate location *filter* to add here, only the
   * column. `CurrentLocation.options` resolves a display name when the
   * operator reached this location through the brand-resolution path (see
   * that class's own doc for why); for a direct single-location grant it
   * stays empty, and the truncated id — the same treatment `cartId` already
   * gets in this table — is the honest fallback rather than a guessed name
   * from a locations lookup this wave does not own.
   */
  protected locationLabel(locationId: string): string {
    const match = this.location.options().find((option) => option.id === locationId);
    return match?.displayName ?? locationId.slice(0, 8);
  }
}

/** The end of a `yyyy-MM-dd` date, in the browser's own zone, as an ISO instant. Mirrors `shifts-page.ts`'s helper. */
function endOfDayIso(dateOnly: string): string {
  const date = new Date(`${dateOnly}T23:59:59.999`);
  return date.toISOString();
}
