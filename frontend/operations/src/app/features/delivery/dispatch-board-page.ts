import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { LocationScope, operationsPaths } from '../../core/api/operations-paths';
import { ApiError } from '../../core/api/problem-details';
import { CurrentLocation } from '../../core/auth/current-location';
import { I18n } from '../../core/i18n/i18n';
import { MessageKey } from '../../core/i18n/messages.en';
import { TPipe } from '../../core/i18n/t.pipe';
import { ConfirmDialog } from '../../shared/ui/confirm-dialog';
import { DragDropAssign, QBoardCardDef } from '../../shared/ui/drag-drop-assign/drag-drop-assign';
import {
  DragDropAssignColumn,
  DragDropAssignOutcome,
  DragDropAssignRejection,
} from '../../shared/ui/drag-drop-assign/drag-drop-assign-types';
import { StatusPill, StatusTone } from '../../shared/ui/status-pill';
import { CouriersApi, RosterEntryResponse } from '../couriers/couriers-api';
import {
  ExternalBookingSubmission,
  ExternalCourierDialog,
} from '../orders/external-courier-dialog';
import { describeApiError } from '../orders/order-errors';
import { pruneSelection, toggleOne } from '../orders/order-queue-selection';
import { OrderSummaryResponse } from '../orders/order-summary';
import {
  DispatchApi,
  ExceptionResponse,
  ExternalPartnerResponse,
  ExternalQuoteResponse,
  PlanQueueResponse,
} from './dispatch-api';

/**
 * `ShipmentStatus`'s own six values (gap map rows 1.2e/1.2f/1.2g/2.1c) —
 * the same vocabulary `order-detail-pane.ts`'s own `SHIPMENT_STATUS_LABEL_KEYS`
 * covers for the § Курьер panel; kept as a small local copy here rather than
 * a cross-feature import so this board's own labels do not depend on the
 * order feature's internals.
 */
const SHIPMENT_STATUS_LABEL_KEYS: Readonly<Partial<Record<string, MessageKey>>> = {
  PENDING: 'orders.detail.courier.status.PENDING',
  ASSIGNED: 'orders.detail.courier.status.ASSIGNED',
  PICKUP_PENDING: 'orders.detail.courier.status.PICKUP_PENDING',
  PICKED_UP: 'orders.detail.courier.status.PICKED_UP',
  DELIVERED: 'orders.detail.courier.status.DELIVERED',
  CANCELLED: 'orders.detail.courier.status.CANCELLED',
};

/** A shipment past these statuses is already settled — cancelling it again is refused server-side, so the action is not offered. */
const SHIPMENT_CANCEL_TERMINAL_STATUSES: ReadonlySet<string> = new Set(['DELIVERED', 'CANCELLED']);

const SHIPMENT_CANCEL_REASON = 'OPERATIONS_DISPATCH_SHIPMENT_CANCEL';
const EXTERNAL_BOOKING_ACCEPT_REASON = 'OPERATIONS_DISPATCH_EXTERNAL_BOOKING_ACCEPT';
const EXTERNAL_BOOKING_ABANDON_REASON = 'OPERATIONS_DISPATCH_EXTERNAL_BOOKING_ABANDON';
const BULK_ASSIGN_REASON = 'OPERATIONS_DISPATCH_BULK_ASSIGN';

/** One selected plan's own outcome from a bulk-assign submission (gap map row 3.1) — the same "per-item outcome" shape the orders bulk panel renders. */
export interface BulkAssignOutcome {
  readonly planId: string;
  readonly applied: boolean;
  readonly reason?: string | null;
}

/** Same cadence as the order and kitchen boards, until ADR 0045 live updates exist. */
const POLL_INTERVAL_MS = 10_000;

/** `q-drag-drop-assign`'s pool column — a plan with no courier lands here. */
const UNASSIGNED_COLUMN_ID = '__unassigned__';

const OPEN_STATUSES: ReadonlySet<string> = new Set([
  'PLANNED',
  'WAITING_TO_SOURCE',
  'SOURCING',
  'BOOKING',
  'RETRY_PENDING',
  'SCHEDULED',
  'MANUAL_ACTION_REQUIRED',
]);

