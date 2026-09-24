import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  signal,
} from '@angular/core';
import { Router } from '@angular/router';
import { Observable, firstValueFrom } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { Versioned } from '../../core/api/aggregate-version';
import { firstPage } from '../../core/api/page';
import { LocationScope, operationsPaths } from '../../core/api/operations-paths';
import { ApiError, ApiErrorCode } from '../../core/api/problem-details';
import { CurrentLocation } from '../../core/auth/current-location';
import { TimeZone, formatClock, formatDateTime, formatDuration } from '../../core/format/datetime';
import { LatenessPolicy, PLATFORM_DEFAULT_LATENESS_POLICY } from '../../core/lateness-policy';
import { LatenessPolicyApi } from '../../core/lateness-policy-api';
import { formatMoney } from '../../core/format/money';
import { I18n, Locale } from '../../core/i18n/i18n';
import { MessageKey } from '../../core/i18n/messages.en';
import { TPipe } from '../../core/i18n/t.pipe';
import { Combobox, ComboboxOption } from '../../shared/ui/combobox';
import { ConfirmDialog } from '../../shared/ui/confirm-dialog';
import { StepItem, Steps } from '../../shared/ui/steps';
import { Timeline, TimelineEntry } from '../../shared/ui/timeline';
import { ReasonResponse, ReferenceDataApi } from '../settings/reference-data/reference-data-api';
import { SalesChannelsApi } from '../settings/sales-channels/sales-channels-api';
import { CouriersApi, RosterEntryResponse } from '../couriers/couriers-api';
import {
  DispatchApi,
  ExternalPartnerResponse,
  ExternalQuoteResponse,
} from '../delivery/dispatch-api';
import { KitchenApi, KitchenEventResponse, KitchenEventsResponse } from '../kitchen/kitchen-api';
import { deliveryLifecycleSteps } from './delivery-lifecycle-steps';
import { kitchenLifecycleSteps } from './kitchen-lifecycle-steps';
import {
  DecisionIdRegistry,
  OrderActionResponse,
  actionLabel,
  decisionOutcomeLabel,
} from './order-actions';
import { DecisionResponse, OrderActionsApi, OrderCancellationResponse } from './order-actions-api';
import { OrderAddLinesDialog, AddLinesSelection } from './order-add-lines-dialog';
import { OrderAmendMenu } from './order-amend-menu';
import {
  AmendmentConfirmationChannel,
  OrderAmendmentConfirmDialog,
} from './order-amendment-confirm-dialog';
import {
  AmendmentResponse,
  BuiltAmendmentCommandType,
  amendmentCommandLabel,
} from './order-amendments';
import { AmendmentAddLine, OrderAmendmentsApi } from './order-amendments-api';
import { OrderCashTenderedDialog } from './order-cash-tendered-dialog';
import { AddressSubmission, OrderChangeAddressDialog } from './order-change-address-dialog';
import { ContactSubmission, OrderChangeContactDialog } from './order-change-contact-dialog';
import { OrderChangePaymentMethodDialog } from './order-change-payment-method-dialog';
import { QuantitySubmission, OrderChangeQuantityDialog } from './order-change-quantity-dialog';
import { OrderChangeTimeDialog } from './order-change-time-dialog';
import {
  OrderAddressReveal,
  OrderApprovalDecision,
  OrderDeliveryResponse,
  OrderDetailResponse,
  OrderLine,
  OrderLineCommentPreset,
  OrderTimelineEntry,
  RevisionResponse,
} from './order-detail';
import { OrderDeliveryApi } from './order-delivery-api';
import { describeApiError, mutationErrorNotice } from './order-errors';
import { OrderFiscalPanel } from './order-fiscal-panel';
import { OrderHandoverPanel } from './order-handover-panel';
import { orderLifecycleSteps } from './order-lifecycle-steps';
import { MoneyReconciliation, reconcileMoney } from './order-money';
import { NewOrderApi } from './new-order/new-order-api';
import { OrderNoteDialog } from './order-note-dialog';
import { ExternalBookingSubmission, ExternalCourierDialog } from './external-courier-dialog';
import {
  outcomeKindLabel,
  outcomeSystemCategoryLabel,
  stockDispositionLabel,
  liabilityPartyLabel,
  customerRefundLabel,
  deliveryCancellationOutcomeText,
  deliveryExceptionReasonLabel,
} from './order-outcome-labels';
import { OrderOutcomeReasonDialog, OutcomeReasonSubmission } from './order-outcome-reason-dialog';
import { OrderPaymentPanel } from './order-payment-panel';
import { OrderPosExportApi, OrderPosExportView, PosExportPushResult } from './order-pos-export-api';
import { POS_EXPORT_STATE_LABEL_KEYS, posExportReachedTheTill } from './order-pos-export-labels';
import {
  OrderRejectReasonDialog,
  OrderRejectSubmission,
  RejectReasonOption,
} from './order-reject-reason-dialog';
import { RejectReasonsApi } from './order-reject-reasons-api';
import { OrderRevealApi } from './order-reveal-api';
import { OrderSeverity, computeOrderSeverity, formatSeverityCaption } from './order-severity';
import { orderStatusLabel } from './order-status';

/** See `order-queue.ts`'s identical constant for why this is a fixed zone, not the browser's. */
const PLACEHOLDER_TIME_ZONE: TimeZone = 'Asia/Tashkent';

/**
 * Fixed, English, machine-facing purpose strings for the ADR 0029 reveal
 * audit trail — not translated, the same reason `ApiError`'s message and
 * `advanceReasonCode` are not: these are read by whoever reviews the audit
 * log, not by the operator, and a purpose that changes with the UI locale
 * would fragment that log by language for no reason.
 */
const REVEAL_PURPOSE = {
  phoneCall: 'Operations console: call the customer',
  phoneCopy: 'Operations console: copy the phone number',
  address: 'Operations console: view the delivery address',
  lineNote: 'Operations console: view a line note',
  // Wave 10 (rows 1.2c/2.1d): the amendment dialogs' own reveals, distinct
  // from the read-only ones above for the identical reason phoneCall and
  // phoneCopy are distinct — the audit log should say the reveal preceded an
  // edit, not merely a look.
  contactAmend: 'Operations console: amend recipient contact',
  addressAmend: 'Operations console: amend delivery address',
} as const;

/**
 * `DispatchApi.assign`/`unassign`'s own reason codes from this pane, distinct
 * from the kitchen board's `OPERATIONS_KDS_ASSIGN` and the dispatch board's
 * own manual-assign reason — an auditor can tell the three surfaces apart.
 */
const ORDER_DETAIL_ASSIGN_REASON = 'OPERATIONS_ORDER_DETAIL_ASSIGN';
const ORDER_DETAIL_UNASSIGN_REASON = 'OPERATIONS_ORDER_DETAIL_UNASSIGN';

/** `DispatchApi.cancelShipment`'s own reason code from this pane (gap map row 1.2g) — distinct from the dispatch board's own. */
const ORDER_DETAIL_SHIPMENT_CANCEL_REASON = 'OPERATIONS_ORDER_DETAIL_SHIPMENT_CANCEL';

/** A shipment past these statuses is already settled — cancelling it again is refused server-side, so the action is not offered. */
const SHIPMENT_CANCEL_TERMINAL_STATUSES: ReadonlySet<string> = new Set(['DELIVERED', 'CANCELLED']);

/** Which reason dialog is open, if any. */
type DialogKind =
  | 'reject'
  | 'cancel'
  | 'complete'
  | 'override'
  | 'amendMenu'
  | 'kitchenNote'
  | 'courierNote'
  | 'internalNote'
  | 'cashTendered'
  | 'externalCourier'
  // ---------------------------------------------------- wave 10 financial commands
  | 'addLines'
  | 'changeQuantity'
  | 'changePaymentMethod'
  | 'changeAddress'
  | 'changeTime'
  | 'changeContact'
  | 'amendConfirm'
  | 'shipmentCancel';

/**
 * The order detail — `docs/operations-spec/orders.md` §3, docked beside the
 * queue (`orders-page.ts` explains why it is a route and not a modal).
 *
 * **What this wave builds, and what it does not.** The two-column desktop
 * layout §3.2 draws is the *full-page* screen; this application docks the
 * detail in a fixed-width column beside the queue instead (`orders-page.css`),
 * so every section here stacks in one column rather than two. Content-wise:
 * the lines table, the money panel with its §1.3 reconciliation guard, the
 * customer and address panels behind their ADR 0029 reveal calls, and — as of
 * wave P10 — the §3.6 «Комментарии» block and its amendment history are
 * built. Интеграции (§3.11) still needs a table that does not exist yet
 * (§11) and is not here; Оплата and Фискализация (§3.9, row `1.2l`, wave
 * P12) are — {@link OrderPaymentPanel} and {@link OrderFiscalPanel}, each
 * reading its own endpoint rather than a field this record would otherwise
 * have to grow. As of wave P11, all three
 * timeline lanes render: commercial (`GET .../timeline`'s `transitions`),
 * production (`kitchen.ticket_events`, joined by order id for the first time
 * outside a test) and delivery (`fulfillment.shipments`' own custody
 * timestamps, joined the same way) — plus the Money panel's two Доставка
 * rows, the assign/unassign control against `ManualDispatchService`, and the
 * losing side of an approval decision the commercial lane's own transitions
 * cannot show.
 */
