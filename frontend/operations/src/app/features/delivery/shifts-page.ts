import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';

import { ApiError } from '../../core/api/problem-details';
import { CurrentLocation } from '../../core/auth/current-location';
import { TimeZone, formatClock, formatDateTime } from '../../core/format/datetime';
import { I18n } from '../../core/i18n/i18n';
import { TPipe } from '../../core/i18n/t.pipe';
import {
  CouriersApi,
  PlannedShiftView,
  RosterEntryResponse,
  ShiftView,
} from '../couriers/couriers-api';
import { describeApiError } from '../orders/order-errors';
import { PlanShiftDialog, PlanShiftSubmission } from './plan-shift-dialog';
import { Toasts } from '../../shared/ui/toast';
import { StatusPill, StatusTone } from '../../shared/ui/status-pill';

/** See `order-queue.ts`'s identical constant — no location carries a timezone on any response this board reaches yet. */
const PLACEHOLDER_TIME_ZONE: TimeZone = 'Asia/Tashkent';

/**
 * IA 3.5 — Shifts & attendance (Посещаемость).
 *
 * **Built**: the branch's shift roster, open and closed, this wave's new
 * `GET /courier-shifts` over `JdbcCourierShiftStore.atLocation` (ADR 0042's
 * store had every field already; nothing read across shifts before this);
 * approve/close, reusing the existing `OperationsCourierController`
 * endpoints `couriers-page.ts`'s own roster never needed; a `from`/`to`
 * period filter over that same read; the courier's `breakSeconds` and
 * `dutyState`, which were on the wire from the start and the template
 * simply dropped; a courier's `displayReference` in place of a raw UUID.
 *
 * **New this wave**: the planned-shift roster (ADR 0042's
 * `courier_roster_entries`, deliberately omitted by V0040) over a minimal
 * local grid — `P02`'s `q-schedule-grid` is not merged yet, so this is a
 * plain table rather than that widget, and swaps in cleanly once it lands.
 * A manager plans a shift, publishes it, or cancels it, and the grid shows
 * each planned entry's coverage against what a courier actually opened —
 * COVERED, PENDING (the window has not elapsed) or UNCOVERED.
 *
 * **Not built, honestly**: the courier's own accept/decline of a published
 * offer (that surface is the courier app's, not this console's, and this
 * wave does not build it — `respondedAt` stays null on every entry here);
 * hours feeding payout beyond the raw `paidSeconds` shown here (the payout
 * run itself is Finance 8.5, out of this wave's section); switching
 * `courier.shift.enforcement` to require a roster entry — `ENFORCED_WITH_
 * ROSTER` is still not a real enforcement mode, and the policy write that
 * would introduce one is P38's.
 */
