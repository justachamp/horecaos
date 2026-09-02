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
import { TimeZone, formatClock } from '../../core/format/datetime';
import { I18n } from '../../core/i18n/i18n';
import { TPipe } from '../../core/i18n/t.pipe';
import {
  ChangeServiceStateRequest,
  LocationsApi,
  ServiceSummaryResponse,
} from '../settings/locations/locations-api';
import { describeApiError } from '../orders/order-errors';
import { OrderDetailResponse, OrderLine } from '../orders/order-detail';
import {
  BoardResponse,
  ItemResponse,
  KitchenApi,
  StationResponse,
  TicketItemView,
  TicketResponse,
} from './kitchen-api';
import {
  DEFAULT_KITCHEN_TAB,
  KITCHEN_TABS,
  KITCHEN_TAB_DEFINITIONS,
  KitchenItemAction,
  KitchenTabId,
  availableItemActions,
  computeTicketSeverity,
  isKitchenTabId,
  isKitchenTabMember,
} from './kitchen-ticket';

/** Same cadence as the order board, until ADR 0045 live updates exist (§1.6). */
const POLL_INTERVAL_MS = 10_000;

/** See `order-queue.ts`'s identical constant — no location carries a timezone on any response this board reaches yet. */
const PLACEHOLDER_TIME_ZONE: TimeZone = 'Asia/Tashkent';

/**
 * The kitchen queue — IA 2.1, `docs/operations-spec/orders.md`'s kitchen
 * section corrected against the real backend (ADR 0041 is built, not "not
 * built" as the spec's own prose says — see the wave's final report).
 *
 * **Built**: the live board (`stream=live`), partitioned by fulfilment mode
 * with a channel chip; SLA colour from the ticket's own `targetReadyAt` (a
 * real promise, unlike the order board's ADR 0014 workaround); department
 * routing shown per line via the branch's stations; start/ready/recall;
 * per-line customer notes and the operator's own `kitchenNote`, both read
 * from the order the line belongs to (`ItemView` carries no name — see
 * `kitchen-api.ts`'s own doc); the branch open/closed toggle, reusing
 * settings 10.2's own `LocationsApi` rather than inventing a second one.
 *
 * **Not built, honestly**: preset product comments (no backend vocabulary
 * exists at all — see the wave's report); assign own courier / dispatch an
 * external provider and change payment type from the kitchen (no backend
 * endpoint exists for either); create an order from the kitchen (no
 * operator-facing order-create endpoint exists anywhere in this build — the
 * shell's own `F2`/`Новый заказ` action already routes to the same honest
 * not-built page any such affordance here would duplicate).
 */
