import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  booleanAttribute,
  effect,
  input,
  output,
  signal,
  viewChildren,
} from '@angular/core';

/**
 * A one-time code, entered one segmented cell at a time — `q-otp-input`
 * (row `X.38`).
 *
 * **Component only, on purpose.** There is no staff second-factor endpoint
 * today, the storefront's phone-OTP session is a different surface and a
 * different principal, and whether a staff factor is enrolled in Keycloak or
 * modelled on the platform is the staff-identity ADR's call — this wave must
 * not invent an endpoint to justify building the input. What it builds is
 * useful either way, and is what a future terminal PIN screen reuses.
 *
 * **Paste fills every cell at once.** A code arrives by SMS or an
 * authenticator app, and an operator who copies the whole thing should not
 * have to place the caret in cell one first — pasting anywhere in the group
 * distributes the digits from that point.
 *
 * **`complete` fires once, the moment every cell holds a digit** — by typing
 * the last one or by a paste that fills the group — so a caller wanting
 * auto-submit has exactly one event to listen for, never a length check of
 * its own against `valueChange`.
 */
@Component({
  selector: 'q-otp-input',
  templateUrl: './otp-input.html',
  styleUrl: './otp-input.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class OtpInput {
  readonly length = input(6);
  /** The code so far, digits only. Shorter than {@link length} pads the remaining cells empty. */
  readonly value = input('');
  readonly disabled = input(false, { transform: booleanAttribute });
  /** The group's own accessible name — "Enter the code sent to +998 90 ••• •• 42". Already translated. */
  readonly ariaLabel = input<string | null>(null);
  /** Already translated — "Digit"; each cell's own label becomes "{prefix} {n}". */
  readonly cellLabelPrefix = input.required<string>();

  readonly valueChange = output<string>();
  /** Emits the full code exactly once, the moment the last cell is filled. */
  readonly complete = output<string>();

  protected readonly digits = signal<readonly string[]>([]);
  protected readonly indexes = signal<readonly number[]>([]);

  private readonly cells = viewChildren<ElementRef<HTMLInputElement>>('cell');
  private lastEmittedValue: string | null = null;

  constructor() {
    effect(
      () => {
        const length = this.length();
        const value = this.value();
        this.indexes.set(Array.from({ length }, (_, i) => i));
        if (value === this.lastEmittedValue) {
          return;
        }
        this.digits.set(Array.from({ length }, (_, i) => value[i] ?? ''));
      },
      { allowSignalWrites: true },
    );
  }

  protected onCellInput(index: number, event: Event): void {
    const raw = (event.target as HTMLInputElement).value;
    const digit = raw.replace(/\D/g, '').slice(-1);
    this.digits.update((current) => {
      const next = [...current];
      next[index] = digit;
      return next;
    });
    this.emit();
    if (digit && index < this.length() - 1) {
      this.focusCell(index + 1);
    }
  }

  protected onCellKeydown(index: number, event: KeyboardEvent): void {
    if (event.key === 'Backspace' && this.digits()[index] === '' && index > 0) {
      event.preventDefault();
      this.digits.update((current) => {
        const next = [...current];
        next[index - 1] = '';
        return next;
      });
      this.emit();
      this.focusCell(index - 1);
      return;
    }
    if (event.key === 'ArrowLeft' && index > 0) {
      event.preventDefault();
      this.focusCell(index - 1);
      return;
    }
    if (event.key === 'ArrowRight' && index < this.length() - 1) {
      event.preventDefault();
      this.focusCell(index + 1);
    }
  }

  protected onPaste(event: ClipboardEvent): void {
    event.preventDefault();
    const pasted = (event.clipboardData?.getData('text') ?? '').replace(/\D/g, '');
    if (pasted === '') {
      return;
    }
    const length = this.length();
    const digitsOnly = pasted.slice(0, length);
    this.digits.set(Array.from({ length }, (_, i) => digitsOnly[i] ?? ''));
    this.emit();
    this.focusCell(Math.min(digitsOnly.length, length) - 1);
  }

  private focusCell(index: number): void {
    const cell = this.cells()[index];
    cell?.nativeElement.focus();
    cell?.nativeElement.select();
  }

  private emit(): void {
    const current = this.digits();
    const value = current.join('');
    this.lastEmittedValue = value;
    this.valueChange.emit(value);
    if (current.length > 0 && current.every((d) => d !== '')) {
      this.complete.emit(value);
    }
  }
}
