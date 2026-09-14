import { Injectable, Signal, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../core/api/api-client';
import { LocationScope, operationsPaths } from '../core/api/operations-paths';
import { CurrentLocation } from '../core/auth/current-location';
import { LatenessPolicy, PLATFORM_DEFAULT_LATENESS_POLICY } from '../core/lateness-policy';
import { LatenessPolicyApi } from '../core/lateness-policy-api';
import { RealtimeClient } from '../core/realtime/realtime-client';
import { OrderCountsResponse } from '../features/orders/order-detail';
import { OrderSeverityInput, computeOrderSeverity } from '../features/orders/order-severity';
import { OrderSummaryResponse } from '../features/orders/order-summary';

/**
 * Every status a fresh non-terminal count includes — `JdbcOrderStore.counts`'s
 * own grouping for `totalNonTerminal`, named here because {@link
 * fetchLateCount} has to ask the plain `GET .../orders` list for the same
 * population the counts endpoint already aggregates.
 */
const NON_TERMINAL_STATUSES = [
  'RECEIVED',
  'PAYMENT_AUTHORIZING',
  'AWAITING_APPROVAL',
  'CONFIRMED',
  'PREPARING',
  'READY',
  'FULFILLING',
] as const;

/**
 * The same cap `order-queue.ts`'s own board fetch uses (`FETCH_LIMIT`). A
 * branch busier than this undercounts `late` the same documented way that
 * screen's `attention` tab already can — see its own doc for why a bigger
 * number is a real `GET .../orders/counts`-shaped gap, not a constant to
 * raise here.
 */
const LATE_FETCH_LIMIT = 200;

/**
 * The counters the shell shows on every screen (ADR 0045, wave P08, row
 * `0.1f`).
 *
 * These live in the shell, not in the orders feature, because of a design
 * decision from the prototype: **an operator must never have to navigate to
 * discover that something has gone late.** The late count is visible from
 * Statistics, from Settings, from a courier's record — from anywhere. That is
 * only possible if the count has an owner above the routed view.
 *
 * **Its own fetch, at last.** Before this wave both signals were written only
 * by `order-queue.ts`'s own `refresh()` — see that method's `this.serviceStatus.set(…)`
 * call — so the rail's open/late badges read zero until an operator opened
 * Orders at least once, and froze at whatever `order-queue.ts` last saw the
 * moment they navigated to any other screen, which is exactly the failure
 * this class's own doc already said it exists to prevent. {@link start},
 * called once from the shell's constructor, makes this class responsible for
 * its own numbers instead of a hostage to whichever screen happens to be
 * open.
 *
 * **`open` and `late` come from two different reads, honestly.**
 * `GET .../orders/counts` (`OrderCountsResponse.totalNonTerminal`) is the
 * exact, server-aggregated open count — no page, no cap, no client
 * arithmetic. `late` has no such aggregate: `JdbcOrderStore.counts`'s own Java
 * doc is explicit that lateness "needs no column, no job and no event"
 * because it is derived from the promise and the clock at render time, so
 * this fetches a capped page of the location's open orders and runs the same
 * `computeOrderSeverity` evaluator `order-queue.ts` and the KDS ticket board
 * both already share (`X.39`), rather than inventing a second severity rule.
 *
 * `order-queue.ts` keeps calling {@link set} directly with the page it
 * already fetched, so a screen that is open does not pay for this class's own
 * redundant fetch on top of its own poll — {@link set} and {@link refresh}
 * are two paths to the same two signals, never both racing on the same
 * screen.
 */
@Injectable({ providedIn: 'root' })
export class ServiceStatus {
  private readonly api = inject(ApiClient);
  private readonly location = inject(CurrentLocation);
  private readonly latenessPolicyApi = inject(LatenessPolicyApi);
  private readonly realtime = inject(RealtimeClient);

  private readonly open = signal(0);
  private readonly late = signal(0);
  private readonly updated = signal<Date | null>(null);

  /** Orders that are neither completed nor cancelled. */
  readonly openCount: Signal<number> = this.open.asReadonly();

  /** Open orders past their promise. Lateness is an overlay on a status, never a status. */
  readonly lateCount: Signal<number> = this.late.asReadonly();

  /** When these numbers were last known to be true. Null means never fetched. */
  readonly updatedAt: Signal<Date | null> = this.updated.asReadonly();

  private started = false;
  private latenessPolicy: LatenessPolicy = PLATFORM_DEFAULT_LATENESS_POLICY;
  private latenessPolicyLoaded = false;

  constructor() {
    // The accelerator's other half: `COUNTERS` carries `open` inline
    // (`OrderCountsResponse.totalNonTerminal`/`BrandOrderCountsResponse
    // .totals.totalNonTerminal`), so a snapshot updates the rail the instant
    // it arrives rather than waiting for the next `refresh()`. `late` has no
    // such snapshot (no server aggregate exists for it — see {@link
    // fetchLateCount}), so it still only moves on `refresh()`/{@link set}.
    this.realtime.onFrame((frame) => {
      if (frame.kind !== 'snapshot' || frame.channel !== 'counters') {
        return;
      }
      const totalNonTerminal = readTotalNonTerminal(frame.snapshot);
      if (totalNonTerminal === null) {
        return;
      }
      this.open.set(totalNonTerminal);
      this.updated.set(new Date());
    });
  }

  /**
   * Safe to call from every consumer — the same idempotent shape
   * `VoicePresence.start()`/`CurrentLocation.ensureLoaded()` already are.
   * Only the first call does anything; later calls are a no-op, because the
   * shell mounts once and this is not a per-screen poll.
   */
  start(): void {
    if (this.started) {
      return;
    }
    this.started = true;
    void this.refresh();
  }

  /**
   * Fetches {@link openCount} and {@link lateCount} directly, independent of
   * whatever screen is currently routed. Never throws: a failed independent
   * refresh leaves the previous, still-true numbers on screen rather than
   * flashing to zero — the exact freeze this class exists to fix is at worst
   * a stale "correct as of a moment ago", never a false "empty".
   */
  async refresh(): Promise<void> {
    await this.location.ensureLoaded();
    const scope = this.location.scope();
    if (!scope) {
      return;
    }
    const now = new Date();
    try {
      const [openCount, lateCount] = await Promise.all([
        this.fetchOpenCount(scope),
        this.fetchLateCount(scope, now),
      ]);
      this.open.set(openCount);
      this.late.set(lateCount);
      this.updated.set(now);
    } catch {
      // Network failure or a capability this session does not hold. The
      // rail simply keeps showing the last true numbers; a poll and — once
      // wired to a screen — a COUNTERS signal both retry on their own.
    }
  }

  /**
   * Written directly by a caller that already paid for the fetch —
   * `order-queue.ts`'s own `refresh()`, so an open Orders screen does not
   * also trigger this class's redundant read on top of its own poll.
   */
  set(counts: { readonly open: number; readonly late: number }, at: Date = new Date()): void {
    this.open.set(counts.open);
    this.late.set(counts.late);
    this.updated.set(at);
  }

  private async fetchOpenCount(scope: LocationScope): Promise<number> {
    const result = await firstValueFrom(
      this.api.get<OrderCountsResponse>(operationsPaths.orderCounts(scope)),
    );
    return result.value?.totalNonTerminal ?? 0;
  }

  private async fetchLateCount(scope: LocationScope, now: Date): Promise<number> {
    if (!this.latenessPolicyLoaded) {
      this.latenessPolicy = await this.latenessPolicyApi.resolve(scope);
      this.latenessPolicyLoaded = true;
    }
    const result = await firstValueFrom(
      this.api.get<readonly OrderSummaryResponse[]>(operationsPaths.orders(scope), {
        params: { status: NON_TERMINAL_STATUSES, limit: LATE_FETCH_LIMIT },
      }),
    );
    const orders = result.value ?? [];
    let late = 0;
    for (const order of orders) {
      if (
        computeOrderSeverity(toSeverityInput(order), now, this.latenessPolicy).level !== 'NORMAL'
      ) {
        late += 1;
      }
    }
    return late;
  }
}

function toSeverityInput(order: OrderSummaryResponse): OrderSeverityInput {
  return {
    status: order.status,
    createdAt: new Date(order.createdAt),
    approvalDeadlineAt: order.approvalDeadlineAt ? new Date(order.approvalDeadlineAt) : null,
    fulfillmentMode: order.fulfillmentMode,
    promisedAt: order.promisedAt ? new Date(order.promisedAt) : null,
    hasBlockedProcess: order.processAttention === 'MANUAL_ACTION_REQUIRED',
  };
}

/**
 * `OrderCountsResponse.totalNonTerminal` out of a `COUNTERS` snapshot's raw
 * JSON payload. `RealtimeClient` never subscribes at `BRAND` scope (its
 * `streamUrl` omits `scope`, which `OperationsStreamController.parseScope`
 * resolves to the connection's own `LOCATION`), so the location shape —
 * never `BrandOrderCountsResponse`'s nested `totals.totalNonTerminal` — is
 * the only one this ever has to read.
 */
function readTotalNonTerminal(snapshot: unknown): number | null {
  if (typeof snapshot !== 'object' || snapshot === null) {
    return null;
  }
  const value = (snapshot as Record<string, unknown>)['totalNonTerminal'];
  return typeof value === 'number' ? value : null;
}