@Component({
  selector: 'q-kitchen-queue-page',
  imports: [TPipe],
  templateUrl: './kitchen-queue-page.html',
  styleUrl: './kitchen-queue-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class KitchenQueuePage implements OnInit {
  private readonly api = inject(ApiClient);
  private readonly kitchen = inject(KitchenApi);
  private readonly location = inject(CurrentLocation);
  private readonly locationsApi = inject(LocationsApi);
  private readonly i18n = inject(I18n);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly tabs = KITCHEN_TABS.map((id) => KITCHEN_TAB_DEFINITIONS[id]);
  protected readonly activeTab = signal<KitchenTabId>(DEFAULT_KITCHEN_TAB);

  protected readonly firstLoadComplete = signal(false);
  protected readonly denied = signal(false);
  protected readonly lastError = signal<ApiError | null>(null);
  protected readonly wiringWarning = signal(false);

  protected readonly tickets = signal<readonly TicketResponse[]>([]);
  protected readonly stationsById = signal<ReadonlyMap<string, StationResponse>>(new Map());
  protected readonly expandedTicketId = signal<string | null>(null);
  protected readonly orderLinesByOrderId = signal<
    ReadonlyMap<string, ReadonlyMap<string, OrderLine>>
  >(new Map());
  protected readonly kitchenNoteByOrderId = signal<ReadonlyMap<string, string | null>>(new Map());
  protected readonly busyItemIds = signal<ReadonlySet<string>>(new Set());
  protected readonly actionNotice = signal<string | null>(null);

  protected readonly serviceSummary = signal<ServiceSummaryResponse | null>(null);
  protected readonly togglingService = signal(false);

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
    if (!scope) {
      this.denied.set(this.location.denied());
      this.firstLoadComplete.set(true);
      return;
    }
    try {
      const stations = await this.kitchen.stations(scope);
      this.stationsById.set(new Map(stations.map((station) => [station.stationId, station])));
    } catch {
      // Department names are a display nicety; the board still works with
      // raw station ids rendered as-is if this call fails.
    }
    try {
      this.serviceSummary.set(await this.locationsApi.serviceSummary(scope));
    } catch {
      // The toggle simply does not render without this — see the template.
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
      const board: BoardResponse = await this.kitchen.board(scope);
      this.tickets.set(board.tickets);
      this.wiringWarning.set(board.warnings.length > 0);
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

  protected selectTab(tab: KitchenTabId): void {
    this.activeTab.set(tab);
  }

  protected tabCount(tab: KitchenTabId): number {
    return this.tickets().filter((ticket) => isKitchenTabMember(tab, ticket.fulfilmentMode)).length;
  }

  protected visibleTickets(): readonly TicketResponse[] {
    const tab = this.activeTab();
    return this.tickets()
      .filter((ticket) => isKitchenTabMember(tab, ticket.fulfilmentMode))
      .slice()
      .sort(compareBySeverityThenTime);
  }

  protected severityTone(ticket: TicketResponse): 'danger' | 'warning' | 'none' {
    return computeTicketSeverity(toSeverityInput(ticket), new Date()).tone;
  }

  protected fulfilmentModeLabel(mode: string): string {
    switch (mode) {
      case 'DELIVERY':
        return this.i18n.t('kitchen.fulfilmentMode.DELIVERY');
      case 'PICKUP':
        return this.i18n.t('kitchen.fulfilmentMode.PICKUP');
      case 'DINE_IN':
        return this.i18n.t('kitchen.fulfilmentMode.DINE_IN');
      default:
        return mode;
    }
  }

  protected ticketStatusLabel(status: string): string {
    switch (status) {
      case 'HELD':
        return this.i18n.t('kitchen.ticket.status.HELD');
      case 'FIRED':
        return this.i18n.t('kitchen.ticket.status.FIRED');
      case 'IN_PRODUCTION':
        return this.i18n.t('kitchen.ticket.status.IN_PRODUCTION');
      case 'READY':
        return this.i18n.t('kitchen.ticket.status.READY');
      case 'HANDED_OVER':
        return this.i18n.t('kitchen.ticket.status.HANDED_OVER');
      case 'VOIDED':
        return this.i18n.t('kitchen.ticket.status.VOIDED');
      default:
        // Unrecognised status renders harmlessly, same rule as `order-status.ts`.
        return status;
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

  protected itemActionLabel(action: KitchenItemAction): string {
    switch (action) {
      case 'START':
        return this.i18n.t('kitchen.item.action.START');
      case 'READY':
        return this.i18n.t('kitchen.item.action.READY');
      case 'RECALL':
        return this.i18n.t('kitchen.item.action.RECALL');
    }
  }

  protected targetReadyLabel(ticket: TicketResponse): string | null {
    return ticket.targetReadyAt
      ? formatClock(new Date(ticket.targetReadyAt), PLACEHOLDER_TIME_ZONE)
      : null;
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

  // ------------------------------------------------------------- expansion

  protected isExpanded(ticket: TicketResponse): boolean {
    return this.expandedTicketId() === ticket.ticketId;
  }

  protected toggleExpand(ticket: TicketResponse): void {
    if (this.isExpanded(ticket)) {
      this.expandedTicketId.set(null);
      return;
    }
    this.expandedTicketId.set(ticket.ticketId);
    void this.ensureOrderLoaded(ticket.orderId);
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
      this.kitchenNoteByOrderId.update((current) =>
        new Map(current).set(orderId, result.value.kitchenNote ?? null),
      );
    } catch {
      // The item list still renders with generic line labels — see the template.
    }
  }

  protected lineFor(ticket: TicketResponse, item: TicketItemView): OrderLine | null {
    return this.orderLinesByOrderId().get(ticket.orderId)?.get(item.orderLineId) ?? null;
  }

  protected kitchenNoteFor(ticket: TicketResponse): string | null {
    return this.kitchenNoteByOrderId().get(ticket.orderId) ?? null;
  }

  // ----------------------------------------------------------------- actions

  protected itemActions(
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
    if (!scope) {
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
            this.kitchen.recall(scope, item.itemId, 'OPERATIONS_KDS_RECALL'),
          );
          break;
      }
      this.applyItemUpdate(response);
    } catch (error) {
      this.actionNotice.set(
        error instanceof ApiError
          ? describeApiError(error, (key, values) => this.i18n.t(key, values))
          : this.i18n.t('error.unknown.noReference'),
      );
    } finally {
      this.setItemBusy(item.itemId, false);
    }
  }

  protected dismissNotice(): void {
    this.actionNotice.set(null);
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

  // -------------------------------------------------- branch open/closed

  /** Settings 10.2's own tri-state — reused verbatim, not reinvented for this screen. */
  protected serviceModeLabel(): string {
    const summary = this.serviceSummary();
    if (!summary) {
      return '';
    }
    switch (summary.effectiveMode) {
      case 'FORCE_CLOSED':
        return this.i18n.t('kitchen.service.closed');
      case 'FORCE_OPEN':
        return this.i18n.t('kitchen.service.forcedOpen');
      default:
        return this.i18n.t('kitchen.service.open');
    }
  }

  protected canToggleService(): boolean {
    return this.serviceSummary() !== null;
  }

  protected async toggleService(): Promise<void> {
    const scope = this.location.scope();
    const summary = this.serviceSummary();
    if (!scope || !summary || this.togglingService()) {
      return;
    }
    const closing = summary.effectiveMode !== 'FORCE_CLOSED';
    const request: ChangeServiceStateRequest = closing
      ? { mode: 'FORCE_CLOSED', reasonCode: 'OPERATIONS_KITCHEN_TOGGLE' }
      : { mode: 'FOLLOW_SCHEDULE' };
    this.togglingService.set(true);
    try {
      await this.locationsApi.changeServiceState(scope, request);
      this.serviceSummary.set(await this.locationsApi.serviceSummary(scope));
    } catch (error) {
      this.actionNotice.set(
        error instanceof ApiError
          ? describeApiError(error, (key, values) => this.i18n.t(key, values))
          : this.i18n.t('error.unknown.noReference'),
      );
    } finally {
      this.togglingService.set(false);
    }
  }
}

function toSeverityInput(ticket: TicketResponse): { targetReadyAt: Date | null; createdAt: Date } {
  return {
    targetReadyAt: ticket.targetReadyAt ? new Date(ticket.targetReadyAt) : null,
    createdAt: new Date(ticket.createdAt),
  };
}

const SEVERITY_RANK: Readonly<Record<'danger' | 'warning' | 'none', number>> = {
  danger: 0,
  warning: 1,
  none: 2,
};

function compareBySeverityThenTime(a: TicketResponse, b: TicketResponse): number {
  const now = new Date();
  const rankDiff =
    SEVERITY_RANK[computeTicketSeverity(toSeverityInput(a), now).tone] -
    SEVERITY_RANK[computeTicketSeverity(toSeverityInput(b), now).tone];
  if (rankDiff !== 0) {
    return rankDiff;
  }
  return new Date(a.createdAt).getTime() - new Date(b.createdAt).getTime();
}
