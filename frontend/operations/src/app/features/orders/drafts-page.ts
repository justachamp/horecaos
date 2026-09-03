import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';

import { ApiError } from '../../core/api/problem-details';
import { CurrentLocation } from '../../core/auth/current-location';
import { TimeZone, formatClock } from '../../core/format/datetime';
import { I18n } from '../../core/i18n/i18n';
import { TPipe } from '../../core/i18n/t.pipe';
import { ChannelView, SalesChannelsApi } from '../settings/sales-channels/sales-channels-api';
import { describeApiError } from './order-errors';
import { DraftCartResponse, DraftsApi } from './drafts-api';

/** See `order-queue.ts`'s identical constant — no location carries a timezone on any response this board reaches yet. */
const PLACEHOLDER_TIME_ZONE: TimeZone = 'Asia/Tashkent';

interface AbandonmentSlice {
  readonly channelId: string;
  readonly channelName: string;
  readonly count: number;
}

/**
 * IA 1.4 — Drafts and abandoned carts.
 *
 * **Built.** This wave's new `GET .../orders/drafts` over
 * `JdbcCartStore.listDrafts`: `ACTIVE`/`EXPIRED`/`ABANDONED` carts with no
 * converted order, newest first, plus the abandonment-by-channel breakdown
 * computed client-side over the same rows — orders.md §6's own framing,
 * "which is the only reason the screen exists". Opening the customer links
 * to IA 5.2 for an account cart; a guest cart's `guestReferenceHash` is a
 * keyed hash and is never shown as though it named a person.
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
  protected readonly channelsById = signal<ReadonlyMap<string, ChannelView>>(new Map());

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
        this.api.list(scope),
        this.channelsApi.list(scope).catch(() => []),
      ]);
      this.drafts.set(drafts);
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

  protected channelName(channelId: string): string {
    return this.channelsById().get(channelId)?.displayName ?? channelId;
  }

  protected abandonmentByChannel(): readonly AbandonmentSlice[] {
    const counts = new Map<string, number>();
    for (const draft of this.drafts()) {
      counts.set(draft.channelId, (counts.get(draft.channelId) ?? 0) + 1);
    }
    return [...counts.entries()]
      .map(([channelId, count]) => ({ channelId, channelName: this.channelName(channelId), count }))
      .sort((a, b) => b.count - a.count);
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
}
