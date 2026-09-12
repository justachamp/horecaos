import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';

import { TPipe } from '../../core/i18n/t.pipe';

export interface DateRange {
  /** Inclusive, `YYYY-MM-DD`. */
  readonly start: string;
  /** Inclusive, `YYYY-MM-DD`. */
  readonly end: string;
}

function toIsoDate(date: Date): string {
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, '0');
  const day = String(date.getDate()).padStart(2, '0');
  return `${year}-${month}-${day}`;
}

function daysAgo(from: Date, days: number): Date {
  const copy = new Date(from);
  copy.setDate(copy.getDate() - days);
  return copy;
}

/**
 * A bounded date range, by preset or by hand (ADR 0101, row `X.11`).
 *
 * The gap this closes: no report in this console can be run over an
 * arbitrary range today, so "last Friday vs. this Friday" has no screen that
 * answers it. Four presets cover the common asks; the two `<input
 * type="date">` fields underneath cover the rest, and native date inputs are
 * kept deliberately for {@link DateRange} — unlike {@link q-time-input}, a
 * calendar picker's own chrome is exactly what an operator already knows how
 * to drive, and reimplementing it would be effort spent on the one part of
 * the browser's date UI that already reads the same way everywhere.
 *
 * `start`/`end` are inclusive `YYYY-MM-DD` in the viewer's local calendar —
 * there is no timezone conversion here, because a filter range is a set of
 * calendar dates, not a pair of instants.
 */
@Component({
  selector: 'q-date-range-picker',
  imports: [TPipe],
  templateUrl: './date-range-picker.html',
  styleUrl: './date-range-picker.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DateRangePicker {
  readonly start = input<string | null>(null);
  readonly end = input<string | null>(null);

  readonly rangeChange = output<DateRange>();

  protected setToday(): void {
    const today = toIsoDate(new Date());
    this.emit(today, today);
  }

  protected setYesterday(): void {
    const yesterday = toIsoDate(daysAgo(new Date(), 1));
    this.emit(yesterday, yesterday);
  }

  protected setLast7Days(): void {
    const now = new Date();
    this.emit(toIsoDate(daysAgo(now, 6)), toIsoDate(now));
  }

  protected setThisMonth(): void {
    const now = new Date();
    const firstOfMonth = new Date(now.getFullYear(), now.getMonth(), 1);
    this.emit(toIsoDate(firstOfMonth), toIsoDate(now));
  }

  protected onStartInput(value: string): void {
    if (value) {
      this.emit(value, this.end() ?? value);
    }
  }

  protected onEndInput(value: string): void {
    if (value) {
      this.emit(this.start() ?? value, value);
    }
  }

  private emit(start: string, end: string): void {
    this.rangeChange.emit(start <= end ? { start, end } : { start: end, end: start });
  }
}
