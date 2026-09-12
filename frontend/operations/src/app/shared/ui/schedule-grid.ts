import { ChangeDetectionStrategy, Component, inject, input, output } from '@angular/core';

import { I18n } from '../../core/i18n/i18n';
import { MessageKey } from '../../core/i18n/messages.en';
import { TPipe } from '../../core/i18n/t.pipe';
import { TimeInput } from './time-input';

/** `LocationServiceOperationsController.RuleResponse`/`RuleRequest`: one open window on one weekday. */
export interface ScheduleRule {
  readonly dayOfWeek: number;
  readonly opensAt: string;
  readonly closesAt: string;
}

/** `LocationServiceOperationsController.ExceptionResponse`: one calendar date's override. */
export interface ScheduleException {
  readonly date: string;
  readonly closedAllDay: boolean;
  readonly opensAt: string | null;
  readonly closesAt: string | null;
}

const DAYS: readonly number[] = [1, 2, 3, 4, 5, 6, 7];

/**
 * A weekly grid plus its dated exceptions (ADR 0101, row `X.11`), over
 * exactly the shape `LocationServiceOperationsController.serviceSummary`
 * already returns: `rules` (`{dayOfWeek, opensAt, closesAt}`) for the
 * standing weekly rule, `exceptions` (`{date, closedAllDay, opensAt,
 * closesAt}`) for a calendar date that overrides it. A merchant cannot edit
 * either from the console today — this is the first control that can, though
 * this wave does not wire it to `PUT /service-bindings` itself.
 *
 * Whole-set outputs, the same discipline `replacePreparationBands` already
 * keeps server-side: adding, editing or removing a window re-emits the
 * complete `rules`/`exceptions` array rather than a delta, so the caller's
 * PUT body is always exactly what the grid shows.
 */
@Component({
  selector: 'q-schedule-grid',
  imports: [TPipe, TimeInput],
  templateUrl: './schedule-grid.html',
  styleUrl: './schedule-grid.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ScheduleGrid {
  private readonly i18n = inject(I18n);

  readonly rules = input<readonly ScheduleRule[]>([]);
  readonly exceptions = input<readonly ScheduleException[]>([]);

  readonly rulesChange = output<readonly ScheduleRule[]>();
  readonly exceptionsChange = output<readonly ScheduleException[]>();

  protected readonly days = DAYS;

  protected dayLabel(day: number): string {
    return this.i18n.t(`ui.dayOfWeek.full.${day}` as MessageKey);
  }

  protected rulesFor(day: number): readonly ScheduleRule[] {
    return this.rules().filter((rule) => rule.dayOfWeek === day);
  }

  protected addWindow(day: number): void {
    this.rulesChange.emit([
      ...this.rules(),
      { dayOfWeek: day, opensAt: '09:00', closesAt: '18:00' },
    ]);
  }

  protected removeWindow(rule: ScheduleRule): void {
    this.rulesChange.emit(this.rules().filter((candidate) => candidate !== rule));
  }

  protected updateWindow(rule: ScheduleRule, patch: Partial<ScheduleRule>): void {
    this.rulesChange.emit(
      this.rules().map((candidate) =>
        candidate === rule ? { ...candidate, ...patch } : candidate,
      ),
    );
  }

  protected addException(date: string): void {
    if (!date || this.exceptions().some((exception) => exception.date === date)) {
      return;
    }
    this.exceptionsChange.emit([
      ...this.exceptions(),
      { date, closedAllDay: true, opensAt: null, closesAt: null },
    ]);
  }

  protected removeException(exception: ScheduleException): void {
    this.exceptionsChange.emit(this.exceptions().filter((candidate) => candidate !== exception));
  }

  protected updateException(exception: ScheduleException, patch: Partial<ScheduleException>): void {
    this.exceptionsChange.emit(
      this.exceptions().map((candidate) =>
        candidate === exception ? { ...candidate, ...patch } : candidate,
      ),
    );
  }

  protected toggleClosedAllDay(exception: ScheduleException, closedAllDay: boolean): void {
    this.updateException(
      exception,
      closedAllDay
        ? { closedAllDay: true, opensAt: null, closesAt: null }
        : { closedAllDay: false, opensAt: '09:00', closesAt: '18:00' },
    );
  }

  /** A stable row key for `@for` — dates and day/time pairs both repeat legitimately. */
  protected trackException = (_: number, exception: ScheduleException): string => exception.date;
}
