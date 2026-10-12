import { ChangeDetectionStrategy, Component, computed, input, output, signal } from '@angular/core';

import { TPipe } from '../core/i18n/t.pipe';

/**
 * The numeric pad a PIN or an activation code is typed on: digits only, large touch targets, a backspace and an OK that
 * stays disabled until the entry is long enough.
 *
 * It never auto-submits at a length. A PIN set under an earlier policy may be shorter or longer than today's, so only the
 * person pressing OK says the entry is finished. The typed digits live in this component's signal and leave through
 * `submitted` once; they are not echoed to the page unless the caller asks for the code to be shown (an activation code is
 * read off a screen or a slip and is not secret from the person typing it; a PIN is always dots).
 */
@Component({
  selector: 'q-pin-keypad',
  imports: [TPipe],
  templateUrl: './pin-keypad.html',
  styleUrl: './pin-keypad.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PinKeypad {
  readonly title = input.required<string>();
  readonly minLength = input(4);
  readonly maxLength = input(12);
  /** Show the digits (an activation code) instead of dots (a PIN). */
  readonly reveal = input(false);
  readonly busy = input(false);
  readonly error = input<string | null>(null);
  readonly submitLabel = input<string | null>(null);
  readonly cancelLabel = input<string | null>(null);

  readonly submitted = output<string>();
  readonly cancelled = output<void>();

  protected readonly digits = signal('');
  protected readonly keys = ['1', '2', '3', '4', '5', '6', '7', '8', '9'];
  protected readonly canSubmit = computed(
    () => this.digits().length >= this.minLength() && !this.busy(),
  );
  protected readonly shown = computed(() =>
    this.reveal() ? this.digits() : '•'.repeat(this.digits().length),
  );

  protected press(digit: string): void {
    if (this.busy() || this.digits().length >= this.maxLength()) {
      return;
    }
    this.digits.update((current) => current + digit);
  }

  protected back(): void {
    this.digits.update((current) => current.slice(0, -1));
  }

  protected clear(): void {
    this.digits.set('');
  }

  protected submit(): void {
    if (!this.canSubmit()) {
      return;
    }
    const value = this.digits();
    // The entry is cleared as it leaves: a refused PIN is typed again, never edited.
    this.digits.set('');
    this.submitted.emit(value);
  }

  /** Lets the owner of a step start from nothing again, e.g. after a refusal. */
  reset(): void {
    this.digits.set('');
  }
}