/**
 * The dispatch board — IA §3.1, courier-keyed columns this wave (`P18`).
 *
 * **Drag replaces the per-row picker.** `q-drag-drop-assign` (`X.22`, built
 * on `q-board`/`q-board-column`/`q-board-card`, `X.21`) owns the drag
 * lifecycle; this page supplies `assignFn`/`unassignFn` (each a thin wrapper
 * over `DispatchApi`, carrying the optimistic `expectedVersion` off the plan
 * or shipment at drop time) and reads the result back only to decide what to
 * tell the operator. `DispatchController.assign`/`unassign` are a compare-
 * and-set single winner: a second dispatcher's drop can lose to one that
 * already changed the version, and that refusal is surfaced —
 * `q-drag-drop-assign`'s own alert plus this page's `notice` band — never a
 * silent revert (see `DragDropAssign`'s own doc for why that distinction is
 * the point).
 *
 * **`GET .../exceptions` is called here for the first time.** A
 * `MANUAL_ACTION_REQUIRED` plan used to show that status word and nothing
 * else; `refresh` now fetches the open exceptions for every such plan and
 * `exceptionSummary` puts the first one's own reason on the card.
 *
 * **«Вызвать курьера» (gap map row 1.2f).** Offered on every unassigned
 * pool card, reusing `q-external-courier-dialog` verbatim — the same
 * component `order-detail-pane.ts` opens, over `DispatchApi`'s own
 * plan-keyed `externalPartners`/`externalQuote`/`externalBook` rather than
 * the order-keyed path that pane uses, since this board already has the
 * `planId` at hand and no reason to resolve an order first. A card carrying
 * a `PARTNER` shipment renders the provider's own booking state
 * ({@link externalProviderLabel}) the same way that pane's § Курьер panel
 * does.
 *
 * **Cancel shipment (gap map row 1.2g).** `DispatchController`'s dedicated
 * `POST .../shipments/{shipmentId}/cancel` had no console caller anywhere —
 * offered on every carried card that is not already `DELIVERED`/`CANCELLED`,
 * gated behind `q-confirm-dialog` the same way an irreversible-feeling action
 * is gated elsewhere in this console (`menus-page.ts`'s own bulk actions).
 *
 * **Bulk assignment (gap map row 3.1).** Selecting several *unassigned* pool
 * cards and one courier assigns every selected plan to that courier in one
 * click, one `DispatchApi.assign` call per plan (no bulk endpoint exists —
 * `DispatchController` has none, and orders' own bulk-action endpoint states
 * explicitly that "bulk courier assignment is out of scope"). Per-item
 * outcomes render the same shape the orders bulk panel uses: applied vs.
 * refused, with the server's own reason.
 *
 * **Still reduced relative to the spec, deliberately.** No live map (`X.4`),
 * no customer name or non-PII destination label on a card —
 * `PlanQueueResponse` carries neither today (see `dispatch-api.ts`'s own
 * doc); a masked zone/street projection needs a new server-side field this
 * wave did not build (see the gap map's own row `3.1` audit note).
 */
