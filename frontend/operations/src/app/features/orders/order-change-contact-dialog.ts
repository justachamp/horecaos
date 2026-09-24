import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  input,
  output,
  signal,
} from '@angular/core';

import { TPipe } from '../../core/i18n/t.pipe';
import { Modal } from '../../shared/ui/modal';

export interface ContactSubmission {
  readonly recipientName: string;
  readonly recipientPhone: string;
}

/**
 * `CHANGE_CONTACT` (ADR 0039, wave 10, row `1.2c`) — the recipient name and
 * phone `OrderAmendmentService.AmendmentCommand.changeContact` carries.
 * ADR 0029-protected: `order-detail-pane.ts` only opens this dialog after the
 * operator has already revealed the phone through the existing audited
 * `OrderRevealApi.revealPhone` call (the same reveal the customer panel's own
 * click-to-call uses) — this dialog never performs a reveal of its own, it
 * only edits the value the pane already holds.
 *
 * Never reprices (`AmendmentCommandType`'s own doc groups it with the field
 * updates), so the pane proposes it with `applyImmediately: true` and applies
 * it in the same call — no priced-delta confirmation step.
 */
@Component({
  selector: 'q-order-change-contact-dialog',
  imports: [TPipe, Modal],
  templateUrl: './order-change-contact-dialog.html',
  styleUrl: './order-change-contact-dialog.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class OrderChangeContactDialog {
  readonly initialName = input('');
  readonly initialPhone = input('');
  readonly busy = input(false);

  readonly confirm = output<ContactSubmission>();
  readonly dismiss = output<void>();

  protected readonly recipientName = signal(this.initialName());
  protected readonly recipientPhone = signal(this.initialPhone());
  private lastSeededName = this.initialName();
  private lastSeededPhone = this.initialPhone();

  protected readonly canSubmit = computed(
    () => this.recipientName().trim() !== '' && this.recipientPhone().trim() !== '',
  );

  constructor() {
    // Same delayed-`componentRef.setInput` resync `order-note-dialog.ts` carries.
    effect(() => {
      const name = this.initialName();
      if (name !== this.lastSeededName) {
        this.lastSeededName = name;
        this.recipientName.set(name);
      }
      const phone = this.initialPhone();
      if (phone !== this.lastSeededPhone) {
        this.lastSeededPhone = phone;
        this.recipientPhone.set(phone);
      }
    });
  }

  protected setRecipientName(value: string): void {
    this.recipientName.set(value.slice(0, 255));
  }

  protected setRecipientPhone(value: string): void {
    this.recipientPhone.set(value.slice(0, 32));
  }

  protected submit(): void {
    if (!this.canSubmit()) {
      return;
    }
    this.confirm.emit({
      recipientName: this.recipientName().trim(),
      recipientPhone: this.recipientPhone().trim(),
    });
  }
}
