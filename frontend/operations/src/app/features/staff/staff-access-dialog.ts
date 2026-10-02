import { ChangeDetectionStrategy, Component, computed, input, output, signal } from '@angular/core';

import { MessageKey } from '../../core/i18n/messages.en';
import { TPipe } from '../../core/i18n/t.pipe';

/**
 * `resendInvite`/`revokeInvite` are staff-access-dialog's own two more
 * confirmations (ADR 0116, staff-and-access.md §4): a manager acting on a
 * person who has not accepted their invitation yet, from the same row-action
 * surface the suspend/restore pair already uses. `endEmployment` is the fifth
 * (ADR 0139, «Завершить работу»): the same shape -- name the person, a
 * mandatory reason, confirm -- plus the optional day employment ended.
 */
export type StaffAccessDialogMode =
  'suspend' | 'restore' | 'resendInvite' | 'revokeInvite' | 'endEmployment';

/**
 * Приостановить/Вернуть доступ (staff-and-access.md §2), and, since ADR 0116,
 * Отправить повторно/Отозвать приглашение (§4). One component for all four,
 * because the confirmation shape is identical — name the person, ask for a
 * mandatory reason, confirm — and only the sentence and the call the parent
 * makes on confirm differ.
 *
 * `affectedJobCount` renders the "N назначений" the suspend/restore body
 * needs (§2's «names the person and states what stops working»); the parent
 * (`staff-page.ts`) computes it from the person's grants because doing the
 * fan-out — N `DELETE`/`POST .../grants` calls, one per assignment, per
 * ADR 0039's "N independent audited operations, not one transaction" — is
 * the parent's job, not a confirmation dialog's. The invite modes revoke
 * exactly the one grant the invitation was for and ignore this input.
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
  readonly affectedJobCount = input(0);
  readonly busy = input(false);
  readonly serverError = input<string | null>(null);

  readonly confirmed = output<{ reason: string; employedUntil?: string }>();
  readonly dismiss = output<void>();

  protected readonly reason = signal('');
  /** `endEmployment` only: the last day of work, as `yyyy-mm-dd`, or blank for "today" in the tenant's own zone. */
  protected readonly employedUntil = signal('');
  private readonly touched = signal(false);

  protected readonly reasonMissing = computed(() => this.touched() && this.reason().trim() === '');

  /** The action carries destructive styling — suspend, revoking an invitation and ending employment; never restore or resend. */
  protected readonly destructive = computed(
    () =>
      this.mode() === 'suspend' ||
      this.mode() === 'revokeInvite' ||
      this.mode() === 'endEmployment',
  );

  protected readonly titleKey = computed<MessageKey>(() => TITLE_KEYS[this.mode()]);
  protected readonly bodyKey = computed<MessageKey>(() => BODY_KEYS[this.mode()]);
  protected readonly actionKey = computed<MessageKey>(() => ACTION_KEYS[this.mode()]);

  protected setReason(value: string): void {
    this.reason.set(value);
  }

  protected setEmployedUntil(value: string): void {
    this.employedUntil.set(value);
  }

  protected confirm(): void {
    this.touched.set(true);
    const reason = this.reason().trim();
    if (!reason) {
      return;
    }
    const until = this.employedUntil().trim();
    this.confirmed.emit(
      this.mode() === 'endEmployment' && until ? { reason, employedUntil: until } : { reason },
    );
  }

  protected close(): void {
    this.dismiss.emit();
  }
}

const TITLE_KEYS: Record<StaffAccessDialogMode, MessageKey> = {
  suspend: 'staff.accessDialog.suspend.title',
  restore: 'staff.accessDialog.restore.title',
  resendInvite: 'staff.accessDialog.resendInvite.title',
  revokeInvite: 'staff.accessDialog.revokeInvite.title',
  endEmployment: 'staff.accessDialog.endEmployment.title',
};

const BODY_KEYS: Record<StaffAccessDialogMode, MessageKey> = {
  suspend: 'staff.accessDialog.suspend.body',
  restore: 'staff.accessDialog.restore.body',
  resendInvite: 'staff.accessDialog.resendInvite.body',
  revokeInvite: 'staff.accessDialog.revokeInvite.body',
  endEmployment: 'staff.accessDialog.endEmployment.body',
};

const ACTION_KEYS: Record<StaffAccessDialogMode, MessageKey> = {
  suspend: 'staff.action.suspend',
  restore: 'staff.action.restore',
  resendInvite: 'staff.accessDialog.resendInvite.confirm',
  revokeInvite: 'staff.accessDialog.revokeInvite.confirm',
  endEmployment: 'staff.accessDialog.endEmployment.title',
};
