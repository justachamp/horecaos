import { ChangeDetectionStrategy, Component, computed, input, output, signal } from '@angular/core';

import { TPipe } from '../../core/i18n/t.pipe';

export interface CreateCustomerSubmission {
  readonly phone: string;
  readonly displayName: string;
}

/**
 * §5.1's "manual create" — a staff-initiated account with no Keycloak
 * principal link, the phone attached as the primary contact
 * (`CustomerController#createManually`'s own doc explains the shape).
 *
 * A phone and nothing else is required. Marketing consent is deliberately
 * not a field here: "absence of a decision is not consent" already applies
 * — `ConsentService`'s own doc — so a manually created account starts
 * non-contactable without this dialog having to say so.
 */
@Component({
  selector: 'q-create-customer-dialog',
  imports: [TPipe],
  templateUrl: './create-customer-dialog.html',
  styleUrl: './create-customer-dialog.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CreateCustomerDialog {
  readonly busy = input(false);
  readonly errorMessage = input<string | null>(null);

  readonly confirm = output<CreateCustomerSubmission>();
  readonly dismiss = output<void>();

  protected readonly phone = signal('');
  protected readonly displayName = signal('');
  private readonly touched = signal(false);

  protected readonly phoneMissing = computed(() => this.touched() && this.phone().trim() === '');

  protected setPhone(value: string): void {
    this.phone.set(value);
  }

  protected setDisplayName(value: string): void {
    this.displayName.set(value);
  }

  protected submit(): void {
    this.touched.set(true);
    const phone = this.phone().trim();
    if (!phone) {
      return;
    }
    this.confirm.emit({ phone, displayName: this.displayName().trim() });
  }

  protected close(): void {
    this.phone.set('');
    this.displayName.set('');
    this.touched.set(false);
    this.dismiss.emit();
  }
}
