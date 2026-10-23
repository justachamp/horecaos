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

/**
 * The digits an operator types instead of a colon (brands-and-locations.md §4.7: «Typing 1000 in a time field
 * means 10:00»). Four digits are a whole time the moment they are typed. Fewer are not, because `100` may be
 * on its way to `1000`; they are completed when the field is left: three digits are `Hmm` (`930` is `09:30`),
 * one or two are the whole hour (`9` is `09:00`). Null when the digits make no time.
 */
export function completeTypedTime(raw: string, leaving: boolean): string | null {
  const digits = raw.trim();
  let candidate: string | null = null;
  if (/^\d{4}$/.test(digits)) {
    candidate = `${digits.slice(0, 2)}:${digits.slice(2)}`;
  } else if (leaving && /^\d{3}$/.test(digits)) {
    candidate = `0${digits[0]}:${digits.slice(1)}`;
  } else if (leaving && /^\d{1,2}$/.test(digits)) {
    candidate = `${digits.padStart(2, '0')}:00`;
  }
  return candidate !== null && TIME_PATTERN.test(candidate) ? candidate : null;
}

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
 * field blurs back to the last valid value. Digits without the colon are a
 * shorthand ({@link completeTypedTime}): `1000` is `10:00`.
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
      return;
    }
    const completed = completeTypedTime(raw, false);
    if (completed !== null) {
      this.draft.set(completed);
      this.emit(completed);
    }
  }

  protected onBlur(): void {
    if (TIME_PATTERN.test(this.draft())) {
      return;
    }
    const completed = completeTypedTime(this.draft(), true);
    if (completed !== null) {
      this.draft.set(completed);
      this.emit(completed);
      return;
    }
    this.draft.set(this.lastEmitted);
  }

  protected onKeydown(event: KeyboardEvent): void {
    if (
      (event.key !== 'ArrowUp' && event.key !== 'ArrowDown') ||
      event.altKey ||
      event.ctrlKey ||
      event.metaKey
    ) {
      // A modified arrow belongs to whoever asked for it (Alt+Down copies a day in the hours editor).
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
