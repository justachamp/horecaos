import { Injectable, Signal, signal } from '@angular/core';

/**
 * The counters the shell shows on every screen.
 *
 * These live in the shell, not in the orders feature, because of a design
 * decision from the prototype: **an operator must never have to navigate to
 * discover that something has gone late.** The late count is visible from
 * Statistics, from Settings, from a courier's record — from anywhere. That is
 * only possible if the count has an owner above the routed view.
 *
 * **No data source yet, and no fake one.** Both counters start at zero and this
 * service has no fetch. Two things have to land before it can have one:
 *
 *  - the `GET .../orders/counts` companion endpoint that
 *    `operations-spec/orders.md` §2.3 specifies, which is not built; and
 *  - ADR 0045 live updates, which would push `COUNTERS` with its integers inline
 *    and coalesce frames at 250 ms.
 *
 * Until then the orders feature calls {@link set} from whatever it fetched, and
 * the polling fallback the spec describes — every 10 s while the tab is visible,
 * stopped when hidden, with a visible "updated HH:mm:ss" stamp — belongs here
 * when it is written. The stamp is not optional: a queue that silently stopped
 * updating looks exactly like a quiet shift.
 */
@Injectable({ providedIn: 'root' })
export class ServiceStatus {
  private readonly open = signal(0);
  private readonly late = signal(0);
  private readonly updated = signal<Date | null>(null);

  /** Orders that are neither completed nor cancelled. */
  readonly openCount: Signal<number> = this.open.asReadonly();

  /** Open orders past their promise. Lateness is an overlay on a status, never a status. */
  readonly lateCount: Signal<number> = this.late.asReadonly();

  /** When these numbers were last known to be true. Null means never fetched. */
  readonly updatedAt: Signal<Date | null> = this.updated.asReadonly();

  set(counts: { readonly open: number; readonly late: number }, at: Date = new Date()): void {
    this.open.set(counts.open);
    this.late.set(counts.late);
    this.updated.set(at);
  }
}
