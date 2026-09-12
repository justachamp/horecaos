import {
  ChangeDetectionStrategy,
  Component,
  booleanAttribute,
  effect,
  input,
  output,
  signal,
} from '@angular/core';

/**
 * A percentage, held everywhere else as basis points (ADR 0101, row `X.10`).
 *
 * `promo-codes-page.ts` computes `Math.round(this.formPercent() * 100)` by
 * hand at its own call site to turn a typed "10" into the 1000 basis points
 * `pricing.coupon_codes` actually stores, and does the inverse nowhere —
 * that page only ever writes the value, so no round trip existed to get
 * wrong yet. This component owns both directions, once, so every future
 * percent field — a tariff surcharge multiplier, a loyalty rate — gets the
 * same rounding rather than a second hand-rolled `* 100`.
 *
 * {@link basisPoints} is the model, not the display string: 1000 renders as
 * `10`, and typing `12.5` emits `1250`. `min`/`max` are basis points too, so
 * a caller bounding "0–100%" passes `0`/`10000` unchanged from what the
 * backend already validates.
 */
@Component({
  selector: 'q-percent-input',
  templateUrl: './percent-input.html',
  styleUrl: './percent-input.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PercentInput {
  readonly basisPoints = input<number>(0);
  readonly min = input<number>(0);
  readonly max = input<number>(10_000);
  readonly ariaLabel = input<string | null>(null);
  readonly disabled = input(false, { transform: booleanAttribute });

  readonly basisPointsChange = output<number>();

  protected readonly displayText = signal(formatPercent(this.basisPoints()));

  private lastEmitted = this.basisPoints();

  constructor() {
    effect(() => {
      const current = this.basisPoints();
      if (current === this.lastEmitted) {
        return;
      }
      this.lastEmitted = current;
      this.displayText.set(formatPercent(current));
    });
  }

  protected onInput(raw: string): void {
    this.displayText.set(raw);
    const parsed = Number.parseFloat(raw.replace(',', '.'));
    // An empty or otherwise unparsable field is not "leave the model alone" —
    // that would let a cleared field silently keep submitting the pre-edit
    // percentage (see MoneyInput.onInput, which emits 0 on empty the same
    // way). Treat it as zero and clamp exactly like a valid value.
    const basisPoints = Number.isFinite(parsed) ? Math.round(parsed * 100) : 0;
    const clamped = Math.min(this.max(), Math.max(this.min(), basisPoints));
    this.lastEmitted = clamped;
    this.basisPointsChange.emit(clamped);
  }

  protected onBlur(): void {
    // Whatever the user left the field on, show it back normalised — "10."
    // becomes "10", "010" becomes "10". Reads the last value *this component*
    // computed, not the `basisPoints` input: nothing guarantees a bound
    // parent has echoed the emission back into that input before blur fires.
    this.displayText.set(formatPercent(this.lastEmitted));
  }
}

function formatPercent(basisPoints: number): string {
  const value = Math.round(basisPoints) / 100;
  return String(value);
}
