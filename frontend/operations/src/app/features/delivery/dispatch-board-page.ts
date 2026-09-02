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
import { CouriersApi, RosterEntryResponse } from '../couriers/couriers-api';
import { describeApiError } from '../orders/order-errors';
import { OrderSummaryResponse } from '../orders/order-summary';
import { DispatchApi, PlanQueueResponse } from './dispatch-api';

/** Same cadence as the order and kitchen boards, until ADR 0045 live updates exist. */
const POLL_INTERVAL_MS = 10_000;

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
 * The dispatch board — IA §3.1.
 *
 * **Built, greenfield this wave.** No manual-assign backend existed before
 * `DispatchController` (see the wave's final report): the data model
 * (`delivery_plans`/`shipments`/`assignment_attempts`) and automated sourcing
 * were already real, but `Capability.DELIVERY_MANUAL_ASSIGN` had zero
 * implementing controllers. This screen is the queue (`GET .../dispatch/queue`)
 * plus assign/unassign, joined client-side against the order board's own
 * `OrderSummaryResponse` read (§1.1) by `orderId` — the same seam
 * `KitchenQueue` uses for order lines, since `PlanQueueResponse` carries no
 * customer-facing field either.
 *
 * **Reduced relative to the spec, deliberately.** No drag-and-drop:
 * `DragDropAssign` is IA Part 4's own wave-2 component, not a pilot blocker —
 * assignment here is a courier picker per row. No live map, no "call an
 * external courier" (partner booking is ADR 0014's automatic sourcing's job,
 * not this manual surface's), no bulk actions, no cascade/simultaneous
 * multi-provider search (§3.8, not built at all — see `delivery-shell.ts`).
 */
@Component({
  selector: 'q-dispatch-board-page',
  imports: [TPipe],
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

  protected readonly firstLoadComplete = signal(false);
  protected readonly denied = signal(false);
  protected readonly lastError = signal<ApiError | null>(null);

  protected readonly plans = signal<readonly PlanQueueResponse[]>([]);
  protected readonly ordersByOrderId = signal<ReadonlyMap<string, OrderSummaryResponse>>(new Map());
  protected readonly fleet = signal<readonly RosterEntryResponse[]>([]);

  protected readonly pickedCourierByPlanId = signal<ReadonlyMap<string, string>>(new Map());
  protected readonly busyPlanIds = signal<ReadonlySet<string>>(new Set());
  protected readonly notice = signal<string | null>(null);

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

  protected manualRefresh(): void {
    void this.refresh();
  }

  protected sortedPlans(): readonly PlanQueueResponse[] {
    return this.plans()
      .slice()
      .sort((a, b) => new Date(a.sourceAt).getTime() - new Date(b.sourceAt).getTime());
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

  protected courierReference(courierId: string): string {
    return (
      this.fleet().find((courier) => courier.courierId === courierId)?.displayReference ?? courierId
    );
  }

  protected pickedCourierFor(plan: PlanQueueResponse): string {
    return this.pickedCourierByPlanId().get(plan.planId) ?? this.fleet()[0]?.courierId ?? '';
  }

  protected onPickCourier(plan: PlanQueueResponse, courierId: string): void {
    this.pickedCourierByPlanId.update((current) => new Map(current).set(plan.planId, courierId));
  }

  protected isBusy(plan: PlanQueueResponse): boolean {
    return this.busyPlanIds().has(plan.planId);
  }

  protected async assign(plan: PlanQueueResponse): Promise<void> {
    const scope = this.location.scope();
    const courierId = this.pickedCourierFor(plan);
    if (!scope || !courierId) {
      return;
    }
    this.setBusy(plan.planId, true);
    try {
      const result = await this.dispatch.assign(
        scope,
        plan.planId,
        courierId,
        plan.version,
        'OPERATIONS_MANUAL_ASSIGN',
      );
      if (!result.applied) {
        this.notice.set(this.i18n.t('delivery.dispatch.conflict', { reason: result.reason ?? '' }));
      }
      await this.refresh();
    } catch (error) {
      this.notice.set(this.describe(error));
    } finally {
      this.setBusy(plan.planId, false);
    }
  }

  protected async unassign(plan: PlanQueueResponse): Promise<void> {
    const scope = this.location.scope();
    if (!scope || !plan.shipment) {
      return;
    }
    this.setBusy(plan.planId, true);
    try {
      const result = await this.dispatch.unassign(
        scope,
        plan.planId,
        plan.shipment.version,
        'OPERATIONS_UNASSIGN',
      );
      if (!result.applied) {
        this.notice.set(this.i18n.t('delivery.dispatch.conflict', { reason: result.reason ?? '' }));
      }
      await this.refresh();
    } catch (error) {
      this.notice.set(this.describe(error));
    } finally {
      this.setBusy(plan.planId, false);
    }
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
