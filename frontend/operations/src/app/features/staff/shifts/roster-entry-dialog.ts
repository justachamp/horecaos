import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  inject,
  input,
  output,
  signal,
} from '@angular/core';

import {
  TimeZone,
  parseZonedDatetimeLocal,
  toZonedDatetimeLocal,
} from '../../../core/format/datetime';
import { I18n } from '../../../core/i18n/i18n';
import { TPipe } from '../../../core/i18n/t.pipe';
import { Modal } from '../../../shared/ui/modal';
import { MessageKey } from '../../../core/i18n/messages.en';
import {
  CANCEL_REASONS,
  EntryDraft,
  RosterEntry,
  ShiftPerson,
  ShiftReason,
} from './staff-shifts-api';

/** What the dialog hands back: a draft to save, or a request to cancel the entry with a reason. */
export type EntryDialogResult =
  | { readonly kind: 'save'; readonly draft: EntryDraft }
  | { readonly kind: 'cancel'; readonly reason: ShiftReason };

const REASON_KEYS: Readonly<Record<string, MessageKey>> = {
  PERSON_UNAVAILABLE: 'staff.shifts.reason.PERSON_UNAVAILABLE',
  SCHEDULE_CHANGED: 'staff.shifts.reason.SCHEDULE_CHANGED',
  ENTERED_IN_ERROR: 'staff.shifts.reason.ENTERED_IN_ERROR',
};

/**
 * «Добавить смену» / «Изменить смену» (ADR 0156, the rota): a person, a start, an end and a planned
 * break, all in the branch's own local time. A plan, never a source of paid time -- the dialog says so.
 *
 * An existing entry's person cannot change: to move the shift to someone else the manager cancels it
 * (with a reason from the closed list) and drafts another, so the trail says who was taken off the rota
 * and who was put on. The dialog never types a reason: it is picked.
 */
@Component({
  selector: 'q-roster-entry-dialog',
  imports: [TPipe, Modal],
  templateUrl: './roster-entry-dialog.html',
  styleUrl: './roster-entry-dialog.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class RosterEntryDialog implements OnInit {
  private readonly i18n = inject(I18n);

  readonly people = input.required<readonly ShiftPerson[]>();
  /** Set when editing an existing entry. */
  readonly entry = input<RosterEntry | null>(null);
  readonly zone = input.required<TimeZone>();
  /** The cell the manager clicked: a local date (`yyyy-MM-dd`) and, optionally, the person of that row. */
  readonly date = input<string | null>(null);
  readonly memberId = input<string | null>(null);
  readonly busy = input(false);
  readonly failure = input<string | null>(null);

  readonly result = output<EntryDialogResult>();
  readonly dismiss = output<void>();

  protected readonly selectedMember = signal('');
  protected readonly start = signal('');
  protected readonly end = signal('');
  protected readonly breakMinutes = signal('0');
  protected readonly cancelling = signal(false);
  protected readonly cancelReason = signal('');
  private readonly touched = signal(false);

  protected readonly cancelReasons = CANCEL_REASONS;

  protected readonly editing = computed(() => this.entry() !== null);
  protected readonly memberMissing = computed(() => this.touched() && this.selectedMember() === '');
  protected readonly windowInvalid = computed(() => {
    if (!this.start() || !this.end()) {
      return this.touched();
    }
    return this.end() <= this.start();
  });

  ngOnInit(): void {
    const entry = this.entry();
    if (entry !== null) {
      this.selectedMember.set(entry.person?.memberId ?? '');
      this.start.set(toZonedDatetimeLocal(new Date(entry.plannedStart), this.zone()));
      this.end.set(toZonedDatetimeLocal(new Date(entry.plannedEnd), this.zone()));
      this.breakMinutes.set(String(entry.plannedBreakMinutes));
      return;
    }
    this.selectedMember.set(this.memberId() ?? '');
    const date = this.date();
    if (date !== null) {
      this.start.set(`${date}T09:00`);
      this.end.set(`${date}T18:00`);
    }
  }

  protected personLabel(person: ShiftPerson): string {
    return person.name === null ? person.displayReference : `${person.name} · ${person.displayReference}`;
  }

  protected reasonLabel(reason: string): string {
    const key = REASON_KEYS[reason];
    return key === undefined ? reason : this.i18n.t(key);
  }

  protected setMember(value: string): void {
    this.selectedMember.set(value);
  }

  protected setStart(value: string): void {
    this.start.set(value);
  }

  protected setEnd(value: string): void {
    this.end.set(value);
  }

  protected setBreak(value: string): void {
    this.breakMinutes.set(value);
  }

  protected startCancelling(): void {
    this.cancelling.set(true);
  }

  protected setCancelReason(value: string): void {
    this.cancelReason.set(value);
  }

  protected save(): void {
    this.touched.set(true);
    if (this.selectedMember() === '' || !this.start() || !this.end() || this.windowInvalid()) {
      return;
    }
    const plannedBreakMinutes = Math.max(0, Math.min(240, Math.trunc(Number(this.breakMinutes()) || 0)));
    this.result.emit({
      kind: 'save',
      draft: {
        memberId: this.selectedMember(),
        plannedStart: parseZonedDatetimeLocal(this.start(), this.zone()).toISOString(),
        plannedEnd: parseZonedDatetimeLocal(this.end(), this.zone()).toISOString(),
        plannedBreakMinutes,
      },
    });
  }

  protected confirmCancel(): void {
    const reason = this.cancelReason();
    if (reason === '') {
      return;
    }
    this.result.emit({ kind: 'cancel', reason: reason as ShiftReason });
  }
}
