import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';

import { ApiError } from '../../core/api/problem-details';
import { CurrentLocation } from '../../core/auth/current-location';
import { TimeZone, formatClock } from '../../core/format/datetime';
import { I18n } from '../../core/i18n/i18n';
import { TPipe } from '../../core/i18n/t.pipe';
import { CouriersApi, ShiftView } from '../couriers/couriers-api';
import { describeApiError } from '../orders/order-errors';

/** See `order-queue.ts`'s identical constant — no location carries a timezone on any response this board reaches yet. */
const PLACEHOLDER_TIME_ZONE: TimeZone = 'Asia/Tashkent';

/**
 * IA 3.5 — Shifts & attendance (Посещаемость).
 *
 * **Built**: the branch's shift roster, open and closed, this wave's new
 * `GET /courier-shifts` over `JdbcCourierShiftStore.atLocation` (ADR 0042's
 * store had every field already; nothing read across shifts before this);
 * approve/close, reusing the existing `OperationsCourierController`
 * endpoints `couriers-page.ts`'s own roster never needed.
 *
 * **Not built, honestly**: rosters/scheduling (a *planned* shift — nothing
 * in ADR 0042 models one, only the shift a courier actually opened); hours
 * feeding payout beyond the raw `paidSeconds` shown here (the payout run
 * itself is Finance 8.5, out of this wave's section).
 */
@Component({
  selector: 'q-shifts-page',
  imports: [TPipe],
  templateUrl: './shifts-page.html',
  styleUrl: './shifts-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ShiftsPage implements OnInit {
  private readonly api = inject(CouriersApi);
  private readonly location = inject(CurrentLocation);
  protected readonly i18n = inject(I18n);

  protected readonly loading = signal(true);
  protected readonly denied = signal(false);
  protected readonly loadError = signal<string | null>(null);
  protected readonly shifts = signal<readonly ShiftView[]>([]);
  protected readonly busyShiftIds = signal<ReadonlySet<string>>(new Set());
  protected readonly actionError = signal<string | null>(null);

  async ngOnInit(): Promise<void> {
    await this.load();
  }

  private async load(): Promise<void> {
    this.loading.set(true);
    await this.location.ensureLoaded();
    const scope = this.location.scope();
    if (!scope) {
      this.denied.set(this.location.denied());
      this.loading.set(false);
      return;
    }
    try {
      const shifts = await this.api.shifts(scope.tenantId, scope.brandId, scope.locationId);
      this.shifts.set(shifts);
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

  protected openedAtLabel(shift: ShiftView): string {
    return formatClock(new Date(shift.openedAt), PLACEHOLDER_TIME_ZONE);
  }

  protected closedAtLabel(shift: ShiftView): string {
    return shift.closedAt ? formatClock(new Date(shift.closedAt), PLACEHOLDER_TIME_ZONE) : '—';
  }

  protected paidLabel(shift: ShiftView): string {
    if (shift.paidSeconds === null || shift.paidSeconds === undefined) {
      return '—';
    }
    const minutes = Math.round(shift.paidSeconds / 60);
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

  private describe(error: unknown): string {
    return error instanceof ApiError
      ? describeApiError(error, (key, values) => this.i18n.t(key, values))
      : this.i18n.t('error.unknown.noReference');
  }
}