@Component({
  selector: 'q-order-detail-pane',
  imports: [
    TPipe,
    OrderOutcomeReasonDialog,
    OrderAmendMenu,
    OrderNoteDialog,
    OrderCashTenderedDialog,
    OrderRejectReasonDialog,
    OrderHandoverPanel,
    ExternalCourierDialog,
    ConfirmDialog,
    OrderPaymentPanel,
    OrderFiscalPanel,
    Steps,
    Timeline,
    Combobox,
    // ---------------------------------------------------- wave 10 financial commands
    OrderAddLinesDialog,
    OrderChangeQuantityDialog,
    OrderChangePaymentMethodDialog,
    OrderChangeAddressDialog,
    OrderChangeTimeDialog,
    OrderChangeContactDialog,
    OrderAmendmentConfirmDialog,
  ],
  templateUrl: './order-detail-pane.html',
  styleUrl: './order-detail-pane.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class OrderDetailPane {
  private readonly api = inject(ApiClient);
  private readonly location = inject(CurrentLocation);
  private readonly actionsApi = inject(OrderActionsApi);
  private readonly amendmentsApi = inject(OrderAmendmentsApi);
  private readonly rejectReasonsApi = inject(RejectReasonsApi);
  private readonly referenceDataApi = inject(ReferenceDataApi);
  private readonly revealApi = inject(OrderRevealApi);
  private readonly latenessPolicyApi = inject(LatenessPolicyApi);
  private readonly deliveryApi = inject(OrderDeliveryApi);
  private readonly dispatchApi = inject(DispatchApi);
  private readonly couriersApi = inject(CouriersApi);
  private readonly kitchenApi = inject(KitchenApi);
  private readonly posExportApi = inject(OrderPosExportApi);
  private readonly newOrderApi = inject(NewOrderApi);
  private readonly channelsApi = inject(SalesChannelsApi);
  private readonly router = inject(Router);
  private readonly i18n = inject(I18n);

  /** Bound from the route parameter by `withComponentInputBinding()`. */
  readonly orderId = input.required<string>();

  protected readonly loading = signal(true);
  protected readonly order = signal<Versioned<OrderDetailResponse> | null>(null);
  protected readonly notFound = signal(false);
  protected readonly denied = signal(false);
  protected readonly lastError = signal<ApiError | null>(null);

  protected readonly timeline = signal<readonly OrderTimelineEntry[] | null>(null);
  protected readonly timelineError = signal(false);
  /** `GET .../timeline`'s own `decisions` (wave P11, row `1.2b`) — winner and losers alike; the losing ones are what the pane shows here that nothing else in this console does. */
  protected readonly decisions = signal<readonly OrderApprovalDecision[]>([]);

  /**
   * `GET .../orders/{orderId}/delivery` (wave P11, rows `1.2e`/`1.2n`/`2.1a`)
   * — `null` both before the fetch settles and for an order fulfilled some
   * other way; {@link deliveryError} is what tells the two apart when it
   * matters (the Money panel and the assign control render nothing either
   * way, but the delivery timeline lane needs to say which).
   */
  protected readonly delivery = signal<OrderDeliveryResponse | null>(null);
  protected readonly deliveryError = signal(false);

  /** The production lane's own read (wave P11, row `1.2b`) — `null` before it settles or on a non-critical failure, exactly like {@link timeline}. */
  protected readonly kitchenEvents = signal<KitchenEventsResponse | null>(null);
  protected readonly kitchenEventsError = signal(false);

  /**
   * §3.11 Интеграции's POS row (wave P42, row `1.2i`) — fetched at the pane
   * level, like {@link delivery}, because it feeds two places at once: its
   * own section below, and {@link amendBlockedReason}'s interlock on the
   * AMEND action in the header. `null` before it settles or on a non-critical
   * failure; `posExport()?.posCapable === false` is what suppresses the
   * section (and the interlock) entirely rather than rendering an empty one.
   */
  protected readonly posExport = signal<OrderPosExportView | null>(null);
  protected readonly posExportError = signal(false);
  protected readonly posExportPushOpen = signal(false);
  protected readonly posExportPushReason = signal('');
  protected readonly posExportPushSubmitting = signal(false);
  protected readonly posExportPushError = signal<string | null>(null);
  protected readonly posExportPushResult = signal<PosExportPushResult | null>(null);

  /** The order detail's own assign/unassign control (wave P11, row `1.2e`) — reuses `DispatchApi` exactly as the kitchen pass's own picker does. */
  protected readonly courierRoster = signal<readonly RosterEntryResponse[]>([]);
  protected readonly courierPickerOpen = signal(false);
  protected readonly assigningCourier = signal(false);

  /** «Вызвать курьера» — the Millenium pattern's own confirmation seam (gap map row 1.2f). */
  protected readonly externalPartners = signal<readonly ExternalPartnerResponse[]>([]);
  protected readonly externalQuote = signal<ExternalQuoteResponse | null>(null);
  protected readonly externalCourierBusy = signal(false);

  /** Cancel shipment (gap map row 1.2g) — `DispatchController.cancelShipment`'s first console caller on this pane. */
  protected readonly shipmentCancelBusy = signal(false);

  /**
   * `q-timeline`'s own shape, row `X.26` — the same idea as the staff
   * activity log's event list, so the same component: a gap notice stays
   * its own row (§3.10's "hiding it hides a bug"), computed exactly as
   * before via {@link missingSequenceBefore}.
   *
   * **Wave P09.** `actor` used to be omitted here on the premise that
   * `OrderTimelineEntry.actorType` — a bare wire tag with no resolvable name
   * or subject behind it, unlike an audit event's `actorDisplay`/
   * `actorSubject` — would render nothing but the same fallback dash on
   * every row. That premise undersold `q-actor-chip`: even with no name it
   * still marks *which kind* of actor moved the order (a customer, an
   * operator, the system itself), which is exactly the fact a raw
   * `reasonCode` next to it cannot supply on its own.
   */
  protected readonly commercialTimelineEntries = computed<readonly TimelineEntry[] | null>(() => {
    const entries = this.timeline();
    if (!entries) {
      return null;
    }
    return entries.map((entry, index) => ({
      id: String(entry.sequence),
      timestamp: this.formatOccurredAt(entry.occurredAt),
      actor: { kind: entry.actorType, displayName: null, subject: null },
      title: `${this.statusLabel(entry.fromStatus)} → ${this.statusLabel(entry.toStatus)}`,
      detail: entry.reasonCode
        ? `${this.triggerLabel(entry.trigger)} · ${entry.reasonCode}`
        : this.triggerLabel(entry.trigger),
      gapBefore: this.gapLabel(entries, index),
      selectable: false,
    }));
  });

  /**
   * The order lifecycle rail (row `X.33`) — over the §3.10 timeline this
   * pane already fetches, no new endpoint. `null` while the order or the
   * timeline has not settled; the rail simply does not render then, the same
   * "greyed, never silently dropped" rule this section already follows for
   * the production/delivery lanes, applied here to render nothing rather
   * than a misleading first position.
   */
  protected readonly lifecycleSteps = computed<readonly StepItem[] | null>(() => {
    const detail = this.order();
    const entries = this.timeline();
    if (!detail || !entries) {
      return null;
    }
    return orderLifecycleSteps(detail.value.summary.status, entries, (status) =>
      this.statusLabel(status),
    );
  });

  /**
   * The production lane (wave P11, row `1.2b`) — filled/hollow/muted marks
   * via `q-steps`, the same primitive {@link lifecycleSteps} already renders
   * the commercial lifecycle rail with. `null` before {@link kitchenEvents}
   * settles; `[]` is a real answer (the order never opened a ticket, or
   * opened one that is still buffered).
   */
  protected readonly productionTimelineSteps = computed<readonly StepItem[] | null>(() => {
    const events = this.kitchenEvents();
    if (!events) {
      return null;
    }
    return kitchenLifecycleSteps(
      events.ticketStatus ?? null,
      events.events,
      (stage) => this.kitchenStageLabel(stage),
      (from, to) => this.formatElapsed(from, to),
    );
  });

  /** The delivery lane (wave P11, row `1.2b`) — `null` before {@link delivery} settles. */
  protected readonly deliveryTimelineSteps = computed<readonly StepItem[] | null>(() => {
    const delivery = this.delivery();
    if (!delivery && !this.deliveryError()) {
      return null;
    }
    return deliveryLifecycleSteps(
      delivery?.shipment ?? null,
      (stage) => this.deliveryStageLabel(stage),
      (from, to) => this.formatElapsed(from, to),
    );
  });

  /** The margin: the customer's fee minus what the provider billed — negative whenever `fulfillment.delivery_cost_subsidies` recorded a gap, because that row is only ever written for a loss. */
  protected deliveryMarginMinor(delivery: OrderDeliveryResponse): number | null {
    return delivery.providerCostMinor == null
      ? null
      : delivery.customerDeliveryFeeMinor - delivery.providerCostMinor;
  }

  /** §4.1/§4.3: STALE_VERSION, a lost approval race, and a refused transition all surface here. */
  protected readonly notice = signal<string | null>(null);
  protected readonly busy = signal(false);
  protected readonly dialog = signal<DialogKind | null>(null);
  /** Fetched before the reject dialog opens — see {@link onActionClick}'s REJECT case. */
  protected readonly rejectReasons = signal<readonly RejectReasonOption[]>([]);
  /**
   * Fetched before the cancel or completion dialog opens (§4.5/§4.6, wave
   * P09) — whichever one is currently relevant; the two dialogs never open
   * at once, so one signal is enough.
   */
  protected readonly outcomeReasons = signal<readonly ReasonResponse[]>([]);
  /**
   * The status an `OVERRIDE` click asked to restore — captured from the
   * clicked `actions[]` entry at open time (§0.2/§11.3, wave 9 row `1.1h`),
   * since {@link OutcomeReasonSubmission} carries only the reason and this
   * dialog never lets the operator choose a target of its own; see {@link
   * onActionClick}'s `OVERRIDE` case.
   */
  protected readonly pendingOverrideTarget = signal<string | null>(null);
  protected readonly headerOverflowOpen = signal(false);
  private readonly decisionIds = new DecisionIdRegistry();

  /**
   * `GET .../revisions` (§3.9, ADR 0039, row `1.2p`) — fetched on demand, not
   * on load: an order with no amendments has one revision nobody needs to
   * see, and the pane's own load already makes two calls (the order, the
   * timeline). `null` before the operator has asked; `[]` would be
   * indistinguishable from "still loading".
   */
  protected readonly revisions = signal<readonly RevisionResponse[] | null>(null);
  protected readonly revisionsOpen = signal(false);
  protected readonly revisionsLoading = signal(false);
  protected readonly revisionsError = signal(false);

  /**
   * `GET .../amendments` (§3.6/§3.10, ADR 0039 wave P10, `:713`) — the
   * amendment history view. Fetched on demand for the same reason revisions
   * are: an order nobody has amended has an empty history nobody needs to
   * see. It is also the only read path for a courier or internal note's own
   * text (ADR 0113: neither has an `ordering.orders` column the way
   * `kitchenNote` does).
   */
  protected readonly amendmentHistory = signal<readonly AmendmentResponse[] | null>(null);
  protected readonly amendmentHistoryOpen = signal(false);
  protected readonly amendmentHistoryLoading = signal(false);
  protected readonly amendmentHistoryError = signal(false);

  /**
   * ADR 0039: change-due short of the total after a later amendment is an
   * acknowledgeable notice, never a refusal — the customer can hand over
   * more. Cleared by the operator, not by the next order load, so it survives
   * exactly as long as it takes to be read.
   */
  protected readonly cashTenderedWarning = signal(false);

  // ---------------------------------------------------- wave 10 financial commands (rows 1.2c/2.1d)

  /** `q-order-add-lines-dialog`'s own search results and loading flag — fully controlled, see that component's own doc. */
  protected readonly addLinesOptions = signal<readonly ComboboxOption[]>([]);
  protected readonly addLinesSearching = signal(false);

  /** The operator channel's enabled payment methods, read fresh each time `CHANGE_PAYMENT_METHOD` opens — `new-order-page.ts`'s own `paymentMethods` reads the identical matrix the identical way. */
  protected readonly paymentMethods = signal<readonly string[]>(['CASH']);

  /**
   * The amendment `q-order-amendment-confirm-dialog` is currently open for —
   * set either by a repricing command's own propose response ({@link
   * submitRepricingAmendment}) or by the RESOLVE row action on an already
   * `PRICED` history entry. `amendmentVersion` is the amendment row's own
   * optimistic version, the exact field `POST .../confirmation`'s `If-Match`
   * needs — never the order's version, which the confirmation endpoint has
   * no opinion about.
   */
  protected readonly pendingAmendment = signal<{
    readonly amendmentId: string;
    readonly amendmentVersion: number;
    readonly deltaMinor: number;
    readonly requiresApproval: boolean;
  } | null>(null);

  protected readonly revealedPhone = signal<string | null>(null);
  protected readonly revealingPhone = signal(false);
  protected readonly revealedAddress = signal<OrderAddressReveal | null>(null);
  protected readonly revealingAddress = signal(false);
  protected readonly revealedNotes = signal<ReadonlyMap<string, string | null>>(new Map());
  protected readonly revealingNoteFor = signal<string | null>(null);

  /** The resolved `ordering.lateness` policy (wave P06) — fetched once per order load in {@link load}. */
  private latenessPolicy: LatenessPolicy = PLATFORM_DEFAULT_LATENESS_POLICY;

  constructor() {
    // `orderId` is a signal input: navigating from one order to another under
    // the same `:orderId` route config reuses this component (Angular's
    // default `RouteReuseStrategy`) rather than recreating it, so a plain
    // `ngOnInit` would only ever see the first order. This effect is what
    // notices the second.
    effect(() => {
      const id = this.orderId();
      void this.load(id);
    });
  }

  private async load(orderId: string): Promise<void> {
    this.loading.set(true);
    this.lastError.set(null);
    this.notFound.set(false);
    this.timeline.set(null);
    this.timelineError.set(false);
    this.decisions.set([]);
    this.delivery.set(null);
    this.deliveryError.set(false);
    this.kitchenEvents.set(null);
    this.kitchenEventsError.set(false);
    this.posExport.set(null);
    this.posExportError.set(false);
    this.posExportPushOpen.set(false);
    this.posExportPushReason.set('');
    this.posExportPushError.set(null);
    this.posExportPushResult.set(null);
    this.courierPickerOpen.set(false);
    this.revealedPhone.set(null);
    this.revealedAddress.set(null);
    this.revealedNotes.set(new Map());
    this.revisions.set(null);
    this.revisionsOpen.set(false);
    this.revisionsError.set(false);
    this.amendmentHistory.set(null);
    this.amendmentHistoryOpen.set(false);
    this.amendmentHistoryError.set(false);
    this.addLinesOptions.set([]);
    this.addLinesSearching.set(false);
    this.paymentMethods.set(['CASH']);
    this.pendingAmendment.set(null);

    await this.location.ensureLoaded();
    const scope = this.location.scope();
    if (!scope) {
      this.denied.set(this.location.denied());
      this.order.set(null);
      this.loading.set(false);
      return;
    }
    this.denied.set(false);
    void this.loadLatenessPolicy(scope);

    try {
      const result = await firstValueFrom(
        this.api.get<OrderDetailResponse>(operationsPaths.order(scope, orderId)),
      );
      this.order.set(result);
      void this.loadTimeline(orderId);
      void this.loadDecisions(orderId);
      void this.loadDelivery(orderId);
      void this.loadKitchenEvents(orderId);
      void this.loadPosExport(orderId);
    } catch (error) {
      if (error instanceof ApiError) {
        if (error.code === ApiErrorCode.RESOURCE_NOT_FOUND) {
          this.notFound.set(true);
          this.order.set(null);
        } else {
          this.lastError.set(error);
        }
      } else {
        throw error;
      }
    } finally {
      this.loading.set(false);
    }
  }

  private async loadTimeline(orderId: string): Promise<void> {
    const scope = this.location.scope();
    if (!scope) {
      return;
    }
    try {
      const result = await firstValueFrom(
        this.api.get<OrderTimelineEntry[]>(operationsPaths.orderTimeline(scope, orderId)),
      );
      this.timeline.set(result.value ?? []);
    } catch {
      // Non-critical panel: the order itself loaded, the timeline just did
      // not. §2.11's "previously loaded rows stay" applies here too — the
      // rest of the detail is still shown.
      this.timelineError.set(true);
    }
  }

  /** The losing side of a decision (wave P11, row 1.2b) — a separate call from {@link loadTimeline}; see `operationsPaths.orderDecisions`'s own doc for why. */
  private async loadDecisions(orderId: string): Promise<void> {
    const scope = this.location.scope();
    if (!scope) {
      return;
    }
    try {
      const result = await firstValueFrom(
        this.api.get<OrderApprovalDecision[]>(operationsPaths.orderDecisions(scope, orderId)),
      );
      this.decisions.set(result.value ?? []);
    } catch {
      // Non-critical panel, exactly like loadTimeline: the losing-decisions
      // list simply stays empty rather than failing the whole pane.
    }
  }

  /** The order-to-fulfilment seam (wave P11, rows `1.2e`/`1.2n`/`2.1a`) — fire-and-forget, exactly like {@link loadTimeline}. */
  private async loadDelivery(orderId: string): Promise<void> {
    const scope = this.location.scope();
    if (!scope) {
      return;
    }
    try {
      this.delivery.set(await this.deliveryApi.delivery(scope, orderId));
    } catch {
      this.deliveryError.set(true);
    }
  }

  /** The production timeline lane's own read (wave P11, row `1.2b`) — fire-and-forget, exactly like {@link loadTimeline}. */
  private async loadKitchenEvents(orderId: string): Promise<void> {
    const scope = this.location.scope();
    if (!scope) {
      return;
    }
    try {
      this.kitchenEvents.set(await this.kitchenApi.eventsForOrder(scope, orderId));
    } catch {
      this.kitchenEventsError.set(true);
    }
  }

  /** §3.11 Интеграции's POS row (wave P42, row `1.2i`) — fire-and-forget, exactly like {@link loadTimeline}. */
  private async loadPosExport(orderId: string): Promise<void> {
    const scope = this.location.scope();
    if (!scope) {
      return;
    }
    try {
      this.posExport.set(await this.posExportApi.forOrder(scope, orderId));
    } catch {
      this.posExportError.set(true);
    }
  }

  /**
   * The resolved `ordering.lateness` policy (wave P06), fire-and-forget
   * exactly like {@link loadTimeline}: {@link headerSeverity} already falls
   * back to {@link PLATFORM_DEFAULT_LATENESS_POLICY} while this is in
   * flight, so the header renders immediately and refines once this settles.
   */
  private async loadLatenessPolicy(scope: LocationScope): Promise<void> {
    this.latenessPolicy = await this.latenessPolicyApi.resolve(scope);
  }

  protected manualRetry(): void {
    void this.load(this.orderId());
  }

  protected statusLabel(status: string): string {
    return orderStatusLabel(status, (key) => this.i18n.t(key));
  }

  protected formatMoneyMinor(amountMinor: number, currency: string): string {
    return formatMoney({ amountMinor, currency }, this.i18n.locale(), { withUnit: true });
  }

  /** `q-order-amendment-confirm-dialog`'s own `locale` input — the template has no other access to the private `i18n` field. */
  protected locale(): Locale {
    return this.i18n.locale();
  }

  protected formatOccurredAt(occurredAt: string): string {
    return formatDateTime(new Date(occurredAt), PLACEHOLDER_TIME_ZONE);
  }

  protected errorMessage(error: ApiError): string {
    return describeApiError(error, (key, values) => this.i18n.t(key, values));
  }

  protected dismissNotice(): void {
    this.notice.set(null);
  }

  /** For `q-order-handover-panel`'s `[scope]` input — the template cannot reach `location` directly. */
  protected currentScope(): LocationScope | null {
    return this.location.scope();
  }

  // ------------------------------------------------------------ §3.11 Интеграции (POS export, wave P42, row 1.2i)

  protected posExportStateLabel(): string | null {
    const state = this.posExport()?.export?.state;
    return state ? this.i18n.t(POS_EXPORT_STATE_LABEL_KEYS[state]) : null;
  }

  /**
   * The &sect;3.11 "a failed export is not an order failure" reassurance —
   * the whole reason the Traps note in gap map row `1.2i` exists. Shown for
   * every state except the two where the till is confirmed to actually hold
   * the ticket ({@link posExportReachedTheTill}); never omitted just because
   * a state also has its own error text, since the reassurance and the
   * detail answer different questions.
   */
  protected posExportShowsReassurance(): boolean {
    const state = this.posExport()?.export?.state;
    return state !== undefined && !posExportReachedTheTill(state);
  }

  /**
   * Gap-map row 1.2i's fix path: true exactly when the read named a
   * currently-unmapped variant or modifier to pre-select on the ADR 0012
   * mapping screen -- never merely because {@link
   * OrderPosExportApi.PosExportView.lastErrorCode} names a mapping gap,
   * since the live re-check behind it may have found nothing left unmapped.
   */
  protected posExportHasMappingDeepLink(): boolean {
    const exportView = this.posExport()?.export;
    return (
      exportView !== undefined &&
      exportView !== null &&
      exportView.unmappedEntityType !== null &&
      exportView.unmappedHorecaosEntityId !== null
    );
  }

  /**
   * Opens the ADR 0012 mapping screen with the offending item already chosen
   * -- see `MappingPane.focusHorecaosId`. Also carries `bindingId`, when the
   * server resolved one, so `catalog-import-page.ts` lands on the same
   * binding the id was checked against rather than defaulting to whichever
   * POS binding happens to be first in the tenant's own list -- the wrong
   * one for any tenant with more than one.
   */
  protected openPosExportMapping(): void {
    const exportView = this.posExport()?.export;
    if (!exportView || exportView.unmappedEntityType === null || exportView.unmappedHorecaosEntityId === null) {
      return;
    }
    void this.router.navigate(['/catalog/import'], {
      queryParams: {
        entityType: exportView.unmappedEntityType,
        focusHorecaosId: exportView.unmappedHorecaosEntityId,
        ...(exportView.unmappedBindingId === null ? {} : { bindingId: exportView.unmappedBindingId }),
      },
    });
  }

  protected openPosExportPush(): void {
    this.posExportPushOpen.set(true);
    this.posExportPushReason.set('');
    this.posExportPushError.set(null);
    this.posExportPushResult.set(null);
  }

  protected cancelPosExportPush(): void {
    this.posExportPushOpen.set(false);
  }

  protected setPosExportPushReason(value: string): void {
    this.posExportPushReason.set(value);
  }

  protected canSubmitPosExportPush(): boolean {
    return !this.posExportPushSubmitting() && this.posExportPushReason().trim().length > 0;
  }

  protected async submitPosExportPush(): Promise<void> {
    if (!this.canSubmitPosExportPush()) {
      return;
    }
    const scope = this.location.scope();
    const detail = this.order();
    if (!scope || !detail) {
      return;
    }
    this.posExportPushSubmitting.set(true);
    this.posExportPushError.set(null);
    this.posExportPushResult.set(null);
    try {
      const result = await this.posExportApi.push(
        scope,
        detail.value.summary.orderId,
        this.posExportPushReason().trim(),
      );
      this.posExportPushResult.set(result);
      this.posExportPushOpen.set(false);
      await this.loadPosExport(detail.value.summary.orderId);
    } catch (error) {
      if (error instanceof ApiError) {
        this.posExportPushError.set(this.errorMessage(error));
      } else {
        this.posExportPushError.set(this.i18n.t('error.unknown.noReference'));
      }
    } finally {
      this.posExportPushSubmitting.set(false);
    }
  }

  /**
   * A push result earns its own banner only for `EXPORT_NOT_SENDABLE` — the
   * ordinary "pressed it while it was already sent/landed/waiting on a
   * decision" answer §4.8 describes, and the one outcome the refreshed state
   * line below does not already say plainly on its own. Every other outcome
   * (a successful send, a provider rejection, an uncertain result) is fully
   * covered by {@link loadPosExport}'s own reload updating the state and
   * error text this section already renders.
   */
  protected posExportPushResultLabel(): string | null {
    const result = this.posExportPushResult();
    return result?.status === 'REJECTED' && result.errorCode === 'EXPORT_NOT_SENDABLE'
      ? this.i18n.t('orders.detail.posExport.action.push.notSendable')
      : null;
  }

  /**
   * The &sect;3.11 amendment interlock (ADR 0039, gap map row `1.2i`, wave
   * P42): non-null exactly while `posExport()` names an export that {@link
   * PosExportView#permitsAmendment} refuses, mirroring &sect;4.2's rule for a
   * temporarily unavailable action — rendered as a disabled control with the
   * reason attached, never silently omitted. `null` while the read has not
   * settled or the tenant has no POS binding at all, so a branch with no
   * till is never told to wait on one.
   */
  protected amendBlockedReason(): string | null {
    const exportView = this.posExport()?.export;
    if (!exportView || exportView.permitsAmendment) {
      return null;
    }
    return this.i18n.t('orders.detail.amend.blockedByPosExport');
  }

  // ------------------------------------------------------------ header severity

  protected headerSeverity(): OrderSeverity | null {
    const detail = this.order();
    if (!detail) {
      return null;
    }
    const summary = detail.value.summary;
    return computeOrderSeverity(
      {
        status: summary.status,
        createdAt: new Date(summary.createdAt),
        approvalDeadlineAt: summary.approvalDeadlineAt
          ? new Date(summary.approvalDeadlineAt)
          : null,
        fulfillmentMode: summary.fulfillmentMode,
        promisedAt: summary.promisedAt ? new Date(summary.promisedAt) : null,
        // Always null on this read today — the detail endpoint's own
        // `OrderSummaryResponse.of(OrderRow, ...)` overload projects no
        // process state (see that record's own doc). Read the real field
        // rather than hardcoding false, so this picks up a real value for
        // free the day that overload does project one.
        hasBlockedProcess: summary.processAttention === 'MANUAL_ACTION_REQUIRED',
      },
      new Date(),
      this.latenessPolicy,
    );
  }

  protected severityCaption(severity: OrderSeverity): string | null {
    return formatSeverityCaption(severity, (key, values) => this.i18n.t(key, values));
  }

  // ------------------------------------------------------------ §3.11/§4.3 actions

  /**
   * The server offers `ADVANCE`→`COMPLETED` and `COMPLETE` together whenever
   * either is legal (`OrderActionsPolicy`'s own doc explains why: a client
   * built before wave P09 — `order-queue.ts` — still works against the
   * generic entry). This pane prefers `COMPLETE`, which lets it name the
   * fulfilment-mode-appropriate reason instead of always booking
   * `DELIVERED_OWN_COURIER` (row `1.2j`), so the redundant `ADVANCE` entry is
   * filtered out here rather than rendered as a second, competing button.
   */
  private visibleActions(): readonly OrderActionResponse[] {
    const actions = this.order()?.value.summary.actions ?? [];
    const hasComplete = actions.some((action) => action.action === 'COMPLETE');
    return hasComplete
      ? actions.filter(
          (action) => !(action.action === 'ADVANCE' && action.targetStatus === 'COMPLETED'),
        )
      : actions;
  }

  protected primaryAction(): OrderActionResponse | null {
    return this.visibleActions()[0] ?? null;
  }

  protected overflowActions(): readonly OrderActionResponse[] {
    return this.visibleActions().slice(1);
  }

  protected actionLabel(action: OrderActionResponse): string {
    const mode = this.order()?.value.summary.fulfillmentMode ?? null;
    return actionLabel(
      action,
      mode,
      (key, values) => this.i18n.t(key, values),
      (status) => this.statusLabel(status),
    );
  }

  protected toggleHeaderOverflow(): void {
    this.headerOverflowOpen.update((open) => !open);
  }

  protected onActionClick(action: OrderActionResponse): void {
    this.headerOverflowOpen.set(false);
    const detail = this.order();
    const scope = this.location.scope();
    if (!detail || !scope) {
      return;
    }
    const orderId = detail.value.summary.orderId;
    const version = detail.value.summary.version ?? 0;

    switch (action.action) {
      case 'APPROVE':
        void this.submitDecision(
          this.actionsApi.approve(scope, orderId, this.decisionIds.idFor(orderId)),
        );
        return;
      case 'REJECT':
        void this.openRejectDialog();
        return;
      case 'CANCEL':
        void this.openCancelDialog();
        return;
      case 'COMPLETE':
        void this.startCompletion();
        return;
      case 'ADVANCE':
        if (action.targetStatus) {
          void this.submitStateMutation(
            this.actionsApi.advance(scope, orderId, action.targetStatus, version),
          );
        }
        return;
      case 'OVERRIDE':
        // §0.2/§11.3, wave 9 row 1.1h: the clicked entry already names the one
        // compensating edge it offers (OrderActionsPolicy never emits more
        // than one OVERRIDE entry per status today) — the dialog only asks
        // for the mandatory registry reason, never a target of its own.
        if (action.targetStatus) {
          void this.openOverrideDialog(action.targetStatus);
        }
        return;
      case 'AMEND':
        // orders.md §4.4: opens the amendment submenu. Wave P10 is the console
        // that can finally render and click this — see
        // `OrderActionsPolicy.AMEND_EMISSION_ENABLED`'s own doc (ADR 0105).
        // The §3.11 interlock (wave P42) disables this button in the
        // template while `amendBlockedReason()` is set; this guard is the
        // same rule applied a second time, in case a click still reaches
        // here (a stale render, a synthetic event) rather than relying on
        // `disabled` alone.
        if (this.amendBlockedReason()) {
          return;
        }
        this.dialog.set('amendMenu');
        return;
      default:
      // An action code this client does not recognise yet (§4.2: still rendered, nothing to invoke).
    }
  }

  // ------------------------------------------------------------ §3.6 Комментарии (ADR 0039, wave P10)

  /**
   * Routes an `OrderAmendMenu` selection to the specific dialog each of the
   * eleven built commands needs — or, for the callback flag, straight to
   * {@link toggleCallback}, which has no dialog because §4.4's own table
   * marks its "Consequences the dialog must state before confirm" column
   * "none". The six wave-10 financial commands (rows `1.2c`/`2.1d`) each
   * open their own dialog below, three of them ({@link openChangeContactDialog},
   * {@link openChangeAddressDialog}) after an ADR 0029 reveal the command's
   * own dialog needs seeded.
   */
  protected onAmendMenuSelect(type: BuiltAmendmentCommandType): void {
    switch (type) {
      case 'SET_KITCHEN_NOTE':
        this.openKitchenNoteDialog();
        return;
      case 'SET_COURIER_NOTE':
        this.openCourierNoteDialog();
        return;
      case 'SET_INTERNAL_NOTE':
        this.openInternalNoteDialog();
        return;
      case 'SET_CASH_TENDERED':
        this.openCashTenderedDialog();
        return;
      case 'SET_CALLBACK_REQUESTED':
        this.dialog.set(null);
        this.toggleCallback();
        return;
      case 'ADD_LINES':
        this.openAddLinesDialog();
        return;
      case 'CHANGE_LINE_QUANTITY':
        this.dialog.set('changeQuantity');
        return;
      case 'CHANGE_PAYMENT_METHOD':
        void this.openChangePaymentMethodDialog();
        return;
      case 'CHANGE_DELIVERY_ADDRESS':
        void this.openChangeAddressDialog();
        return;
      case 'CHANGE_FULFILLMENT_TIME':
        this.dialog.set('changeTime');
        return;
      case 'CHANGE_CONTACT':
        void this.openChangeContactDialog();
        return;
    }
  }

  protected openKitchenNoteDialog(): void {
    this.dialog.set('kitchenNote');
  }

  protected openCourierNoteDialog(): void {
    this.dialog.set('courierNote');
  }

  protected openInternalNoteDialog(): void {
    this.dialog.set('internalNote');
  }

  protected openCashTenderedDialog(): void {
    this.dialog.set('cashTendered');
  }

  protected onKitchenNoteConfirm(note: string): void {
    const detail = this.order();
    const scope = this.location.scope();
    if (!detail || !scope) {
      return;
    }
    void this.submitAmendment(
      this.amendmentsApi.setKitchenNote(
        scope,
        detail.value.summary.orderId,
        detail.value.summary.version ?? 0,
        note,
      ),
    ).finally(() => this.dialog.set(null));
  }

  protected onCourierNoteConfirm(note: string): void {
    const detail = this.order();
    const scope = this.location.scope();
    if (!detail || !scope) {
      return;
    }
    void this.submitAmendment(
      this.amendmentsApi.setCourierNote(
        scope,
        detail.value.summary.orderId,
        detail.value.summary.version ?? 0,
        note,
      ),
    ).finally(() => this.dialog.set(null));
  }

  protected onInternalNoteConfirm(note: string): void {
    const detail = this.order();
    const scope = this.location.scope();
    if (!detail || !scope) {
      return;
    }
    void this.submitAmendment(
      this.amendmentsApi.setInternalNote(
        scope,
        detail.value.summary.orderId,
        detail.value.summary.version ?? 0,
        note,
      ),
    ).finally(() => this.dialog.set(null));
  }

  protected onCashTenderedConfirm(amountMinor: number): void {
    const detail = this.order();
    const scope = this.location.scope();
    if (!detail || !scope) {
      return;
    }
    void this.submitAmendment(
      this.amendmentsApi.setCashTendered(
        scope,
        detail.value.summary.orderId,
        detail.value.summary.version ?? 0,
        amountMinor,
      ),
    ).finally(() => this.dialog.set(null));
  }

  /**
   * `Требуется звонок` toggles rather than opening a dialog — §4.4's own
   * table has no "confirm" for this command. Raising it and clearing it are
   * the same command, `requested` flipped, exactly as ADR 0039 states.
   */
  protected toggleCallback(): void {
    const detail = this.order();
    const scope = this.location.scope();
    if (!detail || !scope) {
      return;
    }
    void this.submitAmendment(
      this.amendmentsApi.setCallbackRequested(
        scope,
        detail.value.summary.orderId,
        detail.value.summary.version ?? 0,
        !detail.value.callbackRequested,
      ),
    );
  }

  protected dismissCashTenderedWarning(): void {
    this.cashTenderedWarning.set(false);
  }

  /**
   * Every built amendment command's own submit path: apply, reload the order
   * so the field that just changed (or, for the two ADR 0113 notes, nothing —
   * see {@link amendmentHistory}) renders its new value, and surface
   * `CASH_TENDERED_INSUFFICIENT` as the acknowledgeable notice §3.5/§4.4
   * describe rather than a refusal.
   */
  private async submitAmendment(request: Observable<AmendmentResponse>): Promise<void> {
    const orderId = this.order()?.value.summary.orderId;
    if (!orderId) {
      return;
    }
    this.busy.set(true);
    try {
      const result = await firstValueFrom(request);
      if (result.warnings.includes('CASH_TENDERED_INSUFFICIENT')) {
        this.cashTenderedWarning.set(true);
      }
      await this.load(orderId);
    } catch (error) {
      this.handleMutationError(orderId, error, false);
    } finally {
      this.busy.set(false);
    }
  }

  // ------------------------------------------------------------ wave 10 financial commands (rows 1.2c/2.1d)

  /** `catalog.md` §4.6's `locale` — the menu endpoint speaks `ru`/`uz`/`en`, `new-order-page.ts`'s own `menuLocale` maps `uz-Latn` the same way. */
  private menuLocale(): string {
    return this.i18n.locale() === 'uz-Latn' ? 'uz' : this.i18n.locale();
  }

  protected openAddLinesDialog(): void {
    this.addLinesOptions.set([]);
    this.dialog.set('addLines');
  }

  /**
   * `NewOrderApi.searchItems` — the identical call `new-order-page.ts`'s own
   * `onItemSearch` makes (catalog.md §4.6), reused rather than a second
   * client. `q-order-add-lines-dialog` is fully controlled: this is the
   * caller `search` output it documents.
   */
  protected async onAddLinesSearch(query: string): Promise<void> {
    const scope = this.location.scope();
    if (!scope) {
      return;
    }
    this.addLinesSearching.set(true);
    try {
      const page = await this.newOrderApi.searchItems(
        scope,
        firstPage(20),
        query,
        this.menuLocale(),
      );
      this.addLinesOptions.set(
        page.items.map((row) => ({
          id: row.variantId,
          label: row.productName ?? row.variantId,
          sublabel: row.available
            ? row.category
            : `${row.category ? row.category + ' · ' : ''}${this.i18n.t('orders.newOrder.menu.stopped')}`,
        })),
      );
    } catch (error) {
      this.addLinesOptions.set([]);
      this.noticeFromRevealError(error);
    } finally {
      this.addLinesSearching.set(false);
    }
  }

  /** `ADD_LINES` — increase only, no modifiers (the server's own refusal; see the dialog's doc). Reprices, so this always goes through {@link submitRepricingAmendment}. */
  protected onAddLinesConfirm(selections: readonly AddLinesSelection[]): void {
    const detail = this.order();
    const scope = this.location.scope();
    if (!detail || !scope) {
      return;
    }
    const lines: readonly AmendmentAddLine[] = selections.map((selection) => ({
      variantId: selection.variantId,
      quantity: selection.quantity,
      modifierOptionIds: [],
    }));
    void this.submitRepricingAmendment(
      this.amendmentsApi.addLines(
        scope,
        detail.value.summary.orderId,
        detail.value.summary.version ?? 0,
        lines,
      ),
    );
  }

  /** `CHANGE_LINE_QUANTITY` — increase only. Reprices, so this always goes through {@link submitRepricingAmendment}. */
  protected onChangeQuantityConfirm(submission: QuantitySubmission): void {
    const detail = this.order();
    const scope = this.location.scope();
    if (!detail || !scope) {
      return;
    }
    void this.submitRepricingAmendment(
      this.amendmentsApi.changeLineQuantity(
        scope,
        detail.value.summary.orderId,
        detail.value.summary.version ?? 0,
        submission.orderLineId,
        submission.quantity,
      ),
    );
  }

  /**
   * `CHANGE_PAYMENT_METHOD` — reads the order's own channel's matrix
   * (`SalesChannelsApi.matrices`), the identical read `new-order-page.ts`'s
   * own `loadPaymentMethods` performs, falling back to the cash-only default
   * {@link paymentMethods} already carries on a denied or empty read — the
   * same capability-model gap that method's own doc documents, so the
   * dialog still offers something rather than nothing.
   */
  protected async openChangePaymentMethodDialog(): Promise<void> {
    const detail = this.order();
    const scope = this.location.scope();
    const channelCode = detail?.value.summary.channelCode;
    if (detail && scope && channelCode) {
      try {
        const channels = await this.channelsApi.list(scope);
        const channel = channels.find((candidate) => candidate.code === channelCode);
        if (channel) {
          const matrices = await this.channelsApi.matrices(scope, channel.id);
          const enabled = Object.entries(matrices.paymentMethods)
            .filter(([, isEnabled]) => isEnabled)
            .map(([code]) => code)
            .sort();
          if (enabled.length > 0) {
            this.paymentMethods.set(enabled);
          }
        }
      } catch {
        // CHANNEL_READ gap `new-order-page.ts`'s own `loadPaymentMethods` already
        // documents — the cash-only default this signal already carries stands.
      }
    }
    this.dialog.set('changePaymentMethod');
  }

  /** `CHANGE_PAYMENT_METHOD` — `CASH` at either end never reprices, so this applies in the same call as the five original commands. */
  protected onChangePaymentMethodConfirm(paymentMethodCode: string): void {
    const detail = this.order();
    const scope = this.location.scope();
    if (!detail || !scope) {
      return;
    }
    void this.submitAmendment(
      this.amendmentsApi.changePaymentMethod(
        scope,
        detail.value.summary.orderId,
        detail.value.summary.version ?? 0,
        paymentMethodCode,
      ),
    ).finally(() => this.dialog.set(null));
  }

  /**
   * `CHANGE_DELIVERY_ADDRESS` — reveals the order's *current* address first,
   * through the dedicated `contactAmend`-sibling `addressAmend` ADR 0029
   * purpose (distinct from the read-only "view the delivery address" reveal
   * the address panel's own control uses, for the identical reason
   * `phoneCall`/`phoneCopy` are distinct), so the dialog opens pre-filled
   * rather than blank.
   */
  protected async openChangeAddressDialog(): Promise<void> {
    const detail = this.order();
    const scope = this.location.scope();
    if (!detail || !scope) {
      return;
    }
    this.revealingAddress.set(true);
    try {
      const result = await firstValueFrom(
        this.revealApi.revealAddress(
          scope,
          detail.value.summary.orderId,
          REVEAL_PURPOSE.addressAmend,
        ),
      );
      this.revealedAddress.set(result);
    } catch (error) {
      this.noticeFromRevealError(error);
    } finally {
      this.revealingAddress.set(false);
    }
    this.dialog.set('changeAddress');
  }

  /** `CHANGE_DELIVERY_ADDRESS` — can lower the total as well as raise it, so this always goes through {@link submitRepricingAmendment}. */
  protected onChangeAddressConfirm(submission: AddressSubmission): void {
    const detail = this.order();
    const scope = this.location.scope();
    if (!detail || !scope) {
      return;
    }
    void this.submitRepricingAmendment(
      this.amendmentsApi.changeDeliveryAddress(
        scope,
        detail.value.summary.orderId,
        detail.value.summary.version ?? 0,
        {
          line1: submission.line1,
          line2: submission.line2,
          city: submission.city,
          district: submission.district,
          postalCode: submission.postalCode,
          entrance: submission.entrance,
          floor: submission.floor,
          apartment: submission.apartment,
          landmark: submission.landmark,
          latitude: submission.latitude,
          longitude: submission.longitude,
          deliveryInstructions: submission.deliveryInstructions,
        },
        submission.recipientName,
        submission.recipientPhone,
      ),
    );
  }

  /** `CHANGE_FULFILLMENT_TIME` — a field update, never reprices, so this applies in the same call as the five original commands. */
  protected onChangeTimeConfirm(promisedAtIso: string): void {
    const detail = this.order();
    const scope = this.location.scope();
    if (!detail || !scope) {
      return;
    }
    void this.submitAmendment(
      this.amendmentsApi.changeFulfillmentTime(
        scope,
        detail.value.summary.orderId,
        detail.value.summary.version ?? 0,
        promisedAtIso,
      ),
    ).finally(() => this.dialog.set(null));
  }

  /**
   * `CHANGE_CONTACT` — reveals the order's current phone first (the
   * dedicated `contactAmend` purpose), so the dialog opens with a real
   * starting point rather than blank; the recipient name seeds from the
   * account's own display name, the closest fact this read has to it.
   */
  protected async openChangeContactDialog(): Promise<void> {
    const detail = this.order();
    const scope = this.location.scope();
    if (!detail || !scope) {
      return;
    }
    this.revealingPhone.set(true);
    try {
      const result = await firstValueFrom(
        this.revealApi.revealPhone(
          scope,
          detail.value.summary.orderId,
          REVEAL_PURPOSE.contactAmend,
        ),
      );
      this.revealedPhone.set(result.phone);
    } catch (error) {
      this.noticeFromRevealError(error);
    } finally {
      this.revealingPhone.set(false);
    }
    this.dialog.set('changeContact');
  }

  protected changeContactInitialName(): string {
    return this.order()?.value.customer.displayName ?? '';
  }

  /** `CHANGE_CONTACT` — ADR 0029-protected, never reprices, so this applies in the same call as the five original commands. */
  protected onChangeContactConfirm(submission: ContactSubmission): void {
    const detail = this.order();
    const scope = this.location.scope();
    if (!detail || !scope) {
      return;
    }
    void this.submitAmendment(
      this.amendmentsApi.changeContact(
        scope,
        detail.value.summary.orderId,
        detail.value.summary.version ?? 0,
        submission.recipientName,
        submission.recipientPhone,
      ),
    ).finally(() => this.dialog.set(null));
  }

  /**
   * The three repricing commands' shared submit path (`ADD_LINES`,
   * `CHANGE_LINE_QUANTITY`, `CHANGE_DELIVERY_ADDRESS`): each is proposed with
   * `applyImmediately: false` (see each `OrderAmendmentsApi` method's own
   * doc), so `propose()` never applies it — the priced-delta confirmation
   * dialog is not one outcome among several here, it is the only way
   * forward, and this method always opens it on a successful proposal
   * rather than gating on `AmendmentResponse.actions` the way the RESOLVE
   * row action does for an *already*-proposed amendment.
   */
  private async submitRepricingAmendment(request: Observable<AmendmentResponse>): Promise<void> {
    const orderId = this.order()?.value.summary.orderId;
    if (!orderId) {
      return;
    }
    this.busy.set(true);
    try {
      const result = await firstValueFrom(request);
      this.openAmendmentConfirmDialog(result);
    } catch (error) {
      this.handleMutationError(orderId, error, false);
      this.dialog.set(null);
    } finally {
      this.busy.set(false);
    }
  }

  /**
   * Opens `q-order-amendment-confirm-dialog` for one amendment — a freshly
   * proposed one ({@link submitRepricingAmendment}) or an older one still
   * `PRICED`, reopened by the history table's own RESOLVE action
   * ({@link resolveAmendment}). Both read the identical three fields off an
   * `AmendmentResponse`, because the history's own `GET .../amendments` and a
   * fresh `POST .../amendments` answer with the same shape.
   */
  private openAmendmentConfirmDialog(amendment: AmendmentResponse): void {
    this.pendingAmendment.set({
      amendmentId: amendment.amendmentId,
      amendmentVersion: amendment.amendmentVersion,
      deltaMinor: amendment.deltaTotalMinor,
      requiresApproval: amendment.requiresApproval,
    });
    this.dialog.set('amendConfirm');
  }

  /**
   * The history table's own RESOLVE action (wave 10): reopens the
   * confirmation dialog for an amendment `AmendmentResponse.actions` marks
   * open and blocked, whether that is the customer's recorded agreement, an
   * ADR 0027 approval still pending, or both — `q-order-amendment-confirm
   * -dialog`'s own doc explains why the same one dialog and the same one
   * `POST .../confirmation` call handle every case: it is the only endpoint
   * that can move a `PRICED` amendment forward.
   */
  protected resolveAmendment(amendment: AmendmentResponse): void {
    this.openAmendmentConfirmDialog(amendment);
  }

  /**
   * `POST .../amendments/{id}/confirmation` — attests the operator's record
   * of how the customer agreed (or, for a pure ADR 0027 approval-pending
   * amendment with no increase, simply attempts the only apply path that
   * exists) and applies the amendment in the same call. `pendingAmendment`'s
   * `amendmentVersion` is the amendment row's own optimistic version, not
   * the order's.
   */
  protected onAmendConfirmDialogConfirm(channel: AmendmentConfirmationChannel): void {
    const detail = this.order();
    const scope = this.location.scope();
    const pending = this.pendingAmendment();
    if (!detail || !scope || !pending) {
      return;
    }
    void this.submitAmendment(
      this.amendmentsApi.confirm(
        scope,
        detail.value.summary.orderId,
        pending.amendmentId,
        pending.amendmentVersion,
        channel,
      ),
    ).finally(() => {
      this.pendingAmendment.set(null);
      this.dialog.set(null);
      // `submitAmendment`'s own success path already called `load()`, whose
      // full reset (see that method's own doc) closes the history table —
      // exactly like every one of the five original commands' own mutation
      // already does. A RESOLVE click's own row is stale after that anyway
      // (its RESOLVE action is gone once applied), so the operator reopens
      // the table to see the resolved state, the same as any other change.
    });
  }

  // ------------------------------------------------------------ §3.6/§3.10 amendment history

  /**
   * Toggles the amendment history, fetching on first open only — mirrors
   * {@link toggleRevisions} exactly, including resetting on every
   * {@link load} (a fresh amendment just applied makes the cached list
   * stale).
   */
  protected async toggleAmendmentHistory(): Promise<void> {
    const opening = !this.amendmentHistoryOpen();
    this.amendmentHistoryOpen.set(opening);
    if (!opening || this.amendmentHistory() !== null) {
      return;
    }
    await this.loadAmendmentHistory();
  }

  /**
   * The fetch half of {@link toggleAmendmentHistory}, split out so {@link
   * onAmendConfirmDialogConfirm} can force a fresh read after RESOLVE
   * without the toggle's own "only fetch once" guard standing in the way —
   * a resolved amendment's row (and its now-gone RESOLVE action) needs to
   * reflect what just happened, not the list `toggleAmendmentHistory`
   * already cached before the operator clicked RESOLVE.
   */
  private async loadAmendmentHistory(): Promise<void> {
    const detail = this.order();
    const scope = this.location.scope();
    if (!detail || !scope) {
      return;
    }
    this.amendmentHistoryLoading.set(true);
    this.amendmentHistoryError.set(false);
    try {
      const result = await this.amendmentsApi.history(scope, detail.value.summary.orderId);
      this.amendmentHistory.set(result);
    } catch (error) {
      if (error instanceof ApiError) {
        this.amendmentHistoryError.set(true);
      } else {
        throw error;
      }
    } finally {
      this.amendmentHistoryLoading.set(false);
    }
  }

  protected amendmentCommandLabel(type: string): string {
    return amendmentCommandLabel(type, (key) => this.i18n.t(key));
  }

  /**
   * Fetch-before-open (wave 24): the picker needs `GET .../reject-reasons`
   * before it has anything to show, and opening on an empty list would be a
   * dialog with no way to confirm. A failed fetch surfaces through the same
   * notice band every other mutation error already uses, and the dialog
   * never opens.
   */
  private async openRejectDialog(): Promise<void> {
    const scope = this.location.scope();
    if (!scope) {
      return;
    }
    try {
      this.rejectReasons.set(await this.rejectReasonsApi.list(scope));
      this.dialog.set('reject');
    } catch (error) {
      if (error instanceof ApiError) {
        this.notice.set(this.errorMessage(error));
      } else {
        throw error;
      }
    }
  }

  /**
   * Fetch-before-open (orders.md §4.5, wave P09 row `1.2k`), the same rule as
   * {@link openRejectDialog}: the picker needs the tenant's active
   * `CANCELLATION` reasons before it has anything to show. `ORDER_ACTION`s
   * cancel is now offered from `CONFIRMED` onward too — the reasoned path
   * this dialog exists for.
   */
  private async openCancelDialog(): Promise<void> {
    const scope = this.location.scope();
    if (!scope) {
      return;
    }
    try {
      this.outcomeReasons.set(await this.referenceDataApi.list(scope, 'CANCELLATION'));
      this.dialog.set('cancel');
    } catch (error) {
      if (error instanceof ApiError) {
        this.notice.set(this.errorMessage(error));
      } else {
        throw error;
      }
    }
  }

  /**
   * Fetch-before-open (wave 9 row `1.1h`), the same rule {@link
   * openCancelDialog} follows: `POST .../state-overrides` reuses the
   * `CANCELLATION` registry for its mandatory `reasonId` — see
   * `OperationsOrderController.StateOverrideRequest`'s own doc — so this
   * reads the identical list {@link openCancelDialog} does rather than a
   * dedicated one that does not exist yet.
   */
  private async openOverrideDialog(targetStatus: string): Promise<void> {
    const scope = this.location.scope();
    if (!scope) {
      return;
    }
    try {
      this.outcomeReasons.set(await this.referenceDataApi.list(scope, 'CANCELLATION'));
      this.pendingOverrideTarget.set(targetStatus);
      this.dialog.set('override');
    } catch (error) {
      if (error instanceof ApiError) {
        this.notice.set(this.errorMessage(error));
      } else {
        throw error;
      }
    }
  }

  /**
   * §4.6: "where exactly one reason is valid for the order's mode... the
   * action completes without a dialog." Fetches the tenant's active
   * `COMPLETION` reasons, narrows them to this order's fulfilment mode, and
   * either submits the one unambiguous choice directly or opens the picker
   * for the operator to choose among several. An empty result (a tenant that
   * configured no completion reason for this mode) falls back to the
   * reasonless call — the server's own honest default, never invented here.
   */
  private async startCompletion(): Promise<void> {
    const detail = this.order();
    const scope = this.location.scope();
    if (!detail || !scope) {
      return;
    }
    const mode = detail.value.summary.fulfillmentMode ?? '';
    try {
      const reasons = await this.referenceDataApi.list(scope, 'COMPLETION');
      const eligible = reasons.filter(
        (reason) =>
          !reason.allowedFulfillmentModes || reason.allowedFulfillmentModes.includes(mode),
      );
      if (eligible.length === 0) {
        void this.submitCompletion();
      } else if (eligible.length === 1) {
        void this.submitCompletion(eligible[0].id);
      } else {
        this.outcomeReasons.set(eligible);
        this.dialog.set('complete');
      }
    } catch (error) {
      if (error instanceof ApiError) {
        this.notice.set(this.errorMessage(error));
      } else {
        throw error;
      }
    }
  }

  private async submitCompletion(reasonId?: string): Promise<void> {
    const detail = this.order();
    const scope = this.location.scope();
    if (!detail || !scope) {
      return;
    }
    const orderId = detail.value.summary.orderId;
    const version = detail.value.summary.version ?? 0;

    await this.submitStateMutation(this.actionsApi.complete(scope, orderId, version, reasonId));
  }

  protected onDialogDismiss(): void {
    this.dialog.set(null);
    this.pendingOverrideTarget.set(null);
    this.pendingAmendment.set(null);
  }

  protected onCancelDialogConfirm(submission: OutcomeReasonSubmission): void {
    const detail = this.order();
    const scope = this.location.scope();
    if (!detail || !scope) {
      return;
    }
    const orderId = detail.value.summary.orderId;
    const version = detail.value.summary.version ?? 0;

    void this.submitCancelMutation(
      this.actionsApi.cancelWithReason(
        scope,
        orderId,
        version,
        submission.reasonId,
        submission.reasonCode,
        submission.note,
      ),
    ).finally(() => this.dialog.set(null));
  }

  protected onCompletionDialogConfirm(submission: OutcomeReasonSubmission): void {
    void this.submitCompletion(submission.reasonId).finally(() => this.dialog.set(null));
  }

  /** §0.2/§11.3, wave 9 row `1.1h`: `POST .../state-overrides`, with the target {@link openOverrideDialog} captured and the operator's chosen registry reason. */
  protected onOverrideDialogConfirm(submission: OutcomeReasonSubmission): void {
    const detail = this.order();
    const scope = this.location.scope();
    const target = this.pendingOverrideTarget();
    if (!detail || !scope || !target) {
      return;
    }
    const orderId = detail.value.summary.orderId;
    const version = detail.value.summary.version ?? 0;

    void this.submitStateMutation(
      this.actionsApi.override(scope, orderId, target, version, submission.reasonId),
    ).finally(() => {
      this.dialog.set(null);
      this.pendingOverrideTarget.set(null);
    });
  }

  /** The override dialog's title interpolation — the status it is about to restore, in this operator's own language. */
  protected overrideTitleValues(): Readonly<Record<string, string>> {
    return { status: this.statusLabel(this.pendingOverrideTarget() ?? '') };
  }

  protected onRejectDialogConfirm(submission: OrderRejectSubmission): void {
    const detail = this.order();
    const scope = this.location.scope();
    if (!detail || !scope) {
      return;
    }
    const orderId = detail.value.summary.orderId;

    void this.submitDecision(
      this.actionsApi.reject(
        scope,
        orderId,
        this.decisionIds.idFor(orderId),
        submission.reasonCode,
        submission.note,
      ),
    ).finally(() => this.dialog.set(null));
  }

  private async submitDecision(request: Observable<DecisionResponse>): Promise<void> {
    const orderId = this.order()?.value.summary.orderId;
    if (!orderId) {
      return;
    }
    this.busy.set(true);
    try {
      const result = await firstValueFrom(request);
      this.decisionIds.settle(orderId);
      if (!result.applied && result.effectiveAction) {
        this.notice.set(
          this.i18n.t('orders.action.lostRace', {
            action: this.decisionActionLabel(result.effectiveAction),
          }),
        );
      }
      await this.load(orderId);
    } catch (error) {
      this.handleMutationError(orderId, error, true);
    } finally {
      this.busy.set(false);
    }
  }

  private async submitStateMutation(request: Observable<DecisionResponse>): Promise<void> {
    const orderId = this.order()?.value.summary.orderId;
    if (!orderId) {
      return;
    }
    this.busy.set(true);
    try {
      await firstValueFrom(request);
      await this.load(orderId);
    } catch (error) {
      this.handleMutationError(orderId, error, false);
    } finally {
      this.busy.set(false);
    }
  }

  /**
   * `.../cancellations`' own mutation (gap map row 1.2g): identical to {@link
   * submitStateMutation} except it also surfaces `deliveryCancellation` — what
   * the cascade found out about the courier provider, in the same notice band
   * every other mutation error already uses. `NOTHING_TO_CANCEL` and `PLAN_CANCELLED`
   * say nothing here: neither one is a fact about a courier an operator needs
   * to see.
   */
  private async submitCancelMutation(
    request: Observable<OrderCancellationResponse>,
  ): Promise<void> {
    const orderId = this.order()?.value.summary.orderId;
    if (!orderId) {
      return;
    }
    this.busy.set(true);
    try {
      const result = await firstValueFrom(request);
      const outcomeText = deliveryCancellationOutcomeText(result.deliveryCancellation, (key) =>
        this.i18n.t(key),
      );
      if (outcomeText) {
        this.notice.set(outcomeText);
      }
      await this.load(orderId);
    } catch (error) {
      this.handleMutationError(orderId, error, false);
    } finally {
      this.busy.set(false);
    }
  }

  private handleMutationError(orderId: string, error: unknown, isDecision: boolean): void {
    if (!(error instanceof ApiError)) {
      throw error;
    }
    if (isDecision && !error.isRetryable) {
      this.decisionIds.settle(orderId);
    }
    const outcome = mutationErrorNotice(
      error,
      (key, values) => this.i18n.t(key, values),
      (status) => this.statusLabel(status),
    );
    this.notice.set(outcome.text);
    if (outcome.shouldReread) {
      void this.load(orderId);
    }
  }

  private decisionActionLabel(effectiveAction: string): string {
    return decisionOutcomeLabel(effectiveAction, (key) => this.i18n.t(key));
  }

  // ------------------------------------------------------------ §3.4 lines

  protected lineName(line: OrderLine): string {
    return line.productName;
  }

  /** Row 2.1b: a preset's label in the console's own locale, matching `data-privacy-page.ts`'s own `consentLabel` selection. */
  protected presetLabel(preset: OrderLineCommentPreset): string {
    switch (this.i18n.locale()) {
      case 'ru':
        return preset.labelRu;
      case 'uz-Latn':
        return preset.labelUz;
      default:
        return preset.labelEn;
    }
  }

  /** §3.6's «Комментарий клиента к позиции» pointer: whether any line has one to reveal, above. */
  protected hasAnyLineNote(): boolean {
    return (this.order()?.value.lines ?? []).some((line) => line.hasNote);
  }

  protected revealedNote(lineId: string): string | null | undefined {
    // undefined = never revealed this load; null = revealed and genuinely empty.
    return this.revealedNotes().get(lineId);
  }

  protected isRevealingNote(lineId: string): boolean {
    return this.revealingNoteFor() === lineId;
  }

  protected async revealLineNote(lineId: string): Promise<void> {
    const detail = this.order();
    const scope = this.location.scope();
    if (!detail || !scope) {
      return;
    }
    this.revealingNoteFor.set(lineId);
    try {
      const result = await firstValueFrom(
        this.revealApi.revealLineNote(
          scope,
          detail.value.summary.orderId,
          lineId,
          REVEAL_PURPOSE.lineNote,
        ),
      );
      this.revealedNotes.update((current) => {
        const next = new Map(current);
        next.set(lineId, result.note);
        return next;
      });
    } catch (error) {
      this.noticeFromRevealError(error);
    } finally {
      this.revealingNoteFor.set(null);
    }
  }

  // ------------------------------------------------------------ §1.3 money

  protected moneyReconciliation(): MoneyReconciliation | null {
    const detail = this.order();
    if (!detail) {
      return null;
    }
    return reconcileMoney(
      detail.value.lines,
      detail.value.subtotalMinor,
      detail.value.summary.totalMinor,
    );
  }

  // ------------------------------------------------------------ §3.7 customer

  protected isDeliveryOrder(): boolean {
    return this.order()?.value.summary.fulfillmentMode === 'DELIVERY';
  }

  /**
   * Click-to-call: reveals and displays the number. Copy-to-clipboard below
   * is a *separate* audited call, never a read of this one's result (§1.5).
   */
  protected async revealPhone(): Promise<void> {
    await this.fetchPhone(REVEAL_PURPOSE.phoneCall);
  }

  protected async copyPhone(): Promise<void> {
    await this.fetchPhone(REVEAL_PURPOSE.phoneCopy);
    const phone = this.revealedPhone();
    if (phone) {
      try {
        await navigator.clipboard.writeText(phone);
      } catch {
        // Clipboard access denied or unavailable (an insecure context, a
        // locked-down kiosk profile) — the number is still on screen to copy
        // by hand, which is a worse but not broken outcome.
      }
    }
  }

  private async fetchPhone(purpose: string): Promise<void> {
    const detail = this.order();
    const scope = this.location.scope();
    if (!detail || !scope) {
      return;
    }
    this.revealingPhone.set(true);
    try {
      const result = await firstValueFrom(
        this.revealApi.revealPhone(scope, detail.value.summary.orderId, purpose),
      );
      this.revealedPhone.set(result.phone);
    } catch (error) {
      this.noticeFromRevealError(error);
    } finally {
      this.revealingPhone.set(false);
    }
  }

  // ------------------------------------------------------------ §3.8 address

  protected async revealAddress(): Promise<void> {
    const detail = this.order();
    const scope = this.location.scope();
    if (!detail || !scope) {
      return;
    }
    this.revealingAddress.set(true);
    try {
      const result = await firstValueFrom(
        this.revealApi.revealAddress(scope, detail.value.summary.orderId, REVEAL_PURPOSE.address),
      );
      this.revealedAddress.set(result);
    } catch (error) {
      this.noticeFromRevealError(error);
    } finally {
      this.revealingAddress.set(false);
    }
  }

  protected hasCoordinates(address: OrderAddressReveal): boolean {
    return address.latitude !== 0 || address.longitude !== 0;
  }

  private noticeFromRevealError(error: unknown): void {
    if (error instanceof ApiError) {
      this.notice.set(this.errorMessage(error));
    } else {
      throw error;
    }
  }

  // ------------------------------------------------------------ §3.10 timeline

  /**
   * §3.10: "if the sequence has a gap the panel says «пропущена запись N»,
   * because hiding it hides a bug." Reports the first sequence number missing
   * immediately before `entries[index]`, or null when there is none.
   */
  protected missingSequenceBefore(
    entries: readonly OrderTimelineEntry[],
    index: number,
  ): number | null {
    if (index === 0) {
      return null;
    }
    const previous = entries[index - 1];
    const current = entries[index];
    return current.sequence === previous.sequence + 1 ? null : previous.sequence + 1;
  }

  protected triggerLabel(trigger: string): string {
    const key = TRIGGER_LABEL_KEYS[trigger];
    // A trigger this client does not know yet renders as its own raw value —
    // the same forward-compatibility rule as an unknown status (order-status.ts).
    return key ? this.i18n.t(key) : trigger;
  }

  /** `q-timeline`'s `TimelineEntry.gapBefore`, translated from {@link missingSequenceBefore}. */
  private gapLabel(entries: readonly OrderTimelineEntry[], index: number): string | null {
    const missing = this.missingSequenceBefore(entries, index);
    return missing === null
      ? null
      : this.i18n.t('orders.detail.timeline.gap', { sequence: missing });
  }

  /** `TicketStatus`'s own four stages, for the production lane's `q-steps`. */
  protected kitchenStageLabel(stage: string): string {
    const key = KITCHEN_STAGE_LABEL_KEYS[stage];
    return key ? this.i18n.t(key) : stage;
  }

  /** `ShipmentStatus`'s custody milestones, for the delivery lane's `q-steps`. */
  protected deliveryStageLabel(stage: string): string {
    const key = DELIVERY_STAGE_LABEL_KEYS[stage];
    return key ? this.i18n.t(key) : stage;
  }

  /**
   * `ShipmentStatus`'s own six values (gap map rows 1.2e/2.1c) — distinct
   * from {@link deliveryStageLabel}, which only covers the three the
   * timeline's `q-steps` renders as milestones; this one is for the § Курьер
   * panel's external-partner state line, which needs every status including
   * `PENDING`/`PICKUP_PENDING`/`CANCELLED`.
   */
  protected shipmentStatusLabel(status: string): string {
    const key = SHIPMENT_STATUS_LABEL_KEYS[status];
    return key ? this.i18n.t(key) : status;
  }

  /** `formatDuration`'s own `hour`/`minute` units, applied to the gap between two ISO instants — the production/delivery lanes' own "elapsed durations between stages". */
  protected formatElapsed(fromIso: string, toIso: string): string {
    const minutes = (new Date(toIso).getTime() - new Date(fromIso).getTime()) / 60_000;
    return formatDuration(minutes, {
      hour: this.i18n.t('orders.duration.hour'),
      minute: this.i18n.t('orders.duration.minute'),
    });
  }

  /**
   * Every decision that did not settle the order (wave P11, row `1.2b`) — the
   * one thing no other screen in this console shows. At most one decision per
   * order is ever `effective`; every other row here is a click that lost the
   * compare-and-set, a duplicate, or arrived after the order was already
   * decided.
   */
  protected readonly losingDecisions = computed<readonly OrderApprovalDecision[]>(() =>
    this.decisions().filter((decision) => !decision.effective),
  );

  /** {@link losingDecisions} as `q-timeline`'s own `TimelineEntry[]`, exactly the mapping {@link commercialTimelineEntries} does for the transitions above it. */
  protected readonly losingDecisionEntries = computed<readonly TimelineEntry[]>(() =>
    this.losingDecisions().map((decision) => ({
      id: decision.decisionId,
      timestamp: this.formatOccurredAt(decision.issuedAt),
      actor: { kind: decision.actorType, displayName: null, subject: decision.actorId ?? null },
      title: this.decisionOutcomeLabel(decision.action),
      detail: decision.reasonCode
        ? `${decision.decisionChannel} · ${decision.reasonCode}`
        : decision.decisionChannel,
      selectable: false,
    })),
  );

  protected decisionOutcomeLabel(action: string): string {
    return decisionOutcomeLabel(action, (key) => this.i18n.t(key));
  }

  // ------------------------------------------------------------ §1.2e assign/unassign courier

  protected canManageCourier(): boolean {
    return this.isDeliveryOrder() && this.delivery() !== null;
  }

  /**
   * The courier ETA chip (wave P11, gap map row 2.1a) -- the winning
   * partner quote's own ETA, fetched into {@link delivery} since that wave
   * but never rendered until now. Mirrors `kitchen-queue-page.ts`'s own
   * `courierEtaLabel` for the same field, under this pane's own i18n key
   * rather than the kitchen board's.
   */
  protected courierEtaLabel(plan: OrderDeliveryResponse): string | null {
    return plan.courierEtaAt
      ? this.i18n.t('orders.detail.courier.eta', {
          time: formatClock(new Date(plan.courierEtaAt), PLACEHOLDER_TIME_ZONE),
        })
      : null;
  }

  /** The roster entry's own `displayReference` — there is no name to resolve, the same limitation `CouriersApi.roster`'s own doc states. */
  protected courierDisplayReference(courierId: string): string {
    return (
      this.courierRoster().find((courier) => courier.courierId === courierId)?.displayReference ??
      courierId
    );
  }

  protected async toggleCourierPicker(): Promise<void> {
    const opening = !this.courierPickerOpen();
    this.courierPickerOpen.set(opening);
    if (!opening || this.courierRoster().length > 0) {
      return;
    }
    const scope = this.location.scope();
    if (!scope) {
      return;
    }
    this.courierRoster.set(await this.couriersApi.roster(scope.tenantId));
  }

  protected courierOptions(): readonly ComboboxOption[] {
    return this.courierRoster().map((courier) => ({
      id: courier.courierId,
      label: courier.displayReference,
    }));
  }

  protected async assignCourier(option: ComboboxOption): Promise<void> {
    const scope = this.location.scope();
    const plan = this.delivery();
    const orderId = this.order()?.value.summary.orderId;
    if (!scope || !plan || !orderId) {
      return;
    }
    this.assigningCourier.set(true);
    try {
      const result = await this.dispatchApi.assign(
        scope,
        plan.planId,
        option.id,
        plan.planVersion,
        ORDER_DETAIL_ASSIGN_REASON,
      );
      if (!result.applied) {
        this.notice.set(
          this.i18n.t('orders.detail.courier.refused', { reason: result.reason ?? '' }),
        );
      }
      this.courierPickerOpen.set(false);
      await this.loadDelivery(orderId);
    } catch (error) {
      this.noticeFromRevealError(error);
    } finally {
      this.assigningCourier.set(false);
    }
  }

  protected async unassignCourier(): Promise<void> {
    const scope = this.location.scope();
    const plan = this.delivery();
    const orderId = this.order()?.value.summary.orderId;
    if (!scope || !plan?.shipment || !orderId) {
      return;
    }
    this.assigningCourier.set(true);
    try {
      const result = await this.dispatchApi.unassign(
        scope,
        plan.planId,
        plan.shipment.version,
        ORDER_DETAIL_UNASSIGN_REASON,
      );
      if (!result.applied) {
        this.notice.set(
          this.i18n.t('orders.detail.courier.refused', { reason: result.reason ?? '' }),
        );
      }
      await this.loadDelivery(orderId);
    } catch (error) {
      this.noticeFromRevealError(error);
    } finally {
      this.assigningCourier.set(false);
    }
  }

  // ------------------------------------------------------------ §1.2f «Вызвать курьера»

  /**
   * Opens the Millenium-pattern confirmation dialog (gap map row 1.2f):
   * fetches the branch's external partners fresh every time, since a binding
   * disabled between two orders must not offer a partner that can no longer
   * be booked.
   */
  protected async openExternalCourierDialog(): Promise<void> {
    const scope = this.location.scope();
    const plan = this.delivery();
    if (!scope || !plan) {
      return;
    }
    this.externalQuote.set(null);
    this.dialog.set('externalCourier');
    this.externalPartners.set(await this.dispatchApi.externalPartners(scope, plan.planId));
  }

  protected async requestExternalQuote(bindingId: string): Promise<void> {
    const scope = this.location.scope();
    const plan = this.delivery();
    const orderId = this.order()?.value.summary.orderId;
    if (!scope || !plan || !orderId) {
      return;
    }
    this.externalCourierBusy.set(true);
    try {
      // Order-keyed (gap map rows 1.2e/2.1c) rather than `dispatchApi
      // .externalQuote(scope, plan.planId, ...)` -- the same path the KDS
      // pass now calls, so both screens share one server-side plan
      // resolution instead of each carrying its own.
      this.externalQuote.set(
        await this.deliveryApi.requestExternalCourierQuote(scope, orderId, bindingId),
      );
    } catch (error) {
      this.noticeFromRevealError(error);
    } finally {
      this.externalCourierBusy.set(false);
    }
  }

  protected async acceptExternalBooking(submission: ExternalBookingSubmission): Promise<void> {
    await this.settleExternalBooking(submission, 'ACCEPT', 'OPERATIONS_EXTERNAL_BOOKING_ACCEPT');
  }

  protected async abandonExternalBooking(submission: ExternalBookingSubmission): Promise<void> {
    await this.settleExternalBooking(submission, 'ABANDON', 'OPERATIONS_EXTERNAL_BOOKING_ABANDON');
  }

  private async settleExternalBooking(
    submission: ExternalBookingSubmission,
    decision: 'ACCEPT' | 'ABANDON',
    reasonCode: string,
  ): Promise<void> {
    const scope = this.location.scope();
    const plan = this.delivery();
    const orderId = this.order()?.value.summary.orderId;
    if (!scope || !plan || !orderId) {
      return;
    }
    this.externalCourierBusy.set(true);
    try {
      // Same order-keyed path {@link requestExternalQuote} switched to.
      const result = await this.deliveryApi.decideExternalCourier(
        scope,
        orderId,
        submission.bindingId,
        submission.quoteId,
        decision,
        reasonCode,
      );
      if (!result.applied && result.reason) {
        this.notice.set(this.i18n.t('orders.detail.courier.refused', { reason: result.reason }));
      }
      this.dialog.set(null);
      await this.loadDelivery(orderId);
    } catch (error) {
      this.noticeFromRevealError(error);
    } finally {
      this.externalCourierBusy.set(false);
    }
  }

  // ------------------------------------------------------------ §1.2g cancel shipment

  /** Whether the current plan's shipment may be cancelled at all — a `DELIVERED`/`CANCELLED` one is already settled. */
  protected canCancelShipment(): boolean {
    const shipment = this.delivery()?.shipment;
    return !!shipment && !SHIPMENT_CANCEL_TERMINAL_STATUSES.has(shipment.status);
  }

  protected openShipmentCancelDialog(): void {
    this.dialog.set('shipmentCancel');
  }

  /**
   * The dedicated, provider-notifying cancel (gap map row 1.2g) —
   * `DispatchController.cancelShipment`'s first console caller on this pane,
   * distinct from {@link unassignCourier}: a `PARTNER` shipment's provider is
   * actually called, and an uncertain or chargeable answer opens
   * `fulfillment.delivery_exceptions`, which the {@link loadDelivery} reload
   * below picks straight back up into the delivery-exception band.
   */
  protected async confirmShipmentCancel(): Promise<void> {
    const scope = this.location.scope();
    const plan = this.delivery();
    const orderId = this.order()?.value.summary.orderId;
    if (!scope || !plan?.shipment || !orderId) {
      return;
    }
    this.shipmentCancelBusy.set(true);
    try {
      const result = await this.dispatchApi.cancelShipment(
        scope,
        plan.shipment.shipmentId,
        plan.shipment.version,
        ORDER_DETAIL_SHIPMENT_CANCEL_REASON,
      );
      if (!result.applied) {
        this.notice.set(
          this.i18n.t('orders.detail.courier.refused', { reason: result.conflictReason ?? '' }),
        );
      }
      this.dialog.set(null);
      await this.loadDelivery(orderId);
    } catch (error) {
      this.noticeFromRevealError(error);
    } finally {
      this.shipmentCancelBusy.set(false);
    }
  }

  // ------------------------------------------------------------ §3.9 revisions (row 1.2p)

  /**
   * Toggles the revisions view, fetching on first open only — `revisions()`
   * stays populated across a collapse/re-expand within the same order so a
   * second look costs nothing.
   */
  protected async toggleRevisions(): Promise<void> {
    const opening = !this.revisionsOpen();
    this.revisionsOpen.set(opening);
    if (!opening || this.revisions() !== null) {
      return;
    }
    const detail = this.order();
    const scope = this.location.scope();
    if (!detail || !scope) {
      return;
    }
    this.revisionsLoading.set(true);
    this.revisionsError.set(false);
    try {
      const result = await firstValueFrom(
        this.api.get<RevisionResponse[]>(
          operationsPaths.orderRevisions(scope, detail.value.summary.orderId),
        ),
      );
      this.revisions.set(result.value ?? []);
    } catch (error) {
      if (error instanceof ApiError) {
        this.revisionsError.set(true);
      } else {
        throw error;
      }
    } finally {
      this.revisionsLoading.set(false);
    }
  }

  // ------------------------------------------------------------ §3.4/§3.9 attribution and outcome

  protected outcomeKindLabel(kind: string): string {
    return outcomeKindLabel(kind, (key, values) => this.i18n.t(key, values));
  }

  protected outcomeCategoryLabel(category: string): string {
    return outcomeSystemCategoryLabel(category, (key, values) => this.i18n.t(key, values));
  }

  protected stockDispositionLabel(value: string): string {
    return stockDispositionLabel(value, (key, values) => this.i18n.t(key, values));
  }

  protected liabilityPartyLabel(value: string): string {
    return liabilityPartyLabel(value, (key, values) => this.i18n.t(key, values));
  }

  protected customerRefundLabel(value: string): string {
    return customerRefundLabel(value, (key, values) => this.i18n.t(key, values));
  }

  /** The delivery-exception band's own reason label (gap map rows 1.2f/1.2g). */
  protected deliveryExceptionReasonLabel(value: string): string {
    return deliveryExceptionReasonLabel(value, (key, values) => this.i18n.t(key, values));
  }

  /**
   * Gap map row 9.2d: prefers the server-resolved `displayName` (`StaffDisplayNames`)
   * over the raw `${actorType} · ${actorId}` pair this pane showed before —
   * an operator or a manager, not a UUID, took or accepted the order. Falls
   * back to the type/id pair for a non-`"USER"` actor (a machine principal
   * has no Keycloak identity to resolve) or a subject with no name on file,
   * the same limitation the commercial timeline's own doc comment names for
   * `actorType`. Machine principals (`"SYSTEM"`) carry no id and render as
   * the type alone.
   */
  protected actorDisplay(
    actorType: string | null | undefined,
    actorId: string | null | undefined,
    displayName?: string | null,
  ): string | null {
    if (displayName) {
      return displayName;
    }
    if (!actorType) {
      return null;
    }
    return actorId ? `${actorType} · ${actorId}` : actorType;
  }
}

const KITCHEN_STAGE_LABEL_KEYS: Readonly<Partial<Record<string, MessageKey>>> = {
  FIRED: 'orders.detail.timeline.kitchen.FIRED',
  IN_PRODUCTION: 'orders.detail.timeline.kitchen.IN_PRODUCTION',
  READY: 'orders.detail.timeline.kitchen.READY',
  HANDED_OVER: 'orders.detail.timeline.kitchen.HANDED_OVER',
  HELD: 'orders.detail.timeline.kitchen.HELD',
  VOIDED: 'orders.detail.timeline.kitchen.VOIDED',
};

const DELIVERY_STAGE_LABEL_KEYS: Readonly<Partial<Record<string, MessageKey>>> = {
  ASSIGNED: 'orders.detail.timeline.delivery.ASSIGNED',
  PICKED_UP: 'orders.detail.timeline.delivery.PICKED_UP',
  DELIVERED: 'orders.detail.timeline.delivery.DELIVERED',
};

/** `ShipmentStatus`'s own six values (gap map rows 1.2e/2.1c) — see {@link OrderDetailPane.shipmentStatusLabel}. */
const SHIPMENT_STATUS_LABEL_KEYS: Readonly<Partial<Record<string, MessageKey>>> = {
  PENDING: 'orders.detail.courier.status.PENDING',
  ASSIGNED: 'orders.detail.courier.status.ASSIGNED',
  PICKUP_PENDING: 'orders.detail.courier.status.PICKUP_PENDING',
  PICKED_UP: 'orders.detail.courier.status.PICKED_UP',
  DELIVERED: 'orders.detail.courier.status.DELIVERED',
  CANCELLED: 'orders.detail.courier.status.CANCELLED',
};

const TRIGGER_LABEL_KEYS: Readonly<Partial<Record<string, MessageKey>>> = {
  CHECKOUT: 'orders.detail.timeline.trigger.CHECKOUT',
  APPROVAL_DECISION: 'orders.detail.timeline.trigger.APPROVAL_DECISION',
  APPROVAL_TIMEOUT: 'orders.detail.timeline.trigger.APPROVAL_TIMEOUT',
  PAYMENT_RESULT: 'orders.detail.timeline.trigger.PAYMENT_RESULT',
  OPERATIONS_ACTION: 'orders.detail.timeline.trigger.OPERATIONS_ACTION',
  KITCHEN_PROGRESS: 'orders.detail.timeline.trigger.KITCHEN_PROGRESS',
  CUSTOMER_ACTION: 'orders.detail.timeline.trigger.CUSTOMER_ACTION',
  SYSTEM: 'orders.detail.timeline.trigger.SYSTEM',
};