@Component({
  selector: 'q-dispatch-board-page',
  imports: [TPipe, DragDropAssign, QBoardCardDef, StatusPill, ConfirmDialog, ExternalCourierDialog],
  templateUrl: './dispatch-board-page.html',
  styleUrl: './dispatch-board-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DispatchBoardPage implements OnInit {
  private readonly api = inject(ApiClient);
  private readonly dispatch = inject(DispatchApi);
  private readonly couriersApi = inject(CouriersApi);
  private readonly location = inject(CurrentLocation);
  private readonly i18n = inject(I18n);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly UNASSIGNED_COLUMN_ID = UNASSIGNED_COLUMN_ID;

  protected readonly firstLoadComplete = signal(false);
  protected readonly denied = signal(false);
  protected readonly lastError = signal<ApiError | null>(null);

  protected readonly plans = signal<readonly PlanQueueResponse[]>([]);
  protected readonly ordersByOrderId = signal<ReadonlyMap<string, OrderSummaryResponse>>(new Map());
  protected readonly fleet = signal<readonly RosterEntryResponse[]>([]);
  protected readonly exceptionsByPlanId = signal<ReadonlyMap<string, readonly ExceptionResponse[]>>(
    new Map(),
  );

  protected readonly busyPlanIds = signal<ReadonlySet<string>>(new Set());
  protected readonly notice = signal<string | null>(null);

  /** «Вызвать курьера» (gap map row 1.2f) — the plan currently offered the dialog, or `null` while closed. */
  protected readonly externalCourierPlan = signal<PlanQueueResponse | null>(null);
  protected readonly externalPartnersForPlan = signal<readonly ExternalPartnerResponse[]>([]);
  protected readonly externalQuoteForPlan = signal<ExternalQuoteResponse | null>(null);
  protected readonly externalCourierBusy = signal(false);

  /** Cancel shipment (gap map row 1.2g) — the plan whose confirm dialog is open, or `null` while closed. */
  protected readonly cancelShipmentPlan = signal<PlanQueueResponse | null>(null);
  protected readonly cancellingShipment = signal(false);

  /** Bulk assignment (gap map row 3.1) — pool cards the dispatcher has selected for one courier. */
  protected readonly selectedPlanIds = signal<ReadonlySet<string>>(new Set());
  protected readonly bulkCourierId = signal<string | null>(null);
  protected readonly bulkAssigning = signal(false);
  protected readonly bulkResult = signal<readonly BulkAssignOutcome[] | null>(null);

  private pollHandle: ReturnType<typeof setInterval> | null = null;

  /** One column per courier, plus the unassigned pool — the drop target's own load (§3.1). */
  protected readonly boardColumns = computed<readonly DragDropAssignColumn<RosterEntryResponse>[]>(
    () => [
      {
        columnId: UNASSIGNED_COLUMN_ID,
        titleText: this.i18n.t('delivery.dispatch.column.unassigned'),
        capacity: null,
        current: this.plans().filter((plan) => !plan.shipment).length,
      },
      ...this.fleet().map((courier) => {
        const ineligibleReason = this.courierIneligibleReason(courier);
        return {
          columnId: courier.courierId,
          titleText: courier.displayReference,
          current: courier.activeAssignments,
          capacity: courier.concurrencyCeiling,
          data: courier,
          dropDisabled: ineligibleReason !== null,
          dropDisabledReason: ineligibleReason ?? undefined,
        };
      }),
    ],
  );

  /**
   * `null` when a plan may be dropped onto this courier; otherwise the
   * reason shown on the column so a dispatcher sees why before dragging
   * (P18 second-pass adversarial review). Server-side, `DispatchApi.assign`
   * is refused independently with `COURIER_NOT_ELIGIBLE` — this is defense
   * in depth for the drag gesture itself, never the only gate.
   */
  private courierIneligibleReason(courier: RosterEntryResponse): string | null {
    if (courier.engagementStatus === 'SUSPENDED_COMPLIANCE') {
      return this.i18n.t('delivery.dispatch.column.ineligible', {
        reason: this.i18n.t('couriers.engagement.status.SUSPENDED_COMPLIANCE'),
      });
    }
    if (courier.engagementStatus === 'SUSPENDED_OPERATIONAL') {
      return this.i18n.t('delivery.dispatch.column.ineligible', {
        reason: this.i18n.t('couriers.engagement.status.SUSPENDED_OPERATIONAL'),
      });
    }
    if (courier.engagementStatus === 'ENDED') {
      return this.i18n.t('delivery.dispatch.column.ineligible', {
        reason: this.i18n.t('couriers.engagement.status.ENDED'),
      });
    }
    if (courier.warningState === 'LAPSED') {
      return this.i18n.t('delivery.dispatch.column.ineligible', {
        reason: this.i18n.t('couriers.warningState.LAPSED'),
      });
    }
    return null;
  }

  /** Soonest source-at first — same order the old table used, now the order cards fall into a column in. */
  protected readonly sortedPlans = computed<readonly PlanQueueResponse[]>(() =>
    this.plans()
      .slice()
      .sort((a, b) => new Date(a.sourceAt).getTime() - new Date(b.sourceAt).getTime()),
  );

  protected readonly cardId = (plan: PlanQueueResponse): string => plan.planId;
  protected readonly columnIdOf = (plan: PlanQueueResponse): string =>
    plan.shipment?.courierId ?? UNASSIGNED_COLUMN_ID;
  protected readonly draggable = (plan: PlanQueueResponse): boolean =>
    plan.shipment ? true : this.isOpen(plan);

  /** Handed to `q-drag-drop-assign`; `plan.version` is the optimistic CAS token off the card at drop time. */
  protected readonly assignFn = async (
    plan: PlanQueueResponse,
    courierId: string,
  ): Promise<DragDropAssignOutcome> => {
    const scope = this.location.scope();
    if (!scope) {
      return { applied: false, reason: null };
    }
    try {
      const result = await this.dispatch.assign(
        scope,
        plan.planId,
        courierId,
        plan.version,
        'OPERATIONS_MANUAL_ASSIGN',
      );
      return { applied: result.applied, reason: result.reason ?? null };
    } catch (error) {
      this.notice.set(this.describe(error));
      return { applied: false, reason: null };
    } finally {
      await this.refresh();
    }
  };

  /** Handed to `q-drag-drop-assign` for a drop onto the pool; also reused by {@link manualUnassign}. */
  protected readonly unassignFn = async (
    plan: PlanQueueResponse,
  ): Promise<DragDropAssignOutcome> => {
    const scope = this.location.scope();
    if (!scope || !plan.shipment) {
      return { applied: false, reason: null };
    }
    try {
      const result = await this.dispatch.unassign(
        scope,
        plan.planId,
        plan.shipment.version,
        'OPERATIONS_UNASSIGN',
      );
      return { applied: result.applied, reason: result.reason ?? null };
    } catch (error) {
      this.notice.set(this.describe(error));
      return { applied: false, reason: null };
    } finally {
      await this.refresh();
    }
  };

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
      const [plans, fleet, orders] = await Promise.all([
        this.dispatch.queue(scope),
        this.couriersApi.roster(scope.tenantId),
        firstValueFrom(
          this.api.get<readonly OrderSummaryResponse[]>(operationsPaths.orders(scope), {
            params: { limit: 200 },
          }),
        ),
      ]);
      this.plans.set(plans);
      this.fleet.set(fleet);
      this.ordersByOrderId.set(
        new Map((orders.value ?? []).map((order) => [order.orderId, order])),
      );
      // A plan that assigned, was cancelled, or simply left the queue on this
      // refresh is pruned from the bulk selection the same way order-queue's
      // own selection survives a refetch — never a stale id carried forward
      // into the next bulk submission.
      this.selectedPlanIds.update((selected) =>
        pruneSelection(
          selected,
          new Set(plans.filter((plan) => this.isBulkSelectable(plan)).map((plan) => plan.planId)),
        ),
      );
      await this.loadExceptions(scope, plans);
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

  /**
   * `GET .../exceptions` (built by `DispatchController`, never called before
   * this wave) — only for the plans that need it, since it is one request
   * per plan and most plans are never `MANUAL_ACTION_REQUIRED`.
   */
  private async loadExceptions(
    scope: LocationScope,
    plans: readonly PlanQueueResponse[],
  ): Promise<void> {
    const needing = plans.filter((plan) => plan.status === 'MANUAL_ACTION_REQUIRED');
    if (needing.length === 0) {
      this.exceptionsByPlanId.set(new Map());
      return;
    }
    const results = await Promise.all(
      needing.map((plan) =>
        this.dispatch.exceptions(scope, plan.planId).catch((): readonly ExceptionResponse[] => []),
      ),
    );
    this.exceptionsByPlanId.set(new Map(needing.map((plan, i) => [plan.planId, results[i]])));
  }

  protected manualRefresh(): void {
    void this.refresh();
  }

  protected orderFor(plan: PlanQueueResponse): OrderSummaryResponse | null {
    return this.ordersByOrderId().get(plan.orderId) ?? null;
  }

  protected isOpen(plan: PlanQueueResponse): boolean {
    return OPEN_STATUSES.has(plan.status) && !plan.shipment;
  }

  protected planStatusLabel(status: string): string {
    switch (status) {
      case 'PLANNED':
        return this.i18n.t('delivery.dispatch.status.PLANNED');
      case 'WAITING_TO_SOURCE':
        return this.i18n.t('delivery.dispatch.status.WAITING_TO_SOURCE');
      case 'SOURCING':
        return this.i18n.t('delivery.dispatch.status.SOURCING');
      case 'BOOKING':
        return this.i18n.t('delivery.dispatch.status.BOOKING');
      case 'RETRY_PENDING':
        return this.i18n.t('delivery.dispatch.status.RETRY_PENDING');
      case 'SCHEDULED':
        return this.i18n.t('delivery.dispatch.status.SCHEDULED');
      case 'ASSIGNED':
        return this.i18n.t('delivery.dispatch.status.ASSIGNED');
      case 'IN_PROGRESS':
        return this.i18n.t('delivery.dispatch.status.IN_PROGRESS');
      case 'COMPLETED':
        return this.i18n.t('delivery.dispatch.status.COMPLETED');
      case 'MANUAL_ACTION_REQUIRED':
        return this.i18n.t('delivery.dispatch.status.MANUAL_ACTION_REQUIRED');
      case 'CANCELLED':
        return this.i18n.t('delivery.dispatch.status.CANCELLED');
      default:
        return status;
    }
  }

  protected planStatusTone(status: string): StatusTone {
    switch (status) {
      case 'MANUAL_ACTION_REQUIRED':
        return 'danger';
      case 'ASSIGNED':
      case 'IN_PROGRESS':
        return 'info';
      case 'COMPLETED':
        return 'success';
      default:
        return 'none';
    }
  }

  /** The first open exception's own reason — why a `MANUAL_ACTION_REQUIRED` plan needs a human. */
  protected exceptionSummary(plan: PlanQueueResponse): string | null {
    if (plan.status !== 'MANUAL_ACTION_REQUIRED') {
      return null;
    }
    const first = this.exceptionsByPlanId().get(plan.planId)?.[0];
    return first ? (first.detail ?? first.reasonCode) : null;
  }

  protected isBusy(plan: PlanQueueResponse): boolean {
    return this.busyPlanIds().has(plan.planId);
  }

  /** Keyboard-accessible fallback for a carried plan — dragging is not the only way to release it. */
  protected async manualUnassign(plan: PlanQueueResponse): Promise<void> {
    this.setBusy(plan.planId, true);
    try {
      const outcome = await this.unassignFn(plan);
      if (!outcome.applied) {
        this.notice.set(
          this.i18n.t('delivery.dispatch.conflict', { reason: outcome.reason ?? '' }),
        );
      }
    } finally {
      this.setBusy(plan.planId, false);
    }
  }

  protected onRejected(rejection: DragDropAssignRejection<PlanQueueResponse>): void {
    this.notice.set(this.i18n.t('delivery.dispatch.conflict', { reason: rejection.reason ?? '' }));
  }

  protected dismissNotice(): void {
    this.notice.set(null);
  }

  // ------------------------------------------------------------ §1.2f «Вызвать курьера»

  /**
   * Opens the Millenium-pattern confirmation dialog on a pool card (gap map
   * row 1.2f) — the same `q-external-courier-dialog` `order-detail-pane.ts`
   * opens, over `DispatchApi`'s own plan-keyed endpoints. Fetches the
   * branch's external partners fresh every time, since a binding disabled
   * between two plans must not offer a partner that can no longer be booked.
   */
  protected async openExternalCourierDialog(plan: PlanQueueResponse): Promise<void> {
    const scope = this.location.scope();
    if (!scope) {
      return;
    }
    this.externalQuoteForPlan.set(null);
    this.externalCourierPlan.set(plan);
    this.externalPartnersForPlan.set(await this.dispatch.externalPartners(scope, plan.planId));
  }

  protected async requestExternalQuoteForPlan(bindingId: string): Promise<void> {
    const scope = this.location.scope();
    const plan = this.externalCourierPlan();
    if (!scope || !plan) {
      return;
    }
    this.externalCourierBusy.set(true);
    try {
      this.externalQuoteForPlan.set(
        await this.dispatch.externalQuote(scope, plan.planId, bindingId),
      );
    } catch (error) {
      this.notice.set(this.describe(error));
    } finally {
      this.externalCourierBusy.set(false);
    }
  }

  protected async acceptExternalBookingForPlan(
    submission: ExternalBookingSubmission,
  ): Promise<void> {
    await this.settleExternalBookingForPlan(submission, 'ACCEPT', EXTERNAL_BOOKING_ACCEPT_REASON);
  }

  protected async abandonExternalBookingForPlan(
    submission: ExternalBookingSubmission,
  ): Promise<void> {
    await this.settleExternalBookingForPlan(submission, 'ABANDON', EXTERNAL_BOOKING_ABANDON_REASON);
  }

  private async settleExternalBookingForPlan(
    submission: ExternalBookingSubmission,
    decision: 'ACCEPT' | 'ABANDON',
    reasonCode: string,
  ): Promise<void> {
    const scope = this.location.scope();
    const plan = this.externalCourierPlan();
    if (!scope || !plan) {
      return;
    }
    this.externalCourierBusy.set(true);
    try {
      const result = await this.dispatch.externalBook(
        scope,
        plan.planId,
        submission.bindingId,
        submission.quoteId,
        decision,
        reasonCode,
      );
      if (!result.applied && result.reason) {
        this.notice.set(this.i18n.t('orders.detail.courier.refused', { reason: result.reason }));
      }
      this.dismissExternalCourierDialog();
      await this.refresh();
    } catch (error) {
      this.notice.set(this.describe(error));
    } finally {
      this.externalCourierBusy.set(false);
    }
  }

  protected dismissExternalCourierDialog(): void {
    this.externalCourierPlan.set(null);
    this.externalPartnersForPlan.set([]);
    this.externalQuoteForPlan.set(null);
  }

  /** The provider's own booking state (gap map row 1.2f) — `null` for an `INTERNAL` shipment or no shipment at all. */
  protected externalProviderLabel(plan: PlanQueueResponse): string | null {
    if (plan.shipment?.sourceType !== 'PARTNER') {
      return null;
    }
    return this.i18n.t('orders.detail.courier.external', {
      status: this.shipmentStatusLabel(plan.shipment.status),
    });
  }

  private shipmentStatusLabel(status: string): string {
    const key = SHIPMENT_STATUS_LABEL_KEYS[status];
    return key ? this.i18n.t(key) : status;
  }

  // ------------------------------------------------------------ §1.2g cancel shipment

  /** Whether this card's shipment may be cancelled at all — a `DELIVERED`/`CANCELLED` one is already settled. */
  protected canCancelShipment(plan: PlanQueueResponse): boolean {
    return !!plan.shipment && !SHIPMENT_CANCEL_TERMINAL_STATUSES.has(plan.shipment.status);
  }

  protected openCancelShipmentDialog(plan: PlanQueueResponse): void {
    this.cancelShipmentPlan.set(plan);
  }

  protected dismissCancelShipmentDialog(): void {
    if (this.cancellingShipment()) {
      return;
    }
    this.cancelShipmentPlan.set(null);
  }

  /**
   * The dedicated, provider-notifying cancel (gap map row 1.2g) —
   * `DispatchController.cancelShipment`'s first console caller. Distinct
   * from unassign: a `PARTNER` shipment's provider is actually called, and
   * an uncertain or chargeable answer opens `fulfillment.delivery_exceptions`,
   * which `refresh()`'s own `loadExceptions` picks straight back up.
   */
  protected async confirmCancelShipment(): Promise<void> {
    const scope = this.location.scope();
    const plan = this.cancelShipmentPlan();
    if (!scope || !plan?.shipment) {
      return;
    }
    this.cancellingShipment.set(true);
    try {
      const result = await this.dispatch.cancelShipment(
        scope,
        plan.shipment.shipmentId,
        plan.shipment.version,
        SHIPMENT_CANCEL_REASON,
      );
      if (!result.applied) {
        this.notice.set(
          this.i18n.t('delivery.dispatch.conflict', { reason: result.conflictReason ?? '' }),
        );
      }
      this.cancelShipmentPlan.set(null);
      await this.refresh();
    } catch (error) {
      this.notice.set(this.describe(error));
    } finally {
      this.cancellingShipment.set(false);
    }
  }

  // ------------------------------------------------------------ §3.1 bulk assignment

  /** Only an unassigned, open plan may be bulk-selected — a carried or terminal plan has nothing a bulk assign could do to it. */
  protected isBulkSelectable(plan: PlanQueueResponse): boolean {
    return this.isOpen(plan);
  }

  protected isPlanSelected(plan: PlanQueueResponse): boolean {
    return this.selectedPlanIds().has(plan.planId);
  }

  protected toggleBulkSelection(plan: PlanQueueResponse): void {
    if (!this.isBulkSelectable(plan)) {
      return;
    }
    this.selectedPlanIds.update((selected) => toggleOne(selected, plan.planId));
  }

  protected clearBulkSelection(): void {
    this.selectedPlanIds.set(new Set());
    this.bulkResult.set(null);
  }

  protected selectedCount(): number {
    return this.selectedPlanIds().size;
  }

  protected setBulkCourier(courierId: string | null): void {
    this.bulkCourierId.set(courierId);
  }

  protected canSubmitBulkAssign(): boolean {
    return !this.bulkAssigning() && this.selectedCount() > 0 && this.bulkCourierId() !== null;
  }

  /**
   * One `DispatchApi.assign` per selected plan (gap map row 3.1) — no bulk
   * endpoint exists on `DispatchController`, and orders' own bulk-action
   * endpoint states explicitly that bulk courier assignment is out of scope
   * (`order-bulk-actions-api.ts`'s own doc). Per-item outcomes render the
   * same applied/refused shape the orders bulk panel uses; a version raced
   * out from under a card between selection and submission is reported as
   * that item's own refusal, never a batch-wide failure.
   */
  protected async submitBulkAssign(): Promise<void> {
    const scope = this.location.scope();
    const courierId = this.bulkCourierId();
    if (!scope || !courierId || !this.canSubmitBulkAssign()) {
      return;
    }
    const targets = this.plans().filter((plan) => this.selectedPlanIds().has(plan.planId));
    if (targets.length === 0) {
      return;
    }
    this.bulkAssigning.set(true);
    this.bulkResult.set(null);
    try {
      const outcomes: BulkAssignOutcome[] = [];
      for (const plan of targets) {
        try {
          const result = await this.dispatch.assign(
            scope,
            plan.planId,
            courierId,
            plan.version,
            BULK_ASSIGN_REASON,
          );
          outcomes.push({ planId: plan.planId, applied: result.applied, reason: result.reason });
        } catch (error) {
          outcomes.push({
            planId: plan.planId,
            applied: false,
            reason: error instanceof ApiError ? error.code : 'UNKNOWN',
          });
        }
      }
      this.bulkResult.set(outcomes);
      this.selectedPlanIds.set(new Set());
      await this.refresh();
    } finally {
      this.bulkAssigning.set(false);
    }
  }

  protected dismissBulkResult(): void {
    this.bulkResult.set(null);
  }

  protected bulkAppliedCount(): number {
    return (this.bulkResult() ?? []).filter((outcome) => outcome.applied).length;
  }

  protected bulkFailedCount(): number {
    return (this.bulkResult() ?? []).filter((outcome) => !outcome.applied).length;
  }

  protected courierOptions(): readonly { id: string; label: string }[] {
    return this.fleet().map((entry) => ({ id: entry.courierId, label: entry.displayReference }));
  }

  private setBusy(planId: string, busy: boolean): void {
    this.busyPlanIds.update((current) => {
      const next = new Set(current);
      if (busy) {
        next.add(planId);
      } else {
        next.delete(planId);
      }
      return next;
    });
  }

  private describe(error: unknown): string {
    return error instanceof ApiError
      ? describeApiError(error, (key, values) => this.i18n.t(key, values))
      : this.i18n.t('error.unknown.noReference');
  }
}
