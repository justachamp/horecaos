import { ChangeDetectionStrategy, Component, computed, input, output, signal } from '@angular/core';

import { TPipe } from '../../core/i18n/t.pipe';
import { RosterEntryResponse } from '../couriers/couriers-api';

/** What the operator confirmed — the host owns the request (ADR 0172). */
export interface BulkAssignSubmission {
  readonly courierId: string;
  readonly reasonCode: string;
}

/** The reason code the console pre-fills (ADR 0172, open input «The reason code»). */
export const DEFAULT_BULK_ASSIGN_REASON = 'OPERATIONS_BULK_ASSIGN';

/** The same shape the single assignment and the server accept: a code, never prose. */
const REASON_CODE = /^[A-Z0-9_]{1,48}$/;

/**
 * The courier picker behind «Назначить курьера» on the orders board's bulk bar (ADR 0172
 * Decision 10, orders.md §2.10).
 *
 * Presentational, mirroring {@code ExternalCourierDialog}: the host fetches the roster (the one
 * the dispatch board already reads) and sends the one request. Each courier shows what a
 * dispatcher decides on — online or not, how many orders they carry against their type's ceiling —
 * and a courier whose engagement is not active is shown but cannot be picked, because the server
 * would refuse every item for them anyway. The ceiling is advisory here: the server counts it in
 * submission order and says `COURIER_AT_CAPACITY` for the rest.
 */
@Component({
  selector: 'q-order-bulk-assign-dialog',
  imports: [TPipe],
  templateUrl: './order-bulk-assign-dialog.html',
  styleUrl: './order-bulk-assign-dialog.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class OrderBulkAssignDialog {
  readonly couriers = input.required<readonly RosterEntryResponse[]>();
  readonly count = input.required<number>();
  readonly busy = input(false);
  /**
   * «Повторить проблемные» for an assignment (ADR 0172 exit criterion): the same dialog, opened for the orders
   * that were refused, so a different courier can be picked for them. Sending the same courier again would
   * fail the same way.
   */
  readonly retry = input(false);
  /** The courier the failed orders were sent to, shown as already tried. */
  readonly triedCourierId = input<string | null>(null);

  readonly confirmed = output<BulkAssignSubmission>();
  readonly dismissed = output<void>();

  protected readonly selectedCourierId = signal<string | null>(null);
  protected readonly reasonCode = signal(DEFAULT_BULK_ASSIGN_REASON);

  protected readonly reasonValid = computed(() => REASON_CODE.test(this.reasonCode()));
  protected readonly canConfirm = computed(
    () => !this.busy() && this.selectedCourierId() !== null && this.reasonValid(),
  );

  protected isPickable(courier: RosterEntryResponse): boolean {
    return courier.status === 'ACTIVE' && courier.engagementStatus === 'ACTIVE';
  }

  protected select(courier: RosterEntryResponse): void {
    if (this.isPickable(courier)) {
      this.selectedCourierId.set(courier.courierId);
    }
  }

  protected onReasonInput(value: string): void {
    this.reasonCode.set(value.trim().toUpperCase());
  }

  protected confirm(): void {
    const courierId = this.selectedCourierId();
    if (!this.canConfirm() || courierId === null) {
      return;
    }
    this.confirmed.emit({ courierId, reasonCode: this.reasonCode() });
  }

  protected dismiss(): void {
    if (!this.busy()) {
      this.dismissed.emit();
    }
  }
}
