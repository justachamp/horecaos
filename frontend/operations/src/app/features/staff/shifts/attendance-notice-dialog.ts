import { ChangeDetectionStrategy, Component, input, output, signal } from '@angular/core';

import { TPipe } from '../../../core/i18n/t.pipe';
import { Modal } from '../../../shared/ui/modal';

/**
 * The notice a tenant reads at the moment it switches staff attendance on (ADR 0156, third open input:
 * recording a person's hours may be workplace monitoring that needs the tenant to notify its staff
 * first). It says in plain words what the record holds and what it does not, and asks for a reason that
 * goes on the audited configuration write. The wording is the platform's default until legal supplies
 * the tenant-facing text; switching on in production waits on that, not on this build.
 */
@Component({
  selector: 'q-attendance-notice-dialog',
  imports: [TPipe, Modal],
  templateUrl: './attendance-notice-dialog.html',
  styleUrl: './attendance-notice-dialog.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AttendanceNoticeDialog {
  readonly busy = input(false);
  readonly failure = input<string | null>(null);

  readonly confirm = output<string>();
  readonly dismiss = output<void>();

  protected readonly acknowledged = signal(false);
  protected readonly reason = signal('');

  protected setReason(value: string): void {
    this.reason.set(value);
  }

  protected toggleAcknowledged(checked: boolean): void {
    this.acknowledged.set(checked);
  }

  protected submit(): void {
    const reason = this.reason().trim();
    if (!this.acknowledged() || reason === '') {
      return;
    }
    this.confirm.emit(reason);
  }
}
