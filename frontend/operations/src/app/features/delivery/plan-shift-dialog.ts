import { ChangeDetectionStrategy, Component, computed, input, output, signal } from '@angular/core';

import { TPipe } from '../../core/i18n/t.pipe';
import { RosterEntryResponse } from '../couriers/couriers-api';
import { Modal } from '../../shared/ui/modal';

export interface PlanShiftSubmission {
  readonly courierId: string;
  readonly plannedStart: string;
  readonly plannedEnd: string;
  readonly reason: string;
}

/**
 * IA 3.5's "plan a shift" form (ADR 0042's roster entry).
 *
 * **A `<select>` over the in-house roster, not a combobox.** `X.9`'s async
 * search-with-create-on-miss combobox is `P02`'s to build and is not merged
 * yet; a courier list a branch runs is small enough that a plain select
 * loses nothing today. Swap this for `q-combobox` once it exists — nothing
 * else about the dialog changes.
 *
 * **No time zone picker.** Every input here is the branch's own local time,
 * entered and read back in the browser's zone; the page that opens this
 * dialog is already branch-scoped, so there is nothing to disambiguate.
 */
@Component({
  selector: 'q-plan-shift-dialog',
  imports: [TPipe, Modal],
  templateUrl: './plan-shift-dialog.html',
  styleUrl: './plan-shift-dialog.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PlanShiftDialog {
  readonly couriers = input.required<readonly RosterEntryResponse[]>();
  readonly busy = input(false);

  readonly confirm = output<PlanShiftSubmission>();
  readonly dismiss = output<void>();

  protected readonly courierId = signal('');
  protected readonly plannedStart = signal('');
  protected readonly plannedEnd = signal('');
  protected readonly reason = signal('');
  private readonly touched = signal(false);

  protected readonly courierMissing = computed(() => this.touched() && this.courierId() === '');
  protected readonly windowInvalid = computed(() => {
    if (!this.touched() || !this.plannedStart() || !this.plannedEnd()) {
      return false;
    }
    return new Date(this.plannedEnd()).getTime() <= new Date(this.plannedStart()).getTime();
  });
  protected readonly reasonMissing = computed(() => this.touched() && this.reason().trim() === '');

  protected setCourierId(value: string): void {
    this.courierId.set(value);
  }

  protected setPlannedStart(value: string): void {
    this.plannedStart.set(value);
  }

  protected setPlannedEnd(value: string): void {
    this.plannedEnd.set(value);
  }

  protected setReason(value: string): void {
    this.reason.set(value);
  }

  protected submit(): void {
    this.touched.set(true);
    const courierId = this.courierId();
    const plannedStart = this.plannedStart();
    const plannedEnd = this.plannedEnd();
    const reason = this.reason().trim();
    if (
      !courierId ||
      !plannedStart ||
      !plannedEnd ||
      !reason ||
      new Date(plannedEnd).getTime() <= new Date(plannedStart).getTime()
    ) {
      return;
    }
    this.confirm.emit({
      courierId,
      plannedStart: new Date(plannedStart).toISOString(),
      plannedEnd: new Date(plannedEnd).toISOString(),
      reason,
    });
  }

  protected close(): void {
    this.courierId.set('');
    this.plannedStart.set('');
    this.plannedEnd.set('');
    this.reason.set('');
    this.touched.set(false);
    this.dismiss.emit();
  }
}
