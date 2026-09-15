import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  inject,
  signal,
} from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { LocationScope, operationsPaths } from '../../core/api/operations-paths';
import { ApiError } from '../../core/api/problem-details';
import { CurrentLocation } from '../../core/auth/current-location';
import { I18n } from '../../core/i18n/i18n';
import { TPipe } from '../../core/i18n/t.pipe';
import { describeApiError } from '../orders/order-errors';
import { OrderDetailResponse, OrderLine } from '../orders/order-detail';
import { ChallengeState } from '../orders/order-handover-api';
import { OrderHandoverPanel } from '../orders/order-handover-panel';
import { KitchenApi, StationResponse, TicketItemView, TicketResponse } from './kitchen-api';

/** One department's own ready count on one ticket — the roll-up gap map row 2.3 asks for. */
export interface DepartmentRollupRow {
  readonly stationId: string;
  readonly readyQuantity: number;
  readonly totalQuantity: number;
}

/** Same cadence as the KDS queue, until ADR 0045 exists. */
const POLL_INTERVAL_MS = 10_000;

/**
 * IA 2.3 — Expo / handover (Раздача): assembly and release.
 *
 * **Owns, built**: the READY queue (`stream=pass`); custody transfer via
 * `KITCHEN_TICKET_HANDOVER` and `/hand-over`; the per-department ready
 * roll-up (wave T02, gap map row 2.3) computed off each ticket's own items —
 * a station-by-station "2/3 ready" summary above the flat item table, rather
 * than the flat table alone; a packing check (all lines must be `READY`
 * before an operator can mark a ticket packed) and the marketplace handover
 * compare (`q-order-handover-panel`, wave P09's own component, embedded
 * here) as the two gates on the hand-over button — wired this wave, since
 * the pass had one unconditional button before it: no packing confirmation,
 * no code entry, no attempt counter, no supervisor bypass, even though the
 * server-side compare was already built and reachable.
 *
 * A ticket whose order carries no handover challenge at all (most direct
 * orders; an aggregator order without one configured) needs no code — the
 * panel renders a quiet caption for that order and the gate passes on
 * packing alone, the same as before this wave.
 */
