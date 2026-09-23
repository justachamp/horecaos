import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  inject,
  signal,
} from '@angular/core';
import { Router } from '@angular/router';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { LocationScope, operationsPaths } from '../../core/api/operations-paths';
import { ApiError } from '../../core/api/problem-details';
import { CurrentLocation } from '../../core/auth/current-location';
import { TimeZone, formatClock } from '../../core/format/datetime';
import { LatenessPolicy, PLATFORM_DEFAULT_LATENESS_POLICY } from '../../core/lateness-policy';
import { LatenessPolicyApi } from '../../core/lateness-policy-api';
import { I18n } from '../../core/i18n/i18n';
import { TPipe } from '../../core/i18n/t.pipe';
import { CouriersApi, RosterEntryResponse } from '../couriers/couriers-api';
import {
  DispatchApi,
  ExternalPartnerResponse,
  ExternalQuoteResponse,
  PlanQueueResponse,
} from '../delivery/dispatch-api';
import {
  ChangeServiceStateRequest,
  LocationsApi,
  ServiceSummaryResponse,
} from '../settings/locations/locations-api';
import {
  ExternalBookingSubmission,
  ExternalCourierDialog,
} from '../orders/external-courier-dialog';
import { describeApiError } from '../orders/order-errors';
import { OrderDeliveryApi } from '../orders/order-delivery-api';
import { OrderDetailResponse, OrderLine } from '../orders/order-detail';
import { OrderRevealApi } from '../orders/order-reveal-api';
import {
  BoardCounts,
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

/**
 * Fixed, English, machine-facing ADR 0029 reveal purpose — matches
 * `order-detail-pane.ts`'s own `REVEAL_PURPOSE.lineNote`, read by whoever
 * reviews the audit log rather than by the operator, so it is never
 * translated.
 */
const REVEAL_LINE_NOTE_PURPOSE = 'Operations console: view a line note (kitchen)';

/** `DispatchApi.assign`'s own reason code, distinct from the dispatch board's `OPERATIONS_MANUAL_ASSIGN` so an auditor can tell the pass assigned it from the board. */
const KDS_ASSIGN_REASON = 'OPERATIONS_KDS_ASSIGN';

/** `OrderDeliveryApi.decideExternalCourier`'s own reason codes from the pass (gap map row 2.1c), distinct from the order detail pane's `OPERATIONS_EXTERNAL_BOOKING_*` for the same auditing reason `KDS_ASSIGN_REASON` exists. */
const KDS_EXTERNAL_BOOKING_ACCEPT_REASON = 'OPERATIONS_KDS_EXTERNAL_BOOKING_ACCEPT';
const KDS_EXTERNAL_BOOKING_ABANDON_REASON = 'OPERATIONS_KDS_EXTERNAL_BOOKING_ABANDON';

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
 * with a typed aggregator tab (wave P16: `channelSystemType`, resolved off
 * `sales_channels.system_type`, not a raw `channelCode` chip); server-side
 * tab counts, exact over every matching ticket rather than over the one
 * `stream=live` page this screen loads (wave P16: `BoardResponse.counts`);
 * SLA colour from the ticket's own `targetReadyAt` (a real promise, unlike
 * the order board's ADR 0014 workaround); department routing shown per line
 * via the branch's stations; start/ready/recall; the operator's own
 * `kitchenNote`, read from the order the line belongs to (`ItemView` carries
 * no name — see `kitchen-api.ts`'s own doc); a per-line customer note, read
 * on demand through the audited `OrderRevealApi.revealLineNote` (wave P16 —
 * before it, `hasNote` rendered as a bare chip and the note text was never
 * fetched even though the endpoint existed); assigning an in-house courier
 * to a delivery ticket from the pass (wave P16: `DispatchApi.assign` joined
 * to this board's own `orderId` — the endpoint was already client-proven on
 * the dispatch board, P18); a counter-sale link to `orders/new` (P13's
 * screen, which did not exist when this class's own doc first called this
 * not-built); the branch open/closed toggle, reusing settings 10.2's own
 * `LocationsApi` rather than inventing a second one; dispatching to an
 * *external* provider from the pass (wave 9 w5, gap map row 2.1c) — «Вызвать
 * курьера» reuses `q-external-courier-dialog` verbatim (the same component
 * `order-detail-pane.ts` renders for row 1.2e) against `OrderDeliveryApi
 * .requestExternalCourierQuote`/`decideExternalCourier`
 * (`OrderDeliveryController.externalCourier`), the order-keyed path both
 * screens now share instead of each resolving `planId` its own way; the
 * winning shipment's `sourceType`/status renders on every visible delivery
 * ticket from the same 10-second poll that loads the board itself
 * ({@link refreshShipmentStates}), not only once an operator happens to open
 * a picker for that one ticket.
 *
 * **Not built, honestly**: preset product comments (no backend vocabulary
 * exists at all — see the wave's report); change payment type from the
 * kitchen (no backend endpoint exists for it).
 */
@Component({
  selector: 'q-kitchen-queue-page',
  imports: [TPipe, ExternalCourierDialog],
  templateUrl: './kitchen-queue-page.html',
  styleUrl: './kitchen-queue-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class KitchenQueuePage implements OnInit {
  private readonly api = inject(ApiClient);
  private readonly kitchen = inject(KitchenApi);
  private readonly location = inject(CurrentLocation);
  private readonly locationsApi = inject(LocationsApi);
  private readonly latenessPolicyApi = inject(LatenessPolicyApi);
  private readonly revealApi = inject(OrderRevealApi);
  private readonly dispatchApi = inject(DispatchApi);
  private readonly orderDeliveryApi = inject(OrderDeliveryApi);
  private readonly couriersApi = inject(CouriersApi);
  private readonly router = inject(Router);
  private readonly i18n = inject(I18n);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly tabs = KITCHEN_TABS.map((id) => KITCHEN_TAB_DEFINITIONS[id]);
  protected readonly activeTab = signal<KitchenTabId>(DEFAULT_KITCHEN_TAB);

  protected readonly firstLoadComplete = signal(false);
  protected readonly denied = signal(false);
  protected readonly lastError = signal<ApiError | null>(null);
  protected readonly wiringWarning = signal(false);

  protected readonly tickets = signal<readonly TicketResponse[]>([]);
  /** The board's own exact tab badges (wave P16) — `null` only before the first load settles. */
  protected readonly boardCounts = signal<BoardCounts | null>(null);
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

  // ------------------------------------------------------- P16: line notes

  /** `undefined` = never revealed this load; `null` = revealed and genuinely empty. Keyed by `lineId`. */
  protected readonly revealedNotes = signal<ReadonlyMap<string, string | null>>(new Map());
  protected readonly revealingNoteFor = signal<string | null>(null);

  // ------------------------------------------------- P16: assign from the pass

  protected readonly courierRoster = signal<readonly RosterEntryResponse[]>([]);
  protected readonly assignPickerForTicketId = signal<string | null>(null);
  /** The dispatch queue's own plan for the open picker's ticket, joined by `orderId` (`DispatchController` carries no `orderId`-keyed read of its own). `undefined` while resolving, `null` when none was found. */
  protected readonly assignPickerPlan = signal<PlanQueueResponse | null | undefined>(undefined);
  protected readonly assigningTicketId = signal<string | null>(null);
  /**
   * The last-resolved shipment for a ticket, keyed by `ticketId` (gap map
   * rows 1.2e/2.1c) — kept current for every visible delivery ticket by
   * {@link refreshShipmentStates} on each board poll, and also written by
   * {@link resolvePlanForTicket} whenever a picker/dialog resolves one
   * ticket's plan directly, so «Вызвать курьера»'s own PARTNER state stays
   * visible on the ticket row after the operator closes the dialog, rather
   * than only while it happens to be open.
   */
  protected readonly shipmentByTicketId = signal<
    ReadonlyMap<string, PlanQueueResponse['shipment']>
  >(new Map());

  // ------------------------------------------------- wave 9 w5: external dispatch from the pass

  /** Which ticket's «Вызвать курьера» dialog is open (gap map row 2.1c). `null` when closed. */
  protected readonly externalCourierTicketId = signal<string | null>(null);
  private externalCourierOrderId: string | null = null;
  /** The plan behind the open dialog's ticket — same resolution `assignPickerPlan` uses, see {@link resolvePlanForTicket}. */
  protected readonly externalCourierPlan = signal<PlanQueueResponse | null | undefined>(undefined);
  protected readonly externalPartners = signal<readonly ExternalPartnerResponse[]>([]);
  protected readonly externalQuote = signal<ExternalQuoteResponse | null>(null);
  protected readonly externalCourierBusy = signal(false);

  private pollHandle: ReturnType<typeof setInterval> | null = null;

  /** The resolved `ordering.lateness` policy (wave P06) — fetched once in {@link start}. */
  private latenessPolicy: LatenessPolicy = PLATFORM_DEFAULT_LATENESS_POLICY;

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
    this.latenessPolicy = await this.latenessPolicyApi.resolve(scope);
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
    try {
      this.courierRoster.set(await this.couriersApi.roster(scope.tenantId));
    } catch {
      // Assign-from-the-pass simply offers no courier list if this fails —
      // every other affordance on the board still works.
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
      this.boardCounts.set(board.counts ?? null);
      this.wiringWarning.set(board.warnings.length > 0);
      this.denied.set(false);
      this.lastError.set(null);
      await this.refreshShipmentStates(scope, board.tickets);
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

  /**
   * The board's own server-side badge (wave P16) — exact over every matching
   * ticket, not the client-side filter over `stream=live`'s own page this
   * method used to run (gap map row 2.1's own finding). `null` before the
   * first load settles, matching `stop-list-page.ts`'s own "no wrong number"
   * rule for a not-yet-known count.
   */
  protected tabCount(tab: KitchenTabId): number | null {
    const counts = this.boardCounts();
    if (!counts) {
      return null;
    }
    switch (tab) {
      case 'all':
        return counts.total;
      case 'delivery':
        return counts.delivery;
      case 'pickup':
        return counts.pickup;
      case 'dineIn':
        return counts.dineIn;
      case 'aggregator':
        return counts.aggregator;
    }
  }

  protected visibleTickets(): readonly TicketResponse[] {
    const tab = this.activeTab();
    return this.tickets()
      .filter((ticket) => isKitchenTabMember(tab, ticket.fulfilmentMode, ticket.channelSystemType))
      .slice()
      .sort(compareBySeverityThenTime(this.latenessPolicy));
  }

  protected severityTone(ticket: TicketResponse): 'danger' | 'warning' | 'none' {
    return computeTicketSeverity(toSeverityInput(ticket), new Date(), this.latenessPolicy).tone;
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

  /** The courier ETA chip (wave P11, gap map row 2.1a) — the winning partner quote's own ETA, joined by `TicketResponse.courierEtaAt`. */
  protected courierEtaLabel(ticket: TicketResponse): string | null {
    return ticket.courierEtaAt
      ? this.i18n.t('kitchen.ticket.courierEta', {
          time: formatClock(new Date(ticket.courierEtaAt), PLACEHOLDER_TIME_ZONE),
        })
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

  // ------------------------------------------------------- P16: line notes

  /** `undefined` = never revealed this load; `null` = revealed and genuinely empty — mirrors `order-detail-pane.ts`'s own contract. */
  protected revealedNote(lineId: string): string | null | undefined {
    return this.revealedNotes().get(lineId);
  }

  protected isRevealingNote(lineId: string): boolean {
    return this.revealingNoteFor() === lineId;
  }

  /**
   * The already-translated display text for a revealed line — computed here
   * rather than inline in the template because `revealedNote`'s `string |
   * null` return does not narrow across two separate template calls to it,
   * and a null note (revealed, genuinely empty) must render differently
   * from a non-empty one.
   */
  protected noteDisplayText(lineId: string): string {
    const note = this.revealedNotes().get(lineId);
    return note
      ? this.i18n.t('kitchen.item.customerNote', { note })
      : this.i18n.t('kitchen.item.noteEmpty');
  }

  /**
   * The audited ADR 0029 reveal a cook clicks for once per line — `hasNote`
   * alone never carries the text (see `kitchen-api.ts`'s own doc), and a
   * bare "has note" chip is exactly what gap map row 2.1 names as the
   * defect this closes: «без лука» never reaching the line.
   */
  protected async revealLineNote(ticket: TicketResponse, lineId: string): Promise<void> {
    const scope = this.location.scope();
    if (!scope || this.revealedNotes().has(lineId)) {
      return;
    }
    this.revealingNoteFor.set(lineId);
    try {
      const result = await firstValueFrom(
        this.revealApi.revealLineNote(scope, ticket.orderId, lineId, REVEAL_LINE_NOTE_PURPOSE),
      );
      this.revealedNotes.update((current) => new Map(current).set(lineId, result.note));
    } catch (error) {
      this.actionNotice.set(this.describeError(error));
    } finally {
      this.revealingNoteFor.set(null);
    }
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
      this.actionNotice.set(this.describeError(error));
    } finally {
      this.setItemBusy(item.itemId, false);
    }
  }

  protected dismissNotice(): void {
    this.actionNotice.set(null);
  }

  private describeError(error: unknown): string {
    return error instanceof ApiError
      ? describeApiError(error, (key, values) => this.i18n.t(key, values))
      : this.i18n.t('error.unknown.noReference');
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
      this.actionNotice.set(this.describeError(error));
    } finally {
      this.togglingService.set(false);
    }
  }

  // ------------------------------------------------- P16: assign from the pass

  protected isDeliveryTicket(ticket: TicketResponse): boolean {
    return ticket.fulfilmentMode === 'DELIVERY';
  }

  protected isAssignPickerOpen(ticket: TicketResponse): boolean {
    return this.assignPickerForTicketId() === ticket.ticketId;
  }

  protected isAssigning(ticket: TicketResponse): boolean {
    return this.assigningTicketId() === ticket.ticketId;
  }

  /**
   * Refreshes {@link shipmentByTicketId} for every visible delivery ticket
   * on each board poll, from the same branch-wide dispatch queue read
   * {@link resolvePlanForTicket} makes for one ticket at a time. Without
   * this, a PARTNER shipment another operator assigned from the order
   * detail pane (row 1.2e) stayed invisible on this pass — still offering
   * «Вызвать курьера» with no "assigned" badge — until someone happened to
   * open a picker for that exact ticket (gap map rows 1.2e/2.1c's own
   * finding: the button/badge must reflect real state without requiring a
   * click first).
   */
  private async refreshShipmentStates(
    scope: LocationScope,
    tickets: readonly TicketResponse[],
  ): Promise<void> {
    const deliveryTickets = tickets.filter((ticket) => ticket.fulfilmentMode === 'DELIVERY');
    if (deliveryTickets.length === 0) {
      return;
    }
    try {
      const queue = await this.dispatchApi.queue(scope);
      const shipmentByOrderId = new Map(queue.map((plan) => [plan.orderId, plan.shipment ?? null]));
      this.shipmentByTicketId.update((byTicket) => {
        const next = new Map(byTicket);
        for (const ticket of deliveryTickets) {
          next.set(ticket.ticketId, shipmentByOrderId.get(ticket.orderId) ?? null);
        }
        return next;
      });
    } catch {
      // Best-effort, same rule as `start()`'s other reads -- the button/badge
      // simply keep their last-known state if this poll's dispatch-queue read
      // fails; the next poll tries again.
    }
  }

  /**
   * The one delivery plan a ticket's own order maps to. `DispatchController`'s
   * queue is keyed by `planId`, not `orderId` — the kitchen board never
   * learned a `planId` of its own — so this reads the branch's whole dispatch
   * queue (`P18`'s own `<=200`-row read) and joins it here by `orderId`,
   * exactly the seam wave P16's own brief named. Shared by the in-house
   * assign picker and the external-courier dialog (gap map rows 2.1/2.1c) —
   * both act on the same plan for the same ticket.
   */
  private async resolvePlanForTicket(
    scope: LocationScope,
    ticket: TicketResponse,
  ): Promise<PlanQueueResponse | null> {
    const queue = await this.dispatchApi.queue(scope);
    const plan = queue.find((candidate) => candidate.orderId === ticket.orderId) ?? null;
    this.shipmentByTicketId.update((byTicket) => {
      const next = new Map(byTicket);
      next.set(ticket.ticketId, plan?.shipment ?? null);
      return next;
    });
    return plan;
  }

  protected async openAssignPicker(ticket: TicketResponse): Promise<void> {
    if (this.isAssignPickerOpen(ticket)) {
      this.assignPickerForTicketId.set(null);
      return;
    }
    const scope = this.location.scope();
    this.assignPickerForTicketId.set(ticket.ticketId);
    this.assignPickerPlan.set(undefined);
    if (!scope) {
      this.assignPickerPlan.set(null);
      return;
    }
    try {
      this.assignPickerPlan.set(await this.resolvePlanForTicket(scope, ticket));
    } catch (error) {
      this.assignPickerPlan.set(null);
      this.actionNotice.set(this.describeError(error));
    }
  }

  protected closeAssignPicker(): void {
    this.assignPickerForTicketId.set(null);
    this.assignPickerPlan.set(undefined);
  }

  protected async assignCourier(ticket: TicketResponse, courierId: string): Promise<void> {
    const scope = this.location.scope();
    const plan = this.assignPickerPlan();
    if (!scope || !plan) {
      return;
    }
    this.assigningTicketId.set(ticket.ticketId);
    try {
      const result = await this.dispatchApi.assign(
        scope,
        plan.planId,
        courierId,
        plan.version,
        KDS_ASSIGN_REASON,
      );
      if (!result.applied) {
        this.actionNotice.set(
          this.i18n.t('kitchen.assign.refused', { reason: result.reason ?? '' }),
        );
      }
      this.closeAssignPicker();
    } catch (error) {
      this.actionNotice.set(this.describeError(error));
    } finally {
      this.assigningTicketId.set(null);
    }
  }

  // ------------------------------------------------- wave 9 w5: external dispatch from the pass

  protected isExternalCourierOpen(ticket: TicketResponse): boolean {
    return this.externalCourierTicketId() === ticket.ticketId;
  }

  /**
   * Opens «Вызвать курьера» (gap map row 2.1c) and resolves the ticket's plan
   * the same way {@link openAssignPicker} does — the picker and this dialog
   * act on one plan, they just reached it independently for the same reason
   * `resolvePlanForTicket`'s own doc gives.
   */
  protected async openExternalCourierDialog(ticket: TicketResponse): Promise<void> {
    this.assignPickerForTicketId.set(null);
    this.externalCourierTicketId.set(ticket.ticketId);
    this.externalCourierOrderId = ticket.orderId;
    this.externalCourierPlan.set(undefined);
    this.externalQuote.set(null);
    this.externalPartners.set([]);
    const scope = this.location.scope();
    if (!scope) {
      this.externalCourierPlan.set(null);
      return;
    }
    try {
      const plan = await this.resolvePlanForTicket(scope, ticket);
      this.externalCourierPlan.set(plan);
      if (plan) {
        this.externalPartners.set(await this.dispatchApi.externalPartners(scope, plan.planId));
      }
    } catch (error) {
      this.externalCourierPlan.set(null);
      this.actionNotice.set(this.describeError(error));
    }
  }

  protected closeExternalCourierDialog(): void {
    this.externalCourierTicketId.set(null);
    this.externalCourierOrderId = null;
    this.externalCourierPlan.set(undefined);
    this.externalQuote.set(null);
    this.externalPartners.set([]);
  }

  protected async requestExternalQuote(bindingId: string): Promise<void> {
    const scope = this.location.scope();
    const orderId = this.externalCourierOrderId;
    if (!scope || !orderId) {
      return;
    }
    this.externalCourierBusy.set(true);
    try {
      this.externalQuote.set(
        await this.orderDeliveryApi.requestExternalCourierQuote(scope, orderId, bindingId),
      );
    } catch (error) {
      this.actionNotice.set(this.describeError(error));
    } finally {
      this.externalCourierBusy.set(false);
    }
  }

  protected async acceptExternalBooking(submission: ExternalBookingSubmission): Promise<void> {
    await this.settleExternalBooking(submission, 'ACCEPT', KDS_EXTERNAL_BOOKING_ACCEPT_REASON);
  }

  protected async abandonExternalBooking(submission: ExternalBookingSubmission): Promise<void> {
    await this.settleExternalBooking(submission, 'ABANDON', KDS_EXTERNAL_BOOKING_ABANDON_REASON);
  }

  private async settleExternalBooking(
    submission: ExternalBookingSubmission,
    decision: 'ACCEPT' | 'ABANDON',
    reasonCode: string,
  ): Promise<void> {
    const scope = this.location.scope();
    const orderId = this.externalCourierOrderId;
    if (!scope || !orderId) {
      return;
    }
    this.externalCourierBusy.set(true);
    try {
      const result = await this.orderDeliveryApi.decideExternalCourier(
        scope,
        orderId,
        submission.bindingId,
        submission.quoteId,
        decision,
        reasonCode,
      );
      if (!result.applied && result.reason) {
        this.actionNotice.set(this.i18n.t('kitchen.assign.refused', { reason: result.reason }));
      }
      this.closeExternalCourierDialog();
      await this.refresh();
    } catch (error) {
      this.actionNotice.set(this.describeError(error));
    } finally {
      this.externalCourierBusy.set(false);
    }
  }

  // ------------------------------------------------------- P16: counter sale

  /**
   * Links to `P13`'s new-order screen (`/orders/new`) rather than duplicating
   * it here — this class's own doc used to call that page not-built, which
   * has not been true since `P13` merged.
   */
  protected startCounterSale(): void {
    void this.router.navigateByUrl('/orders/new');
  }
}

function toSeverityInput(ticket: TicketResponse): {
  targetReadyAt: Date | null;
  createdAt: Date;
  fulfilmentMode: string | null | undefined;
} {
  return {
    targetReadyAt: ticket.targetReadyAt ? new Date(ticket.targetReadyAt) : null,
    createdAt: new Date(ticket.createdAt),
    fulfilmentMode: ticket.fulfilmentMode,
  };
}

const SEVERITY_RANK: Readonly<Record<'danger' | 'warning' | 'none', number>> = {
  danger: 0,
  warning: 1,
  none: 2,
};

/** A comparator closure over the one resolved policy this page's tickets all share (wave P06). */
function compareBySeverityThenTime(
  policy: LatenessPolicy,
): (a: TicketResponse, b: TicketResponse) => number {
  return (a, b) => {
    const now = new Date();
    const rankDiff =
      SEVERITY_RANK[computeTicketSeverity(toSeverityInput(a), now, policy).tone] -
      SEVERITY_RANK[computeTicketSeverity(toSeverityInput(b), now, policy).tone];
    if (rankDiff !== 0) {
      return rankDiff;
    }
    return new Date(a.createdAt).getTime() - new Date(b.createdAt).getTime();
  };
}
