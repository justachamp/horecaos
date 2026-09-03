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
import { TPipe } from '../../core/i18n/t.pipe';
import { KitchenApi, TicketResponse } from './kitchen-api';
import { computeTicketSeverity } from './kitchen-ticket';

/** Same cadence as the KDS queue, until ADR 0045 exists. */
const POLL_INTERVAL_MS = 10_000;

/**
 * IA 2.4 — Display board (VDU): a read-only wall display of the combined
 * queue, for customers or staff to glance at from across the room.
 *
 * **Same data as the KDS** (`stream=live` — FIRED/IN_PRODUCTION/READY), the
 * one thing IA 2.4 says by name to keep: "provider-assigned external
 * identifiers shown to humans" — `sequenceLabel` is exactly that. Read-only:
 * no start/ready/recall here.
 *
 * **Reduced relative to the spec, deliberately, and named where it matters.**
 * IA Part 4's own component-gap list puts a TV-distance wallboard shell
 * beside the operator console and the device/KDS shell — none of the three
 * exist as separate templates yet (see `kitchen-shell.ts`'s own doc for the
 * KDS's identical reduction). This renders inside the same operator console
 * shell, with its own oversized type rather than an off-scale invention, the
 * same trade-off `today-page.ts` documents for the live board's counters.
 */
@Component({
  selector: 'q-vdu-page',
  imports: [TPipe],
  templateUrl: './vdu-page.html',
  styleUrl: './vdu-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class VduPage implements OnInit {
  private readonly kitchen = inject(KitchenApi);
  private readonly location = inject(CurrentLocation);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly tickets = signal<readonly TicketResponse[]>([]);
  protected readonly firstLoadComplete = signal(false);
  protected readonly denied = signal(false);

  private pollHandle: ReturnType<typeof setInterval> | null = null;

  ngOnInit(): void {
    this.pollHandle = setInterval(() => void this.refresh(), POLL_INTERVAL_MS);
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
      const board = await this.kitchen.board(scope, 'live');
      this.tickets.set(
        [...board.tickets].sort((a, b) => statusRank(a.status) - statusRank(b.status)),
      );
      this.denied.set(false);
    } catch (error) {
      if (error instanceof ApiError && error.status === 403) {
        this.denied.set(true);
      }
      // A wall display swallows other errors rather than showing a stack —
      // it just keeps last-known rows on screen until the next poll succeeds.
    } finally {
      this.firstLoadComplete.set(true);
    }
  }

  protected severityTone(ticket: TicketResponse): 'danger' | 'warning' | 'none' {
    return computeTicketSeverity(
      {
        targetReadyAt: ticket.targetReadyAt ? new Date(ticket.targetReadyAt) : null,
        createdAt: new Date(ticket.createdAt),
      },
      new Date(),
    ).tone;
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
