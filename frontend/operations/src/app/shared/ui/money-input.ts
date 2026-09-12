import {
  ChangeDetectionStrategy,
  Component,
  booleanAttribute,
  effect,
  inject,
  input,
  output,
  signal,
} from '@angular/core';

import { formatMoney } from '../../core/format/money';
import { I18n } from '../../core/i18n/i18n';
import { TPipe } from '../../core/i18n/t.pipe';

/**
 * A som amount, grouped as it is typed (ADR 0101, row `X.10`).
 *
 * The gap this closes: a tariff field today is a bare `<input type="number">`
 * spinner — no thousands grouping, no currency suffix, a pasted `125 000`
 * rejected outright by the browser's own number parser, and nothing stopping
 * a decimal point on a currency that does not have one. `money.ts`'s own
 * doc records the production incident a decimal-aware formatter already
 * caused on the *display* side; this is the same discipline applied to
 * *entry* — UZS has no minor unit, so every character that is not a digit is
 * simply not part of the number and is dropped, never reinterpreted as
 * cents.
 *
 * Always emits an integer `…Minor` (whole som — see `money.ts`), never a
 * float, and the grouping is `formatMoney`'s own NBSP grouping so a value
 * shown here and a value shown on a report never disagree by a hair space.
 */
@Component({
  selector: 'q-money-input',
  imports: [TPipe],
  templateUrl: './money-input.html',
  styleUrl: './money-input.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class MoneyInput {
  private readonly i18n = inject(I18n);

  /** Whole som. Integer; UZS carries no minor unit (`money.ts`). */
  readonly valueMinor = input<number>(0);
  readonly ariaLabel = input<string | null>(null);
  readonly disabled = input(false, { transform: booleanAttribute });

  readonly valueMinorChange = output<number>();

  protected readonly displayText = signal(this.formatted(this.valueMinor()));

  private lastEmitted = this.valueMinor();

  constructor() {
    effect(() => {
      const current = this.valueMinor();
      if (current === this.lastEmitted) {
        // Our own emission echoed back through the parent's signal — leave
        // the field exactly as the operator left it rather than reformatting
        // under their cursor.
        return;
      }
      this.lastEmitted = current;
      this.displayText.set(this.formatted(current));
    });
  }

  protected onInput(raw: string): void {
    // Every character that is not a digit — spaces, NBSP, a dot, a comma, the
    // suffix text pasted along with the number — is noise, not data. This is
    // simultaneously the paste-tolerance behaviour (`"125 000"` groups down
    // to digits `1`,`2`,`5`,`0`,`0`,`0`) and the decimal-rejection behaviour
    // (`"125.50"`'s `.` is dropped, never read as cents a currency with no
    // minor unit cannot have).
    //
    // A leading `-` is refused rather than stripped along with the rest of
    // the noise: silently dropping the sign would turn a typed "-500" into
    // 500, the exact opposite of what the operator typed. This field has no
    // negative-amount call site today (a delivery fee, a discount amount),
    // so a sign is treated as an invalid entry and the value resets to 0,
    // the same "cleared field" outcome an empty input already produces.
    const isNegative = raw.trimStart().startsWith('-');
    const digitsOnly = raw.replace(/[^\d]/g, '');
    const value =
      isNegative || digitsOnly === '' ? 0 : Math.min(Number(digitsOnly), Number.MAX_SAFE_INTEGER);
    this.lastEmitted = value;
    this.displayText.set(this.formatted(value));
    this.valueMinorChange.emit(value);
  }

  private formatted(amountMinor: number): string {
    return formatMoney({ amountMinor, currency: 'UZS' }, this.i18n.locale());
  }
}
