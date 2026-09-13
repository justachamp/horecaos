import { ChangeDetectionStrategy, Component, inject, input, output } from '@angular/core';

import { I18n } from '../../core/i18n/i18n';
import { MessageKey } from '../../core/i18n/messages.en';

/** Monday first, matching every `dayOfWeek` this platform sends — `@Min(1) @Max(7)` in `LocationServiceOperationsController`. */
const DAYS: readonly number[] = [1, 2, 3, 4, 5, 6, 7];

/**
 * A week's seven days, toggled (ADR 0101, row `X.11`).
 *
 * `mode: 'single'` is what a form with exactly one required weekday needs —
 * `capacity-page`'s throughput window, today a `<select>` — and clicking any
 * day there simply replaces the selection rather than allowing it down to
 * zero. `mode: 'multiple'` is what a weekly opening-hours rule needs
 * (`q-schedule-grid`), where a rule commonly spans several days at once and
 * every day starts independently toggleable.
 *
 * Day numbers follow `LocationServiceOperationsController`'s own convention
 * (`dayOfWeek`, 1 = Monday … 7 = Sunday) rather than `delivery_tariffs`'
 * bitmask, because {@link q-schedule-grid} binds directly to that
 * controller's `ServiceSummaryResponse` shape.
 */
@Component({
  selector: 'q-day-of-week-toggle',
  templateUrl: './day-of-week-toggle.html',
  styleUrl: './day-of-week-toggle.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DayOfWeekToggle {
  private readonly i18n = inject(I18n);

  readonly selected = input<ReadonlySet<number>>(new Set());
  readonly mode = input<'single' | 'multiple'>('multiple');

  readonly selectedChange = output<ReadonlySet<number>>();

  protected readonly days = DAYS;

  protected isSelected(day: number): boolean {
    return this.selected().has(day);
  }

  protected shortLabel(day: number): string {
    return this.i18n.t(`ui.dayOfWeek.short.${day}` as MessageKey);
  }

  protected fullLabel(day: number): string {
    return this.i18n.t(`ui.dayOfWeek.full.${day}` as MessageKey);
  }

  protected toggle(day: number): void {
    if (this.mode() === 'single') {
      this.selectedChange.emit(new Set([day]));
      return;
    }
    const next = new Set(this.selected());
    if (next.has(day)) {
      next.delete(day);
    } else {
      next.add(day);
    }
    this.selectedChange.emit(next);
  }
}
