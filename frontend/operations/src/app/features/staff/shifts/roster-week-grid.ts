import { ChangeDetectionStrategy, Component, computed, inject, input, output } from '@angular/core';

import { TimeZone, formatTime, toZonedDatetimeLocal } from '../../../core/format/datetime';
import { I18n } from '../../../core/i18n/i18n';
import { MessageKey } from '../../../core/i18n/messages.en';
import { TPipe } from '../../../core/i18n/t.pipe';
import { RosterEntry, RosterStatus, ShiftPerson } from './staff-shifts-api';

/** One day column of the grid. */
export interface GridDay {
  readonly iso: string;
  readonly label: string;
  readonly isToday: boolean;
}

/** One person's row: the people on the rota this week, plus whoever the manager added to it. */
export interface GridRow {
  readonly memberId: string;
  readonly person: ShiftPerson;
  readonly cells: readonly { readonly day: GridDay; readonly entries: readonly RosterEntry[] }[];
}

const STATUS_KEYS: Readonly<Record<RosterStatus, MessageKey>> = {
  DRAFT: 'staff.shifts.status.DRAFT',
  PUBLISHED: 'staff.shifts.status.PUBLISHED',
  CONSUMED: 'staff.shifts.status.CONSUMED',
  MISSED: 'staff.shifts.status.MISSED',
  CANCELLED: 'staff.shifts.status.CANCELLED',
};

/**
 * «Неделя» (ADR 0156, operations IA 9.6): the branch's rota as a grid, a row per person and a column
 * per day.
 *
 * **An empty cell is a dashed outline** -- a button that adds a shift for that person on that day --
 * so what is missing from the rota is visible and what is planned is solid. **Today is a guard, not a
 * colour:** the today column carries {@code aria-current="date"} and is the only one whose gaps mean
 * anything yet; a future day is never rendered as missed. **Keyboard:** the arrow keys move between
 * cells, Enter and Space act on the focused one (they are buttons), and Home and End jump to a row's
 * ends. A plan is never a source of paid time, and the grid says so in its caption.
 */
@Component({
  selector: 'q-roster-week-grid',
  imports: [TPipe],
  templateUrl: './roster-week-grid.html',
  styleUrl: './roster-week-grid.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class RosterWeekGrid {
  private readonly i18n = inject(I18n);

  readonly days = input.required<readonly GridDay[]>();
  readonly entries = input.required<readonly RosterEntry[]>();
  /** Everyone the manager may roster here; a row exists for those with an entry or added by hand. */
  readonly extraPeople = input<readonly ShiftPerson[]>([]);
  readonly zone = input.required<TimeZone>();
  readonly canManage = input(false);

  readonly add = output<{ readonly date: string; readonly memberId: string | null }>();
  readonly open = output<RosterEntry>();

  protected readonly rows = computed<readonly GridRow[]>(() => {
    const byMember = new Map<string, { person: ShiftPerson; entries: RosterEntry[] }>();
    for (const person of this.extraPeople()) {
      byMember.set(person.memberId, { person, entries: [] });
    }
    for (const entry of this.entries()) {
      if (entry.person === null) {
        continue;
      }
      const row = byMember.get(entry.person.memberId) ?? { person: entry.person, entries: [] };
      row.entries.push(entry);
      byMember.set(entry.person.memberId, row);
    }
    const days = this.days();
    return [...byMember.values()]
      .filter((row) => row.entries.length > 0 || this.extraPeople().some((p) => p.memberId === row.person.memberId))
      .sort((a, b) => this.label(a.person).localeCompare(this.label(b.person)))
      .map((row) => ({
        memberId: row.person.memberId,
        person: row.person,
        cells: days.map((day) => ({
          day,
          entries: row.entries
            .filter((entry) => this.dayOf(entry) === day.iso)
            .sort((a, b) => a.plannedStart.localeCompare(b.plannedStart)),
        })),
      }));
  });

  protected label(person: ShiftPerson): string {
    return person.name ?? person.displayReference;
  }

  protected dayOf(entry: RosterEntry): string {
    return toZonedDatetimeLocal(new Date(entry.plannedStart), this.zone()).slice(0, 10);
  }

  protected window(entry: RosterEntry): string {
    const zone = this.zone();
    return `${formatTime(new Date(entry.plannedStart), zone)}–${formatTime(new Date(entry.plannedEnd), zone)}`;
  }

  protected statusLabel(status: RosterStatus): string {
    return this.i18n.t(STATUS_KEYS[status]);
  }

  /** Arrow keys, Home and End move between the grid's cells; the cells themselves are buttons. */
  protected onKeydown(event: KeyboardEvent, rowIndex: number, columnIndex: number): void {
    const root = (event.currentTarget as HTMLElement).closest('table');
    if (root === null) {
      return;
    }
    let row = rowIndex;
    let column = columnIndex;
    switch (event.key) {
      case 'ArrowRight':
        column += 1;
        break;
      case 'ArrowLeft':
        column -= 1;
        break;
      case 'ArrowDown':
        row += 1;
        break;
      case 'ArrowUp':
        row -= 1;
        break;
      case 'Home':
        column = 0;
        break;
      case 'End':
        column = this.days().length - 1;
        break;
      default:
        return;
    }
    const target = root.querySelector<HTMLElement>(`[data-cell="${row}:${column}"]`);
    if (target !== null) {
      event.preventDefault();
      target.focus();
    }
  }

  protected addFor(day: GridDay, memberId: string): void {
    if (this.canManage()) {
      this.add.emit({ date: day.iso, memberId });
    }
  }
}
