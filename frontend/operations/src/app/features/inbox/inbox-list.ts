import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { firstValueFrom } from 'rxjs';

import { ApiError, ApiErrorCode } from '../../core/api/problem-details';
import { CurrentLocation } from '../../core/auth/current-location';
import { TimeZone, formatClock, formatDateTime } from '../../core/format/datetime';
import { I18n } from '../../core/i18n/i18n';
import { MessageKey } from '../../core/i18n/messages.en';
import { TPipe } from '../../core/i18n/t.pipe';
import { DeniedState } from '../../shared/ui/denied-state';
import { EmptyState } from '../../shared/ui/empty-state';
import { StatusPill } from '../../shared/ui/status-pill';
import { describeApiError, errorReference } from '../orders/order-errors';
import { InboxApi } from './inbox-api';
import { ConversationSummaryResponse } from './inbox-conversation';
import { channelLabel, stateLabel } from './inbox-labels';

/** ADR 0059's alternatives table: "Inbox v1 uses the order board's polling pattern" — the same 10s cadence. */
const POLL_INTERVAL_MS = 10_000;

/** The endpoint's own maximum (`Page.MAXIMUM_LIMIT` is 200; the controller caps at 500, this client asks for less). */
const FETCH_LIMIT = 200;

/** See `order-queue.ts`'s identical constant for why this is a fixed zone, not the browser's. */
const PLACEHOLDER_TIME_ZONE: TimeZone = 'Asia/Tashkent';

/** The capability `ConversationInboxController`'s endpoints declare — see `Capability.CONVERSATION_INBOX_MANAGE`. */
const LIST_CAPABILITY = 'CONVERSATION_INBOX_MANAGE';

/**
 * The inbox's list — a sibling of `OrderQueue`, built the same way: a live
 * poll, a dense table, and a docked detail beside it rather than a modal
 * (see `inbox-page.ts`). Needs-attention ordering is entirely server-side
 * (`ConversationRepository.listForBrand`'s own doc explains the ranking), so
 * unlike the order board this has no client-side tabs to maintain — the
 * `needsReply` flag on each row is enough to render the same kind of
 * attention cue orders.md's severity rail gives a late order.
 */
@Component({
  selector: 'q-inbox-list',
  imports: [TPipe, StatusPill, DeniedState, EmptyState],
  templateUrl: './inbox-list.html',
  styleUrl: './inbox-list.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class InboxList implements OnInit {
  private readonly api = inject(InboxApi);
  private readonly location = inject(CurrentLocation);
  private readonly i18n = inject(I18n);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly rows = signal<readonly ConversationSummaryResponse[]>([]);
  protected readonly lastUpdatedAt = signal<Date | null>(null);
  protected readonly firstLoadComplete = signal(false);
  protected readonly refreshing = signal(false);
  protected readonly lastError = signal<ApiError | null>(null);
  protected readonly denied = signal(false);
  /**
   * `null` when `denied` is true only because the operator has no location in
   * scope (`CurrentLocation.denied()`) — no request was ever made, so no
   * capability was ever checked. Set to {@link LIST_CAPABILITY} only once a
   * real `CONVERSATION_INBOX_MANAGE` 403 comes back from {@link refresh}'s
   * own request. See `customers-page.ts`'s identical signal for the bug this
   * closes: a hardcoded capability name is wrong, and actively misleading,
   * for the scope-denial case.
   */
  protected readonly deniedCapability = signal<string | null>(null);
  /** Distinct copy for "no location assigned" versus a genuine capability denial. */
  protected readonly deniedAskKey = computed<MessageKey>(() =>
    this.deniedCapability() === null ? 'ui.denied.ask.noLocation' : 'ui.denied.ask',
  );

  private pollHandle: ReturnType<typeof setInterval> | null = null;

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

  private readonly onVisibilityChange = (): void => {
    if (document.visibilityState === 'visible') {
      void this.refresh();
    }
  };

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
      // No request was made, so no capability was ever checked — see
      // `deniedCapability`'s own doc for why this must stay `null` here.
      this.deniedCapability.set(null);
      this.firstLoadComplete.set(true);
      return;
    }

    this.refreshing.set(true);
    try {
      const result = await firstValueFrom(this.api.list(scope, FETCH_LIMIT));
      this.rows.set(result.value ?? []);
      this.lastUpdatedAt.set(new Date());
      this.lastError.set(null);
      this.denied.set(false);
      this.deniedCapability.set(null);
    } catch (error) {
      if (error instanceof ApiError) {
        if (error.status === 403) {
          this.denied.set(true);
          this.deniedCapability.set(
            error.code === ApiErrorCode.INSUFFICIENT_CAPABILITY ? LIST_CAPABILITY : null,
          );
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

  protected openConversation(conversationId: string): void {
    void this.router.navigate([conversationId], { relativeTo: this.route });
  }

  protected channelLabel(channel: string): string {
    return channelLabel(channel, (key) => this.i18n.t(key));
  }

  protected stateLabel(state: string): string {
    return stateLabel(state, (key) => this.i18n.t(key));
  }

  protected formatActivity(row: ConversationSummaryResponse): string {
    return formatDateTime(new Date(row.lastActivityAt), PLACEHOLDER_TIME_ZONE);
  }

  protected formatUpdatedAt(): string | null {
    const updated = this.lastUpdatedAt();
    return updated
      ? this.i18n.t('orders.queue.updated', { time: formatClock(updated, PLACEHOLDER_TIME_ZONE) })
      : null;
  }

  protected emptyMessage(): string {
    return this.i18n.t('inbox.list.empty');
  }

  protected errorMessage(error: ApiError): string {
    return describeApiError(error, (key, values) => this.i18n.t(key, values));
  }

  protected errorReference(error: ApiError): string {
    return errorReference(error);
  }
}
