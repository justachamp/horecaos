import {
  ChangeDetectionStrategy,
  Component,
  booleanAttribute,
  computed,
  input,
  output,
} from '@angular/core';

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
 */
@Component({
  selector: 'q-number-stepper',
  templateUrl: './number-stepper.html',
  styleUrl: './number-stepper.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class NumberStepper {
  readonly value = input<number>(0);
  readonly min = input<number | null>(null);
  readonly max = input<number | null>(null);
  readonly step = input<number>(1);
  readonly ariaLabel = input<string | null>(null);
  readonly disabled = input(false, { transform: booleanAttribute });

  readonly valueChange = output<number>();

  /** `{min}–{max}`, `≥{min}`, `≤{max}`, or empty when neither bound is set. */
  protected readonly boundsHint = computed(() => {
    const min = this.min();
    const max = this.max();
    if (min !== null && max !== null) {
      return `${min}–${max}`;
    }
    if (min !== null) {
      return `≥${min}`;
    }
    if (max !== null) {
      return `≤${max}`;
    }
    return '';
  });

  protected readonly canDecrement = computed(() => {
    const min = this.min();
    return !this.disabled() && (min === null || this.value() - this.step() >= min);
  });

  protected readonly canIncrement = computed(() => {
    const max = this.max();
    return !this.disabled() && (max === null || this.value() + this.step() <= max);
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
    const parsed = Number.parseInt(raw, 10);
    if (Number.isFinite(parsed)) {
      this.emit(parsed);
    }
  }

  private emit(next: number): void {
    const min = this.min();
    const max = this.max();
    let clamped = next;
    if (min !== null) {
      clamped = Math.max(min, clamped);
    }
    if (max !== null) {
      clamped = Math.min(max, clamped);
    }
    this.valueChange.emit(clamped);
  }
}
