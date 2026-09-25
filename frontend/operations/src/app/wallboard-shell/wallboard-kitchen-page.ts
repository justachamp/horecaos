import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  inject,
  signal,
} from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiError } from '../core/api/problem-details';
import { CurrentLocation } from '../core/auth/current-location';
import { I18n } from '../core/i18n/i18n';
import { TPipe } from '../core/i18n/t.pipe';
import { RealtimeClient } from '../core/realtime/realtime-client';
import { ConnectionStateBanner } from '../shared/ui/connection-state-banner';
import { LiveBadge } from '../shared/ui/live-badge';
import {
  ItemResponse,
  KitchenApi,
  StationResponse,
  TicketItemView,
  TicketResponse,
} from '../features/kitchen/kitchen-api';
import { KitchenItemAction, availableItemActions } from '../features/kitchen/kitchen-ticket';
import { describeApiError, errorReference } from '../features/orders/order-errors';

/** The mandated fallback (ADR 0045): every live surface keeps a polling path that must work with the stream disabled. */
const POLL_INTERVAL_MS = 10_000;

/** One missed tick still reads as live at a glance — same rule as `wallboard-shell.ts`. */
const FRESH_THRESHOLD_MS = POLL_INTERVAL_MS * 1.5;
/** Past this the banner names the data age rather than only colouring it — ADR 0045's own kitchen-display state table (`STALE`). */
const STALE_THRESHOLD_MS = POLL_INTERVAL_MS * 3;
/** Independent of the poll, so a hung request still ages the banner — see `wallboard-shell.ts`'s identical clock. */
const CLOCK_TICK_MS = 1_000;

/** A single reason code for every touch-shell recall, the same simplification the desk console's own `KDS_ASSIGN_REASON` family already makes for its own actions. */
const WALLBOARD_RECALL_REASON = 'OPERATIONS_WALLBOARD_RECALL';

export type WallboardKitchenFreshness = 'loading' | 'fresh' | 'aging' | 'stale';

/**
 * Row 2.1 — the KDS touch shell (ADR 0045, ADR 0041's "Displays and
 * devices"), hosted like the wallboard (`wallboard-shell.ts`, IA `0.1e`)
 * rather than inside the operator console `Shell`: no rail, no top bar, a
 * fullscreen surface for a tablet mounted at the pass.
 *
 * **Not the same thing as `/device`.** `DeviceShell` (ADR 0079) is a kiosk
 * that authenticates as its own device principal, with no person signed in.
 * This page is the other case ADR 0045's "Displays and devices" section
 * covers: a staff person's own tablet, signed in with their ordinary
 * Keycloak session, wanting the queue at touch scale instead of the desk
 * console's dense `kitchen-queue-page.ts`. It reuses `KitchenApi` directly —
 * the same `kitchen.ticket.read`/`advance`/`recall` grants a signed-in cook
 * already holds — rather than `DeviceBoardApi`, which is scoped to the
 * narrower device-principal grant.
 *
 * **Large targets, no hover-only affordances.** Every action a line can take
 * is a visible button at all times — never a hover-revealed icon — sized for
 * a finger rather than a cursor (`wallboard-kitchen-page.css`'s own touch
 * scale, the same values `device-shell.css` established for the paired KDS).
 *
 * **The accelerator and its fallback.** `RealtimeClient` already opens one
 * session-wide stream subscribed to `kitchen_board` (ADR 0045) whenever the
 * signed-in principal holds `kitchen.ticket.read`; this page listens for a
 * frame on that channel and re-reads the board at once, on top of the
 * unconditional {@link POLL_INTERVAL_MS} poll every live surface keeps —
 * turning the stream off changes no other code here, by design.
 *
 * **The offline/disconnected banner.** `ConnectionStateBanner` reports the
 * transport's own state (reconnecting/unavailable); this page adds the
 * second half ADR 0045's kitchen-display state table asks for — how long
 * ago the board last actually refreshed — so a KDS that is still polling but
 * getting stale data is visibly not fresh even while the stream itself looks
 * connected.
 */
