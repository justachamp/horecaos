import { ChangeDetectionStrategy, Component, computed, input, output, signal } from '@angular/core';

import { TPipe } from '../../core/i18n/t.pipe';

export type StaffAccessDialogMode = 'suspend' | 'restore';

/**
 * Приостановить/Вернуть доступ (staff-and-access.md §2). One component for
 * both, because the confirmation shape is identical — name the person, ask
 * for a mandatory reason, confirm — and only the sentence and the call the
 * parent makes on confirm differ.
 *
 * `affectedJobCount` renders the "N назначений" the confirm text needs
 * (§2's «names the person and states what stops working»); the parent
 * (`staff-page.ts`) computes it from the person's grants because doing the
 * fan-out — N `DELETE`/`POST .../grants` calls, one per assignment, per
 * ADR 0039's "N independent audited operations, not one transaction" — is
 * the parent's job, not a confirmation dialog's.
 */
@Component({
  selector: 'q-staff-access-dialog',
  imports: [TPipe],
  templateUrl: './staff-access-dialog.html',
  styleUrl: './staff-access-dialog.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class StaffAccessDialog {
  readonly mode = input.required<StaffAccessDialogMode>();
  readonly principalSubject = input.required<string>();
  readonly affectedJobCount = input.required<number>();
  readonly busy = input(false);
  readonly serverError = input<string | null>(null);

  readonly confirmed = output<{ reason: string }>();
  readonly dismiss = output<void>();

  protected readonly reason = signal('');
  private readonly touched = signal(false);

  protected readonly reasonMissing = computed(() => this.touched() && this.reason().trim() === '');

  protected setReason(value: string): void {
    this.reason.set(value);
  }

  protected confirm(): void {
    this.touched.set(true);
    const reason = this.reason().trim();
    if (!reason) {
      return;
    }
    this.confirmed.emit({ reason });
  }

  protected close(): void {
    this.dismiss.emit();
  }
}
