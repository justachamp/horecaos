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
import { TPipe } from '../../core/i18n/t.pipe';
import { DragDropAssign, QBoardCardDef } from '../../shared/ui/drag-drop-assign/drag-drop-assign';
import {
  DragDropAssignColumn,
  DragDropAssignOutcome,
  DragDropAssignRejection,
} from '../../shared/ui/drag-drop-assign/drag-drop-assign-types';
import { StatusPill, StatusTone } from '../../shared/ui/status-pill';
import { CouriersApi, RosterEntryResponse } from '../couriers/couriers-api';
import { describeApiError } from '../orders/order-errors';
import { OrderSummaryResponse } from '../orders/order-summary';
import { DispatchApi, ExceptionResponse, PlanQueueResponse } from './dispatch-api';

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
 * **Still reduced relative to the spec, deliberately.** No live map (`X.4`),
 * no "call an external courier" (`P44`), no bulk actions, no customer name
 * or address on a card (`PlanQueueResponse` carries neither — see
 * `dispatch-api.ts`'s own doc).
 */
@Component({
  selector: 'q-dispatch-board-page',
  imports: [TPipe, DragDropAssign, QBoardCardDef, StatusPill],
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
