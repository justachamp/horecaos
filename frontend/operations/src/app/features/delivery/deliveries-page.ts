import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';

import { CursorState, PAGE_DEFAULT_LIMIT, firstPage } from '../../core/api/page';
import { ApiError } from '../../core/api/problem-details';
import { CurrentLocation } from '../../core/auth/current-location';
import { TimeZone, formatDateTime } from '../../core/format/datetime';
import { formatMoney } from '../../core/format/money';
import { I18n } from '../../core/i18n/i18n';
import { MessageKey } from '../../core/i18n/messages.en';
import { TPipe } from '../../core/i18n/t.pipe';
import { CouriersApi, RosterEntryResponse } from '../couriers/couriers-api';
import { describeApiError } from '../orders/order-errors';
import {
  DELIVERY_STATES,
  DeliveriesApi,
  DeliveryCost,
  DeliveryFilters,
  DeliveryRow,
} from './deliveries-api';

/** See `dispatch-board-page.ts`'s identical constant: no location carries a timezone on a response this list reads. */
const PLACEHOLDER_TIME_ZONE: TimeZone = 'Asia/Tashkent';

const STATE_LABELS: Readonly<Record<string, MessageKey>> = {
  PLANNED: 'delivery.deliveries.state.PLANNED',
  WAITING_TO_SOURCE: 'delivery.deliveries.state.WAITING_TO_SOURCE',
  SOURCING: 'delivery.deliveries.state.SOURCING',
  BOOKING: 'delivery.deliveries.state.BOOKING',
  RETRY_PENDING: 'delivery.deliveries.state.RETRY_PENDING',
  SCHEDULED: 'delivery.deliveries.state.SCHEDULED',
  ASSIGNED: 'delivery.deliveries.state.ASSIGNED',
  IN_PROGRESS: 'delivery.deliveries.state.IN_PROGRESS',
  COMPLETED: 'delivery.deliveries.state.COMPLETED',
  MANUAL_ACTION_REQUIRED: 'delivery.deliveries.state.MANUAL_ACTION_REQUIRED',
  CANCELLED: 'delivery.deliveries.state.CANCELLED',
};

const BASIS_LABELS: Readonly<Record<string, MessageKey>> = {
  ACCRUED: 'delivery.deliveries.basis.ACCRUED',
  INVOICED: 'delivery.deliveries.basis.INVOICED',
  SETTLED: 'delivery.deliveries.basis.SETTLED',
};

/**
 * The deliveries list (ADR 0164 slice two, gap map row 3.1b): every delivery the tenant has had, newest first.
 *
 * The dispatch board is a branch's open plans; this is the record behind it, for a courier-service tenant whose
 * work is the deliveries themselves (it is the landing route of the deliveries profile, ADR 0164 Decision 6). One
 * row per plan with its history, filterable by branch, state, courier and date, paged by cursor. **A row opens the
 * order's detail pane** (`/orders/:orderId`), which is where the recipient's authorised reveal lives; the list
 * itself carries no name, number or address, only the non-PII destination label ordering projects.
 *
 * **Its scope follows its filters** (ADR 0144). With no branch chosen the call is tenant-wide; a dispatcher who
 * holds the read at one branch only is refused that, and the page falls back to their own branch and says so
 * rather than showing a denial they did nothing to earn. The cost is shown at its basis (accrued, invoiced or
 * settled) and never added to another basis (ADR 0042).
 */
