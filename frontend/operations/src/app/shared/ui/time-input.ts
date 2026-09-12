import {
  ChangeDetectionStrategy,
  Component,
  booleanAttribute,
  effect,
  input,
  output,
  signal,
} from '@angular/core';

import { TPipe } from '../../core/i18n/t.pipe';

const TIME_PATTERN = /^([01]\d|2[0-3]):([0-5]\d)$/;

/** Minutes since midnight, wrapping at the day boundary. */
function toMinutes(time: string): number {
  const match = TIME_PATTERN.exec(time);
  if (!match) {
    return 0;
  }
  return Number(match[1]) * 60 + Number(match[2]);
}

function fromMinutes(totalMinutes: number): string {
  const wrapped = ((totalMinutes % 1440) + 1440) % 1440;
  const hours = String(Math.floor(wrapped / 60)).padStart(2, '0');
  const minutes = String(wrapped % 60).padStart(2, '0');
  return `${hours}:${minutes}`;
}

/**
 * A 24-hour `HH:mm` field with arrow-key stepping (ADR 0101, row `X.11`).
 *
 * Not a native `<input type="time">`: this console is one visual system
 * across three browsers and three operating systems, and a native time
 * control renders — and keys — differently in each. This is the same
 * "byte-identical output" argument `datetime.ts` already makes for display;
 * here it applies to editing.
 *
 * `ArrowUp`/`ArrowDown` move by {@link step} minutes (default 1), wrapping
 * across midnight rather than clamping, because a courier's shift or a
 * kitchen's peak window commonly does cross it. Typed text is accepted only
 * once it matches `HH:mm` in full; anything else is held until it does or the
 * field blurs back to the last valid value.
 */
@Component({
  selector: 'q-time-input',
  imports: [TPipe],
  templateUrl: './time-input.html',
  styleUrl: './time-input.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class TimeInput {
  readonly value = input<string>('00:00');
  readonly step = input<number>(1);
  readonly ariaLabel = input<string | null>(null);
  readonly disabled = input(false, { transform: booleanAttribute });

  readonly valueChange = output<string>();

  protected readonly draft = signal(this.value());
  private lastEmitted = this.value();

  constructor() {
    effect(() => {
      const current = this.value();
      if (current === this.lastEmitted) {
        return;
      }
      this.lastEmitted = current;
      this.draft.set(current);
    });
  }

  protected onTyped(raw: string): void {
    this.draft.set(raw);
    if (TIME_PATTERN.test(raw)) {
      this.emit(raw);
    }
  }

  protected onBlur(): void {
    if (!TIME_PATTERN.test(this.draft())) {
      this.draft.set(this.lastEmitted);
    }
  }

  protected onKeydown(event: KeyboardEvent): void {
    if (event.key !== 'ArrowUp' && event.key !== 'ArrowDown') {
      return;
    }
    event.preventDefault();
    const base = TIME_PATTERN.test(this.draft()) ? this.draft() : this.lastEmitted;
    const delta = event.key === 'ArrowUp' ? this.step() : -this.step();
    const next = fromMinutes(toMinutes(base) + delta);
    this.draft.set(next);
    this.emit(next);
  }

  private emit(time: string): void {
    this.lastEmitted = time;
    this.valueChange.emit(time);
  }
}
