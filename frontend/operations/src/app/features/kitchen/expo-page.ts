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
import { operationsPaths } from '../../core/api/operations-paths';
import { ApiError } from '../../core/api/problem-details';
import { CurrentLocation } from '../../core/auth/current-location';
import { I18n } from '../../core/i18n/i18n';
import { TPipe } from '../../core/i18n/t.pipe';
import { describeApiError } from '../orders/order-errors';
import { OrderDetailResponse, OrderLine } from '../orders/order-detail';
import { KitchenApi, StationResponse, TicketItemView, TicketResponse } from './kitchen-api';

/** Same cadence as the KDS queue, until ADR 0045 exists. */
const POLL_INTERVAL_MS = 10_000;

/**
 * IA 2.3 — Expo / handover (Раздача): assembly and release.
 *
 * **Owns, built**: the READY queue (`stream=pass`); per-department ready
 * roll-up (each line still shows the station it routed to, same as the KDS
 * expanded row); custody transfer via the new `KITCHEN_TICKET_HANDOVER`
 * capability and `/hand-over` endpoint this wave adds — see the wave's final
 * report.
 *
 * **Not built, honestly**: provider handover-code verification as the
 * release gate (ADR 0040 — `order_handover_challenges` does not exist).
 * Hand-over here is a plain custody-transfer button with no code check, and
 * the panel says so rather than pretending a verification step it cannot
 * perform.
 */
@Component({
  selector: 'q-expo-page',
  imports: [TPipe],
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

  protected async handOver(ticket: TicketResponse): Promise<void> {
    const scope = this.location.scope();
    if (!scope || this.isBusy(ticket)) {
      return;
    }
    this.setBusy(ticket.ticketId, true);
    try {
      await firstValueFrom(this.kitchen.handOver(scope, ticket.ticketId));
      // Handed over — it belongs to the order's own delivery/pickup path now,
      // not the pass.
      this.tickets.update((current) => current.filter((row) => row.ticketId !== ticket.ticketId));
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