@Component({
  selector: 'q-deliveries-page',
  imports: [TPipe, RouterLink],
  templateUrl: './deliveries-page.html',
  styleUrl: './deliveries-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DeliveriesPage implements OnInit {
  private readonly api = inject(DeliveriesApi);
  private readonly couriers = inject(CouriersApi);
  private readonly location = inject(CurrentLocation);
  protected readonly i18n = inject(I18n);

  protected readonly states = DELIVERY_STATES;

  protected readonly loading = signal(true);
  protected readonly loadingMore = signal(false);
  protected readonly denied = signal(false);
  protected readonly error = signal<string | null>(null);
  /** True when the tenant-wide read was refused and this is the operator's own branch instead. */
  protected readonly ownBranchOnly = signal(false);

  protected readonly rows = signal<readonly DeliveryRow[]>([]);
  protected readonly nextCursor = signal<string | null>(null);
  protected readonly roster = signal<readonly RosterEntryResponse[]>([]);

  protected readonly branchId = signal('');
  protected readonly stateFilter = signal('');
  protected readonly courierId = signal('');
  protected readonly fromDate = signal('');
  protected readonly toDate = signal('');

  private cursorState: CursorState = firstPage(PAGE_DEFAULT_LIMIT);

  async ngOnInit(): Promise<void> {
    await this.location.ensureLoaded();
    const scope = this.location.scope();
    if (!scope) {
      this.denied.set(this.location.denied());
      this.loading.set(false);
      return;
    }
    // The roster names couriers by reference; without courier.read the column falls back to a short id.
    void this.couriers
      .roster(scope.tenantId)
      .then((roster) => this.roster.set(roster))
      .catch(() => undefined);
    await this.reload();
  }

  protected branches(): readonly { readonly id: string; readonly displayName: string }[] {
    return this.location.options();
  }

  /** Any filter changed: the old cursor belongs to the old filters, so the list starts again. */
  protected async changeFilter(): Promise<void> {
    await this.reload();
  }

  protected async reload(): Promise<void> {
    this.cursorState = firstPage(PAGE_DEFAULT_LIMIT);
    this.rows.set([]);
    this.nextCursor.set(null);
    this.loading.set(true);
    this.error.set(null);
    try {
      const page = await this.fetch(this.cursorState);
      this.rows.set(page.items);
      this.nextCursor.set(page.nextCursor);
    } catch (failure) {
      this.handle(failure);
    } finally {
      this.loading.set(false);
    }
  }

  protected async loadMore(): Promise<void> {
    const cursor = this.nextCursor();
    if (cursor === null || this.loadingMore()) {
      return;
    }
    this.loadingMore.set(true);
    try {
      const page = await this.fetch({ ...this.cursorState, cursor });
      this.rows.update((current) => [...current, ...page.items]);
      this.nextCursor.set(page.nextCursor);
    } catch (failure) {
      this.handle(failure);
    } finally {
      this.loadingMore.set(false);
    }
  }

  private async fetch(state: CursorState): ReturnType<DeliveriesApi['list']> {
    const scope = this.location.scope();
    if (!scope) {
      throw new Error('no scope');
    }
    const filters = this.filters();
    try {
      return await this.api.list(scope.tenantId, filters, state);
    } catch (failure) {
      // A branch-level reader is refused the tenant's list: show their own branch instead, and say so.
      if (
        failure instanceof ApiError &&
        failure.status === 403 &&
        filters.locationId === undefined
      ) {
        this.ownBranchOnly.set(true);
        return this.api.list(
          scope.tenantId,
          { ...filters, brandId: scope.brandId, locationId: scope.locationId },
          state,
        );
      }
      throw failure;
    }
  }

  private filters(): DeliveryFilters {
    const scope = this.location.scope();
    const branch = this.branchId();
    return {
      ...(branch !== '' && scope ? { brandId: scope.brandId, locationId: branch } : {}),
      ...(this.stateFilter() !== '' ? { states: [this.stateFilter()] } : {}),
      ...(this.courierId() !== '' ? { courierId: this.courierId() } : {}),
      ...(this.fromDate() !== '' ? { from: startOfDay(this.fromDate()) } : {}),
      ...(this.toDate() !== '' ? { to: startOfNextDay(this.toDate()) } : {}),
    };
  }

  private handle(failure: unknown): void {
    if (failure instanceof ApiError && failure.status === 403) {
      this.denied.set(true);
      return;
    }
    this.error.set(
      failure instanceof ApiError
        ? describeApiError(failure, (key, values) => this.i18n.t(key, values))
        : this.i18n.t('error.unknown.noReference'),
    );
  }

  // ------------------------------------------------------------- presentation

  protected stateLabel(state: string): string {
    const key = STATE_LABELS[state];
    return key ? this.i18n.t(key) : state;
  }

  /** The in-house courier by reference, a partner by its type, or nobody yet. */
  protected carrier(row: DeliveryRow): string {
    if (row.courierId) {
      const found = this.roster().find((courier) => courier.courierId === row.courierId);
      return found ? found.displayReference : row.courierId.slice(0, 8);
    }
    if (row.providerType) {
      return row.providerType;
    }
    return '—';
  }

  protected time(instant: string | null | undefined): string {
    return instant ? formatDateTime(new Date(instant), PLACEHOLDER_TIME_ZONE) : '—';
  }

  protected money(minor: number | null | undefined, currency: string): string {
    return minor === null || minor === undefined
      ? '—'
      : formatMoney({ amountMinor: minor, currency }, this.i18n.locale(), { withUnit: true });
  }

  protected cost(cost: DeliveryCost | null | undefined): string {
    if (!cost) {
      return '—';
    }
    const basis = BASIS_LABELS[cost.basis];
    return `${this.money(cost.amountMinor, cost.currency)} · ${basis ? this.i18n.t(basis) : cost.basis}`;
  }
}

/** The start of a calendar day in the operator's zone, as the instant the platform filters from. */
function startOfDay(day: string): string {
  return new Date(`${day}T00:00:00+05:00`).toISOString();
}

/** The start of the day after, which is the exclusive end of a day-granular «to». */
function startOfNextDay(day: string): string {
  return new Date(new Date(`${day}T00:00:00+05:00`).getTime() + 24 * 60 * 60 * 1000).toISOString();
}