@Component({
  selector: 'q-shifts-page',
  imports: [TPipe, PlanShiftDialog, StatusPill],
  templateUrl: './shifts-page.html',
  styleUrl: './shifts-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ShiftsPage implements OnInit {
  private readonly api = inject(CouriersApi);
  private readonly location = inject(CurrentLocation);
  private readonly toasts = inject(Toasts);
  protected readonly i18n = inject(I18n);

  protected readonly loading = signal(true);
  protected readonly denied = signal(false);
  protected readonly loadError = signal<string | null>(null);
  protected readonly shifts = signal<readonly ShiftView[]>([]);
  protected readonly busyShiftIds = signal<ReadonlySet<string>>(new Set());
  protected readonly actionError = signal<string | null>(null);

  protected readonly fromDate = signal('');
  protected readonly toDate = signal('');

  protected readonly rosterLoading = signal(true);
  protected readonly rosterError = signal<string | null>(null);
  protected readonly rosterEntries = signal<readonly PlannedShiftView[]>([]);
  protected readonly busyEntryIds = signal<ReadonlySet<string>>(new Set());

  protected readonly couriers = signal<readonly RosterEntryResponse[]>([]);
  protected readonly planDialogOpen = signal(false);
  protected readonly planBusy = signal(false);

  async ngOnInit(): Promise<void> {
    await this.load();
  }

  private async load(): Promise<void> {
    this.loading.set(true);
    this.rosterLoading.set(true);
    await this.location.ensureLoaded();
    const scope = this.location.scope();
    if (!scope) {
      this.denied.set(this.location.denied());
      this.loading.set(false);
      this.rosterLoading.set(false);
      return;
    }
    const from = this.fromDate() ? new Date(this.fromDate()).toISOString() : undefined;
    // Inclusive of the whole end day, not just its midnight.
    const to = this.toDate() ? endOfDayIso(this.toDate()) : undefined;

    try {
      const [shifts, couriers] = await Promise.all([
        this.api.shifts(scope.tenantId, scope.brandId, scope.locationId, 200, from, to),
        this.api.roster(scope.tenantId),
      ]);
      this.shifts.set(shifts);
      this.couriers.set(couriers);
      this.denied.set(false);
    } catch (error) {
      if (error instanceof ApiError && error.status === 403) {
        this.denied.set(true);
      } else {
        this.loadError.set(this.describe(error));
      }
    } finally {
      this.loading.set(false);
    }

    await this.loadRoster(scope, from, to);
  }

  private async loadRoster(
    scope: { tenantId: string; brandId: string; locationId: string },
    from: string | undefined,
    to: string | undefined,
  ): Promise<void> {
    this.rosterLoading.set(true);
    const windowFrom = from ?? defaultWindowStart();
    const windowTo = to ?? defaultWindowEnd();
    try {
      const comparison = await this.api.rosterComparison(
        scope.tenantId,
        scope.brandId,
        scope.locationId,
        windowFrom,
        windowTo,
      );
      this.rosterEntries.set(comparison);
      this.rosterError.set(null);
    } catch (error) {
      this.rosterError.set(this.describe(error));
    } finally {
      this.rosterLoading.set(false);
    }
  }

  protected async applyPeriod(): Promise<void> {
    await this.load();
  }

  protected setFromDate(value: string): void {
    this.fromDate.set(value);
  }

  protected setToDate(value: string): void {
    this.toDate.set(value);
  }

  protected statusLabel(status: string): string {
    switch (status) {
      case 'OPEN':
        return this.i18n.t('delivery.shifts.status.OPEN');
      case 'CLOSE_REQUESTED':
        return this.i18n.t('delivery.shifts.status.CLOSE_REQUESTED');
      case 'RECONCILING':
        return this.i18n.t('delivery.shifts.status.RECONCILING');
      case 'AWAITING_APPROVAL':
        return this.i18n.t('delivery.shifts.status.AWAITING_APPROVAL');
      case 'CLOSED':
        return this.i18n.t('delivery.shifts.status.CLOSED');
      case 'AUTO_CLOSED':
        return this.i18n.t('delivery.shifts.status.AUTO_CLOSED');
      case 'SETTLED':
        return this.i18n.t('delivery.shifts.status.SETTLED');
      default:
        return status;
    }
  }

  protected dutyStateLabel(dutyState: string): string {
    switch (dutyState) {
      case 'AVAILABLE':
        return this.i18n.t('delivery.shifts.dutyState.AVAILABLE');
      case 'ON_BREAK':
        return this.i18n.t('delivery.shifts.dutyState.ON_BREAK');
      case 'AT_CAPACITY':
        return this.i18n.t('delivery.shifts.dutyState.AT_CAPACITY');
      case 'UNREACHABLE':
        return this.i18n.t('delivery.shifts.dutyState.UNREACHABLE');
      default:
        return dutyState;
    }
  }

  protected dutyStateTone(dutyState: string): StatusTone {
    switch (dutyState) {
      case 'AVAILABLE':
        return 'success';
      case 'ON_BREAK':
        return 'info';
      case 'AT_CAPACITY':
      case 'UNREACHABLE':
        return 'warning';
      default:
        return 'none';
    }
  }

  protected courierLabel(shift: ShiftView | PlannedShiftView): string {
    return shift.courierDisplayReference ?? shift.courierId;
  }

  protected openedAtLabel(shift: ShiftView): string {
    return formatClock(new Date(shift.openedAt), PLACEHOLDER_TIME_ZONE);
  }

  protected closedAtLabel(shift: ShiftView): string {
    return shift.closedAt ? formatClock(new Date(shift.closedAt), PLACEHOLDER_TIME_ZONE) : '—';
  }

  protected paidLabel(shift: ShiftView): string {
    return this.secondsAsHours(shift.paidSeconds);
  }

  /** Time on break is the central attendance figure IA 3.5 asks for and the template used to drop entirely. */
  protected breakLabel(shift: ShiftView): string {
    return this.secondsAsHours(shift.breakSeconds);
  }

  private secondsAsHours(seconds: number | null | undefined): string {
    if (seconds === null || seconds === undefined) {
      return '—';
    }
    const minutes = Math.round(seconds / 60);
    const hours = Math.floor(minutes / 60);
    const rest = String(minutes % 60).padStart(2, '0');
    return `${hours}:${rest}`;
  }

  protected isBusy(shift: ShiftView): boolean {
    return this.busyShiftIds().has(shift.shiftId);
  }

  protected canClose(shift: ShiftView): boolean {
    return shift.status === 'OPEN' && !this.isBusy(shift);
  }

  protected canApprove(shift: ShiftView): boolean {
    return shift.status === 'AWAITING_APPROVAL' && !this.isBusy(shift);
  }

  protected async close(shift: ShiftView): Promise<void> {
    const scope = this.location.scope();
    if (!scope || !this.canClose(shift)) {
      return;
    }
    this.setBusy(shift.shiftId, true);
    try {
      await this.api.closeShift(
        scope.tenantId,
        shift.shiftId,
        'END_OF_SERVICE',
        this.i18n.t('delivery.shifts.closeReason'),
        'UZS',
      );
      await this.load();
    } catch (error) {
      this.actionError.set(this.describe(error));
    } finally {
      this.setBusy(shift.shiftId, false);
    }
  }

  protected async approve(shift: ShiftView): Promise<void> {
    const scope = this.location.scope();
    if (!scope || !this.canApprove(shift)) {
      return;
    }
    this.setBusy(shift.shiftId, true);
    try {
      await this.api.approveShift(
        scope.tenantId,
        shift.shiftId,
        this.i18n.t('delivery.shifts.approveReason'),
      );
      await this.load();
    } catch (error) {
      this.actionError.set(this.describe(error));
    } finally {
      this.setBusy(shift.shiftId, false);
    }
  }

  private setBusy(shiftId: string, busy: boolean): void {
    this.busyShiftIds.update((current) => {
      const next = new Set(current);
      if (busy) {
        next.add(shiftId);
      } else {
        next.delete(shiftId);
      }
      return next;
    });
  }

  // ------------------------------------------------------------------ roster

  protected entryStatusLabel(status: string): string {
    switch (status) {
      case 'DRAFT':
        return this.i18n.t('delivery.shifts.roster.status.DRAFT');
      case 'PUBLISHED':
        return this.i18n.t('delivery.shifts.roster.status.PUBLISHED');
      case 'ACCEPTED':
        return this.i18n.t('delivery.shifts.roster.status.ACCEPTED');
      case 'DECLINED':
        return this.i18n.t('delivery.shifts.roster.status.DECLINED');
      case 'CONSUMED':
        return this.i18n.t('delivery.shifts.roster.status.CONSUMED');
      case 'MISSED':
        return this.i18n.t('delivery.shifts.roster.status.MISSED');
      case 'CANCELLED':
        return this.i18n.t('delivery.shifts.roster.status.CANCELLED');
      default:
        return status;
    }
  }

  protected coverageLabel(coverage: string | null | undefined): string {
    switch (coverage) {
      case 'COVERED':
        return this.i18n.t('delivery.shifts.roster.coverage.COVERED');
      case 'PENDING':
        return this.i18n.t('delivery.shifts.roster.coverage.PENDING');
      case 'UNCOVERED':
        return this.i18n.t('delivery.shifts.roster.coverage.UNCOVERED');
      default:
        return '—';
    }
  }

  protected coverageTone(coverage: string | null | undefined): StatusTone {
    switch (coverage) {
      case 'COVERED':
        return 'success';
      case 'PENDING':
        return 'info';
      case 'UNCOVERED':
        return 'danger';
      default:
        return 'none';
    }
  }

  protected plannedWindowLabel(entry: PlannedShiftView): string {
    return `${formatDateTime(new Date(entry.plannedStart), PLACEHOLDER_TIME_ZONE)} – ${formatDateTime(new Date(entry.plannedEnd), PLACEHOLDER_TIME_ZONE)}`;
  }

  protected isEntryBusy(entry: PlannedShiftView): boolean {
    return this.busyEntryIds().has(entry.entryId);
  }

  protected canPublish(entry: PlannedShiftView): boolean {
    return entry.status === 'DRAFT' && !this.isEntryBusy(entry);
  }

  protected canCancelEntry(entry: PlannedShiftView): boolean {
    return (entry.status === 'DRAFT' || entry.status === 'PUBLISHED') && !this.isEntryBusy(entry);
  }

  protected openPlanDialog(): void {
    this.planDialogOpen.set(true);
  }

  protected closePlanDialog(): void {
    this.planDialogOpen.set(false);
  }

  protected async submitPlan(submission: PlanShiftSubmission): Promise<void> {
    const scope = this.location.scope();
    if (!scope) {
      return;
    }
    this.planBusy.set(true);
    try {
      await this.api.draftRosterEntry(scope.tenantId, {
        brandId: scope.brandId,
        locationId: scope.locationId,
        courierId: submission.courierId,
        plannedStart: submission.plannedStart,
        plannedEnd: submission.plannedEnd,
        reason: submission.reason,
      });
      this.planDialogOpen.set(false);
      this.toasts.show({ message: this.i18n.t('delivery.shifts.roster.planned'), tone: 'success' });
      await this.loadRoster(scope, undefined, undefined);
    } catch (error) {
      this.rosterError.set(this.describe(error));
    } finally {
      this.planBusy.set(false);
    }
  }

  protected async publishEntry(entry: PlannedShiftView): Promise<void> {
    const scope = this.location.scope();
    if (!scope || !this.canPublish(entry)) {
      return;
    }
    this.setEntryBusy(entry.entryId, true);
    try {
      await this.api.publishRosterEntry(
        scope.tenantId,
        entry.entryId,
        this.i18n.t('delivery.shifts.roster.publishReason'),
      );
      this.toasts.show({
        message: this.i18n.t('delivery.shifts.roster.published'),
        tone: 'success',
      });
      await this.loadRoster(scope, undefined, undefined);
    } catch (error) {
      this.rosterError.set(this.describe(error));
    } finally {
      this.setEntryBusy(entry.entryId, false);
    }
  }

  protected async cancelEntry(entry: PlannedShiftView): Promise<void> {
    const scope = this.location.scope();
    if (!scope || !this.canCancelEntry(entry)) {
      return;
    }
    this.setEntryBusy(entry.entryId, true);
    try {
      await this.api.cancelRosterEntry(
        scope.tenantId,
        entry.entryId,
        this.i18n.t('delivery.shifts.roster.cancelReason'),
      );
      this.toasts.show({
        message: this.i18n.t('delivery.shifts.roster.cancelled'),
        tone: 'success',
      });
      await this.loadRoster(scope, undefined, undefined);
    } catch (error) {
      this.rosterError.set(this.describe(error));
    } finally {
      this.setEntryBusy(entry.entryId, false);
    }
  }

  private setEntryBusy(entryId: string, busy: boolean): void {
    this.busyEntryIds.update((current) => {
      const next = new Set(current);
      if (busy) {
        next.add(entryId);
      } else {
        next.delete(entryId);
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

/** The end of a `yyyy-MM-dd` date, in the browser's own zone, as an ISO instant. */
function endOfDayIso(dateOnly: string): string {
  const date = new Date(`${dateOnly}T23:59:59.999`);
  return date.toISOString();
}

/** A week back, when no `from` is chosen — the roster grid still needs a window to compare. */
function defaultWindowStart(): string {
  const date = new Date();
  date.setDate(date.getDate() - 7);
  return date.toISOString();
}

/** A week ahead, when no `to` is chosen. */
function defaultWindowEnd(): string {
  const date = new Date();
  date.setDate(date.getDate() + 7);
  return date.toISOString();
}
