import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  inject,
  signal,
} from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiError } from '../../core/api/problem-details';
import { CurrentLocation } from '../../core/auth/current-location';
import { TimeZone, formatClock } from '../../core/format/datetime';
import { I18n } from '../../core/i18n/i18n';
import { TPipe } from '../../core/i18n/t.pipe';
import { describeApiError } from '../orders/order-errors';
import { KitchenApi, TicketResponse } from './kitchen-api';

/** Same cadence as the KDS queue (kitchen-queue-page.ts), until ADR 0045 exists. */
const POLL_INTERVAL_MS = 10_000;

/** See `order-queue.ts`'s identical constant — no location carries a timezone on any response this board reaches yet. */
const PLACEHOLDER_TIME_ZONE: TimeZone = 'Asia/Tashkent';

/**
 * IA 2.2 — Buffer: tickets accepted but deliberately not yet on the line.
 *
 * `KitchenBoardController`'s `stream=buffer` (HELD tickets) and its
 * `/release` endpoint are both real — ADR 0041's own buffer, built alongside
 * the KDS but not yet reachable from any screen. This is that screen.
 *
 * **Owns, built**: the buffer list; the kitchen fire time (`releaseAt`) as a
 * field distinct from `createdAt` and `targetReadyAt`; manual release.
 * **Not built**: changing *when* a ticket fires (`release-schedule`,
 * `kitchen.ticket.release.override`) — this screen only fires a ticket now,
 * the same reduction `dispatch-board-page.ts` documents for drag-and-drop:
 * the one action operators need three hundred times a shift, not the rarer
 * one. **Paid-only hold** (orders.md nowhere specifies which payment fact
 * gates it, and ADR 0013's payment-method registry is itself not built) is
 * therefore not modelled — every held ticket in the buffer is shown, and
 * release is offered unconditionally.
 */
@Component({
  selector: 'q-buffer-page',
  imports: [TPipe],
  templateUrl: './buffer-page.html',
  styleUrl: './buffer-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class BufferPage implements OnInit {
  private readonly kitchen = inject(KitchenApi);
  private readonly location = inject(CurrentLocation);
  private readonly i18n = inject(I18n);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly tickets = signal<readonly TicketResponse[]>([]);
  protected readonly firstLoadComplete = signal(false);
  protected readonly denied = signal(false);
  protected readonly lastError = signal<ApiError | null>(null);
  protected readonly busyTicketIds = signal<ReadonlySet<string>>(new Set());
  protected readonly actionNotice = signal<string | null>(null);

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

  private async refresh(): Promise<void> {
    const scope = this.location.scope();
    if (!scope) {
      this.denied.set(this.location.denied());
      this.firstLoadComplete.set(true);
      return;
    }
    try {
      const board = await this.kitchen.board(scope, 'buffer');
      // Oldest fire time first: the ticket a manager should look at next is
      // the one due soonest, not the one most recently accepted.
      this.tickets.set([...board.tickets].sort((a, b) => releaseInstant(a) - releaseInstant(b)));
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

  protected releaseAtLabel(ticket: TicketResponse): string | null {
    return ticket.releaseAt ? formatClock(new Date(ticket.releaseAt), PLACEHOLDER_TIME_ZONE) : null;
  }

  protected targetReadyLabel(ticket: TicketResponse): string | null {
    return ticket.targetReadyAt
      ? formatClock(new Date(ticket.targetReadyAt), PLACEHOLDER_TIME_ZONE)
      : null;
  }

  protected releaseModeLabel(mode: string): string {
    switch (mode) {
      case 'MANUAL_HOLD':
        return this.i18n.t('kitchen.buffer.releaseMode.MANUAL_HOLD');
      case 'SCHEDULED':
        return this.i18n.t('kitchen.buffer.releaseMode.SCHEDULED');
      case 'AUTO_ON_CONFIRM':
        return this.i18n.t('kitchen.buffer.releaseMode.AUTO_ON_CONFIRM');
      default:
        return mode;
    }
  }

  protected isBusy(ticket: TicketResponse): boolean {
    return this.busyTicketIds().has(ticket.ticketId);
  }

  protected async releaseNow(ticket: TicketResponse): Promise<void> {
    const scope = this.location.scope();
    if (!scope || this.isBusy(ticket)) {
      return;
    }
    this.setBusy(ticket.ticketId, true);
    try {
      const updated = await firstValueFrom(
        this.kitchen.release(scope, ticket.ticketId, ticket.version, 'OPERATIONS_BUFFER_RELEASE'),
      );
      // Fired now — it belongs on the KDS queue, not here.
      this.tickets.update((current) => current.filter((row) => row.ticketId !== updated.ticketId));
    } catch (error) {
      this.actionNotice.set(
        error instanceof ApiError
          ? describeApiError(error, (key, values) => this.i18n.t(key, values))
          : this.i18n.t('error.unknown.noReference'),
      );
    } finally {
      this.setBusy(ticket.ticketId, false);
    }
  }

  protected dismissNotice(): void {
    this.actionNotice.set(null);
  }

  private setBusy(ticketId: string, busy: boolean): void {
    this.busyTicketIds.update((current) => {
      const next = new Set(current);
      if (busy) {
        next.add(ticketId);
      } else {
        next.delete(ticketId);
      }
      return next;
    });
  }
}

function releaseInstant(ticket: TicketResponse): number {
  return ticket.releaseAt ? new Date(ticket.releaseAt).getTime() : Number.MAX_SAFE_INTEGER;
}
