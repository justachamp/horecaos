import {
  ChangeDetectionStrategy,
  Component,
  booleanAttribute,
  computed,
  inject,
  input,
  output,
} from '@angular/core';

import { formatQuantity } from '../../core/format/quantity';
import { I18n } from '../../core/i18n/i18n';

/**
 * A quantity control with visible bounds (ADR 0101, row `X.29`).
 *
 * The gap this replaces is real: a bare `<input type="number">` spinner
 * requires selecting the text inside it to change a value on a touch screen,
 * and a modifier's min/max or a portion band's limits are never shown next to
 * the field that enforces them. This renders the ceiling and the floor beside
 * the control instead of leaving an operator to discover them from a rejected
 * value.
 *
 * Presentational, like every primitive in this directory: {@link value} is an
 * input, {@link valueChange} an output, and clamping to {@link min}/{@link max}
 * happens here so every caller gets the same behaviour at the boundary rather
 * than reimplementing it.
 *
 * **By the portion (ADR 0137).** A splittable variant is ordered in the steps its
 * portion size names, so {@link step} may be a fraction. Then the value is written
 * the way the console writes any quantity (`0,5`, never `0.5000000000000001`), a
 * typed value takes a comma or a point and snaps to the nearest step — the cart
 * refuses anything that is not a multiple of the portion, so the control must not
 * offer it — and nothing it emits carries floating-point noise. A whole step
 * behaves exactly as it always did.
 */
@Component({
  selector: 'q-number-stepper',
  templateUrl: './number-stepper.html',
  styleUrl: './number-stepper.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class NumberStepper {
  private readonly i18n = inject(I18n);

  readonly value = input<number>(0);
  readonly min = input<number | null>(null);
  readonly max = input<number | null>(null);
  readonly step = input<number>(1);
  readonly ariaLabel = input<string | null>(null);
  readonly disabled = input(false, { transform: booleanAttribute });

  readonly valueChange = output<number>();

  protected readonly fractional = computed(() => !Number.isInteger(this.step()));

  /** The value as the console writes a quantity: `2`, `0,5`. */
  protected readonly valueText = computed(() => this.write(this.value()));

  /** `{min}–{max}`, `≥{min}`, `≤{max}`, or empty when neither bound is set. */
  protected readonly boundsHint = computed(() => {
    const min = this.min();
    const max = this.max();
    if (min !== null && max !== null) {
      return `${this.write(min)}–${this.write(max)}`;
    }
    if (min !== null) {
      return `≥${this.write(min)}`;
    }
    if (max !== null) {
      return `≤${this.write(max)}`;
    }
    return '';
  });

  protected readonly canDecrement = computed(() => {
    const min = this.min();
    return !this.disabled() && (min === null || tidy(this.value() - this.step()) >= min);
  });

  protected readonly canIncrement = computed(() => {
    const max = this.max();
    return !this.disabled() && (max === null || tidy(this.value() + this.step()) <= max);
  });

  protected decrement(): void {
    if (this.canDecrement()) {
      this.emit(this.value() - this.step());
    }
  }

  protected increment(): void {
    if (this.canIncrement()) {
      this.emit(this.value() + this.step());
    }
  }

  protected onTyped(raw: string): void {
    const parsed = this.fractional()
      ? snapToStep(Number.parseFloat(raw.trim().replace(',', '.')), this.step())
      : Number.parseInt(raw, 10);
    if (Number.isFinite(parsed)) {
      this.emit(parsed);
    }
  }

  private write(quantity: number): string {
    return this.fractional() || !Number.isInteger(quantity)
      ? formatQuantity(quantity, this.i18n.locale())
      : String(quantity);
  }

  private emit(next: number): void {
    const min = this.min();
    const max = this.max();
    let clamped = tidy(next);
    if (min !== null) {
      clamped = Math.max(min, clamped);
    }
    if (max !== null) {
      clamped = Math.min(max, clamped);
    }
    this.valueChange.emit(clamped);
  }
}

/** Thousandths, the scale a quantity is stored at, so `0.2 + 0.1` is `0.3` and not `0.30000000000000004`. */
function tidy(value: number): number {
  return Math.round(value * 1000) / 1000;
}

/** The nearest whole multiple of `step`, at least one step: a portion cannot be zero. */
function snapToStep(value: number, step: number): number {
  return tidy(Math.max(1, Math.round(value / step)) * step);
}