@Component({
  selector: 'q-expo-page',
  imports: [TPipe, OrderHandoverPanel],
  templateUrl: './expo-page.html',
  styleUrl: './expo-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ExpoPage implements OnInit {
  private readonly api = inject(ApiClient);
  private readonly kitchen = inject(KitchenApi);
  private readonly location = inject(CurrentLocation);
  private readonly i18n = inject(I18n);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly tickets = signal<readonly TicketResponse[]>([]);
  protected readonly stationsById = signal<ReadonlyMap<string, StationResponse>>(new Map());
  protected readonly orderLinesByOrderId = signal<
    ReadonlyMap<string, ReadonlyMap<string, OrderLine>>
  >(new Map());
  protected readonly firstLoadComplete = signal(false);
  protected readonly denied = signal(false);
  protected readonly lastError = signal<ApiError | null>(null);
  protected readonly busyTicketIds = signal<ReadonlySet<string>>(new Set());
  protected readonly actionNotice = signal<string | null>(null);

  /** One order's handover challenge — `undefined` while `q-order-handover-panel` has not reported yet. */
  protected readonly challengesByOrderId = signal<ReadonlyMap<string, ChallengeState | null>>(
    new Map(),
  );
  /** Tickets an operator has confirmed as packed — cleared whenever a recall makes the ticket un-ready again. */
  protected readonly packedTicketIds = signal<ReadonlySet<string>>(new Set());

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
    const scope = this.location.scope();
    if (scope) {
      try {
        const stations = await this.kitchen.stations(scope);
        this.stationsById.set(new Map(stations.map((station) => [station.stationId, station])));
      } catch {
        // Department names are a nicety; the queue still works with raw ids.
      }
    }
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
      const board = await this.kitchen.board(scope, 'pass');
      this.tickets.set(board.tickets);
      for (const ticket of board.tickets) {
        void this.ensureOrderLoaded(ticket.orderId);
      }
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

  private async ensureOrderLoaded(orderId: string): Promise<void> {
    if (this.orderLinesByOrderId().has(orderId)) {
      return;
    }
    const scope = this.location.scope();
    if (!scope) {
      return;
    }
    try {
      const result = await firstValueFrom(
        this.api.get<OrderDetailResponse>(operationsPaths.order(scope, orderId)),
      );
      const lines = new Map<string, OrderLine>(
        result.value.lines.map((line) => [line.lineId, line]),
      );
      this.orderLinesByOrderId.update((current) => new Map(current).set(orderId, lines));
    } catch {
      // The item list still renders with generic line labels — see the template.
    }
  }

  /**
   * `q-order-handover-panel`'s required `[scope]`. The template only ever
   * calls this from inside the branch that already checked `denied()` and
   * `firstLoadComplete()`, so a scope is present by construction the same
   * way `refresh()` above already assumes.
   */
  protected expoScope(): LocationScope {
    return this.location.scope() as LocationScope;
  }

  protected lineFor(ticket: TicketResponse, item: TicketItemView): OrderLine | null {
    return this.orderLinesByOrderId().get(ticket.orderId)?.get(item.orderLineId) ?? null;
  }

  protected stationLabel(stationId: string): string {
    const station = this.stationsById().get(stationId);
    if (!station) {
      return stationId;
    }
    switch (this.i18n.locale()) {
      case 'uz-Latn':
        return station.displayNameUz;
      case 'en':
        return station.displayNameEn;
      default:
        return station.displayNameRu;
    }
  }

  protected itemStatusLabel(status: string): string {
    switch (status) {
      case 'QUEUED':
        return this.i18n.t('kitchen.item.status.QUEUED');
      case 'STARTED':
        return this.i18n.t('kitchen.item.status.STARTED');
      case 'READY':
        return this.i18n.t('kitchen.item.status.READY');
      case 'CANCELLED':
        return this.i18n.t('kitchen.item.status.CANCELLED');
      default:
        return status;
    }
  }

  protected isBusy(ticket: TicketResponse): boolean {
    return this.busyTicketIds().has(ticket.ticketId);
  }

  /**
   * Grouped by station, quantity-weighted rather than a line count: a single
   * routed line can carry a quantity greater than one, and "3/4 ready" is
   * what a department roll-up means, not "1 of 2 lines".
   */
  protected departmentRollup(ticket: TicketResponse): readonly DepartmentRollupRow[] {
    const byStation = new Map<string, { ready: number; total: number }>();
    for (const item of ticket.items) {
      if (item.status === 'CANCELLED') {
        continue;
      }
      const bucket = byStation.get(item.stationId) ?? { ready: 0, total: 0 };
      bucket.total += item.quantity;
      if (item.status === 'READY') {
        bucket.ready += item.quantity;
      }
      byStation.set(item.stationId, bucket);
    }
    return [...byStation.entries()]
      .map(([stationId, counts]) => ({
        stationId,
        readyQuantity: counts.ready,
        totalQuantity: counts.total,
      }))
      .sort((a, b) => this.stationLabel(a.stationId).localeCompare(this.stationLabel(b.stationId)));
  }

  /** The packing check needs every line actually on the pass — a cancelled line does not block it. */
  protected allItemsReady(ticket: TicketResponse): boolean {
    return ticket.items.every((item) => item.status === 'READY' || item.status === 'CANCELLED');
  }

  protected isPacked(ticket: TicketResponse): boolean {
    return this.packedTicketIds().has(ticket.ticketId);
  }

  protected togglePacked(ticket: TicketResponse): void {
    if (!this.allItemsReady(ticket)) {
      return;
    }
    this.packedTicketIds.update((current) => {
      const next = new Set(current);
      if (next.has(ticket.ticketId)) {
        next.delete(ticket.ticketId);
      } else {
        next.add(ticket.ticketId);
      }
      return next;
    });
  }

  protected onChallengeChange(ticket: TicketResponse, state: ChallengeState | null): void {
    this.challengesByOrderId.update((current) => new Map(current).set(ticket.orderId, state));
  }

  /**
   * Whether the marketplace handover compare is satisfied: no challenge was
   * ever issued for this order (most orders), or one was and it settled —
   * verified against the code, or a supervisor bypassed it. `undefined`
   * means `q-order-handover-panel` has not reported yet, which blocks the
   * button rather than assuming a pass — the same fail-closed choice ADR
   * 0040's own compare makes server-side.
   */
  protected canHandOverProof(ticket: TicketResponse): boolean {
    const state = this.challengesByOrderId().get(ticket.orderId);
    if (state === undefined) {
      return false;
    }
    if (state === null) {
      return true;
    }
    return state.status === 'VERIFIED' || state.status === 'BYPASSED';
  }

  protected canHandOver(ticket: TicketResponse): boolean {
    return this.isPacked(ticket) && this.canHandOverProof(ticket);
  }

  protected async handOver(ticket: TicketResponse): Promise<void> {
    const scope = this.location.scope();
    if (!scope || this.isBusy(ticket) || !this.canHandOver(ticket)) {
      return;
    }
    this.setBusy(ticket.ticketId, true);
    try {
      await firstValueFrom(this.kitchen.handOver(scope, ticket.ticketId));
      // Handed over — it belongs to the order's own delivery/pickup path now,
      // not the pass.
      this.tickets.update((current) => current.filter((row) => row.ticketId !== ticket.ticketId));
      this.packedTicketIds.update((current) => {
        const next = new Set(current);
        next.delete(ticket.ticketId);
        return next;
      });
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