@Component({
  selector: 'q-wallboard-kitchen-page',
  imports: [TPipe, ConnectionStateBanner, LiveBadge],
  templateUrl: './wallboard-kitchen-page.html',
  styleUrl: './wallboard-kitchen-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class WallboardKitchenPage implements OnInit {
  private readonly kitchen = inject(KitchenApi);
  private readonly location = inject(CurrentLocation);
  private readonly realtime = inject(RealtimeClient);
  private readonly destroyRef = inject(DestroyRef);
  protected readonly i18n = inject(I18n);

  protected readonly connectionState = this.realtime.state;

  protected readonly tickets = signal<readonly TicketResponse[]>([]);
  protected readonly stationsById = signal<ReadonlyMap<string, StationResponse>>(new Map());
  protected readonly firstLoadComplete = signal(false);
  protected readonly denied = signal(false);
  protected readonly lastError = signal<ApiError | null>(null);
  protected readonly lastUpdatedAt = signal<Date | null>(null);
  protected readonly busyItemIds = signal<ReadonlySet<string>>(new Set());
  protected readonly actionNotice = signal<string | null>(null);
  /** Ticks every {@link CLOCK_TICK_MS} so {@link freshnessState} ages between polls — see `wallboard-shell.ts`'s identical field. */
  protected readonly now = signal(Date.now());

  private pollHandle: ReturnType<typeof setInterval> | null = null;
  private clockHandle: ReturnType<typeof setInterval> | null = null;

  ngOnInit(): void {
    this.pollHandle = setInterval(() => void this.refresh(), POLL_INTERVAL_MS);
    this.clockHandle = setInterval(() => this.now.set(Date.now()), CLOCK_TICK_MS);

    // The accelerator: a KITCHEN_BOARD signal on this connection means this
    // branch's board just changed, so refresh at once rather than waiting up
    // to POLL_INTERVAL_MS for the fallback above to notice.
    const unsubscribeRealtime = this.realtime.onFrame((frame) => {
      if (
        (frame.kind === 'signal' && frame.channel === 'kitchen_board') ||
        frame.kind === 'resync'
      ) {
        void this.refresh();
      }
    });

    this.destroyRef.onDestroy(() => {
      if (this.pollHandle !== null) {
        clearInterval(this.pollHandle);
      }
      if (this.clockHandle !== null) {
        clearInterval(this.clockHandle);
      }
      unsubscribeRealtime();
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
        // Station names are a label, not a correctness requirement — a board
        // that cannot resolve them still shows raw ids rather than nothing.
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
      const board = await this.kitchen.board(scope, 'live');
      this.tickets.set(board.tickets);
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
      this.firstLoadComplete.set(true);
    }
  }

  protected availableActions(
    ticket: TicketResponse,
    item: TicketItemView,
  ): readonly KitchenItemAction[] {
    return availableItemActions(item.status, ticket.status);
  }

  protected isItemBusy(item: TicketItemView): boolean {
    return this.busyItemIds().has(item.itemId);
  }

  protected async onItemAction(item: TicketItemView, action: KitchenItemAction): Promise<void> {
    const scope = this.location.scope();
    if (!scope || this.isItemBusy(item)) {
      return;
    }
    this.setItemBusy(item.itemId, true);
    try {
      let response: ItemResponse;
      switch (action) {
        case 'START':
          response = await firstValueFrom(this.kitchen.start(scope, item.itemId));
          break;
        case 'READY':
          response = await firstValueFrom(this.kitchen.ready(scope, item.itemId));
          break;
        case 'RECALL':
          response = await firstValueFrom(
            this.kitchen.recall(scope, item.itemId, WALLBOARD_RECALL_REASON),
          );
          break;
      }
      this.applyItemUpdate(response);
    } catch {
      this.actionNotice.set(this.i18n.t('wallboardKitchen.actionError'));
    } finally {
      this.setItemBusy(item.itemId, false);
    }
  }

  protected dismissNotice(): void {
    this.actionNotice.set(null);
  }

  private setItemBusy(itemId: string, busy: boolean): void {
    this.busyItemIds.update((current) => {
      const next = new Set(current);
      if (busy) {
        next.add(itemId);
      } else {
        next.delete(itemId);
      }
      return next;
    });
  }

  private applyItemUpdate(response: ItemResponse): void {
    this.tickets.update((current) =>
      current.map((ticket) => {
        if (!ticket.items.some((item) => item.itemId === response.item.itemId)) {
          return ticket;
        }
        return {
          ...ticket,
          status: response.ticketStatus,
          version: response.ticketVersion,
          items: ticket.items.map((item) =>
            item.itemId === response.item.itemId ? response.item : item,
          ),
        };
      }),
    );
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

  protected itemActionLabel(action: KitchenItemAction): string {
    return this.i18n.t(`kitchen.item.action.${action}`);
  }

  /**
   * The second half of ADR 0045's kitchen-display state table: how long ago
   * the board last actually refreshed, ticked by {@link now} independently
   * of {@link POLL_INTERVAL_MS} so a hung request still ages this rather than
   * waiting for a poll tick a broken connection will never deliver.
   */
  protected freshnessState(): WallboardKitchenFreshness {
    if (!this.firstLoadComplete()) {
      return 'loading';
    }
    const updated = this.lastUpdatedAt();
    if (!updated) {
      return 'stale';
    }
    const age = this.now() - updated.getTime();
    if (age < FRESH_THRESHOLD_MS) {
      return 'fresh';
    }
    if (age < STALE_THRESHOLD_MS) {
      return 'aging';
    }
    return 'stale';
  }

  protected freshnessLabel(): string {
    const state = this.freshnessState();
    if (state === 'loading') {
      return this.i18n.t('wallboard.freshness.loading');
    }
    const updated = this.lastUpdatedAt();
    const seconds = updated ? Math.max(0, Math.floor((this.now() - updated.getTime()) / 1000)) : 0;
    if (seconds < 60) {
      return this.i18n.t('wallboard.freshness.seconds', { seconds });
    }
    const minutes = Math.floor(seconds / 60);
    return this.i18n.t('wallboard.freshness.minutes', { minutes });
  }

  /** Mirrors `wallboard-shell.ts`'s own error band — same helper, same ADR 0031 codes. */
  protected errorMessage(error: ApiError): string {
    return describeApiError(error, (key, values) => this.i18n.t(key, values));
  }

  protected errorReference(error: ApiError): string {
    return errorReference(error);
  }
}
