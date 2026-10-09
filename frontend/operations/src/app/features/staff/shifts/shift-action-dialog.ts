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
import { MessageKey } from '../../../core/i18n/messages.en';
import { TPipe } from '../../../core/i18n/t.pipe';
import { Modal } from '../../../shared/ui/modal';
import {
  APPROVAL_REASONS,
  ENTRY_REASONS,
  ShiftPerson,
  ShiftReason,
  StaffShift,
  VOID_REASONS,
} from './staff-shifts-api';

export type ShiftActionKind = 'open' | 'close' | 'correct' | 'void' | 'approve';

export type ShiftActionResult =
  | {
      readonly kind: 'open';
      readonly memberId: string;
      readonly startedAt: string;
      readonly reason: ShiftReason | null;
    }
  | {
      readonly kind: 'close';
      readonly endedAt: string;
      readonly breakSeconds: number | null;
      readonly reason: ShiftReason | null;
    }
  | {
      readonly kind: 'correct';
      readonly startedAt: string | null;
      readonly endedAt: string | null;
      readonly breakSeconds: number | null;
      readonly reason: ShiftReason;
    }
  | { readonly kind: 'void'; readonly reason: ShiftReason }
  | { readonly kind: 'approve'; readonly reason: ShiftReason };

const REASON_KEYS: Readonly<Record<ShiftReason, MessageKey>> = {
  FORGOT_CLOCK_IN: 'staff.shifts.reason.FORGOT_CLOCK_IN',
  FORGOT_CLOCK_OUT: 'staff.shifts.reason.FORGOT_CLOCK_OUT',
  SYSTEM_PROBLEM: 'staff.shifts.reason.SYSTEM_PROBLEM',
  PLAN_CHANGED: 'staff.shifts.reason.PLAN_CHANGED',
  SHIFT_SWAP: 'staff.shifts.reason.SHIFT_SWAP',
  COVERED_SHORTAGE: 'staff.shifts.reason.COVERED_SHORTAGE',
  ENTERED_IN_ERROR: 'staff.shifts.reason.ENTERED_IN_ERROR',
  DUPLICATE: 'staff.shifts.reason.DUPLICATE',
  DID_NOT_WORK: 'staff.shifts.reason.DID_NOT_WORK',
  WORKED_AS_RECORDED: 'staff.shifts.reason.WORKED_AS_RECORDED',
  PERSON_UNAVAILABLE: 'staff.shifts.reason.PERSON_UNAVAILABLE',
  SCHEDULE_CHANGED: 'staff.shifts.reason.SCHEDULE_CHANGED',
};

const TITLES: Readonly<Record<ShiftActionKind, MessageKey>> = {
  open: 'staff.shifts.action.title.open',
  close: 'staff.shifts.action.title.close',
  correct: 'staff.shifts.action.title.correct',
  void: 'staff.shifts.action.title.void',
  approve: 'staff.shifts.action.title.approve',
};

const LEADS: Readonly<Record<ShiftActionKind, MessageKey>> = {
  open: 'staff.shifts.action.lead.open',
  close: 'staff.shifts.action.lead.close',
  correct: 'staff.shifts.action.lead.correct',
  void: 'staff.shifts.action.lead.void',
  approve: 'staff.shifts.action.lead.approve',
};

const CONFIRMS: Readonly<Record<ShiftActionKind, MessageKey>> = {
  open: 'staff.shifts.action.confirm.open',
  close: 'staff.shifts.action.confirm.close',
  correct: 'staff.shifts.action.confirm.correct',
  void: 'staff.shifts.action.confirm.void',
  approve: 'staff.shifts.action.confirm.approve',
};

/** A time older than this needs a reason (`StaffShiftService.PAST_GRACE`); the platform checks again. */
const PAST_GRACE_MS = 5 * 60_000;

/**
 * One dialog for the five things a manager does to a shift (ADR 0156): record that someone started,
 * record that they finished, correct the hours, void a mistaken shift, and approve one whose variance
 * was over the threshold.
 *
 * **A reason is picked, never typed** -- from a short closed list that fits the act (a correction's
 * reasons are not an approval's), so a reason can be counted and an audit fact read without a free-text
 * field that holds a person's name. A time in the past needs one; the platform checks that again.
 * Approval is one shift at a time on purpose: there is no select-all, so the guarantee is that a
 * person looked at this shift.
 */
@Component({
  selector: 'q-shift-action-dialog',
  imports: [TPipe, Modal],
  templateUrl: './shift-action-dialog.html',
  styleUrl: './shift-action-dialog.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ShiftActionDialog implements OnInit {
  private readonly i18n = inject(I18n);

  readonly kind = input.required<ShiftActionKind>();
  readonly zone = input.required<TimeZone>();
  readonly people = input<readonly ShiftPerson[]>([]);
  readonly shift = input<StaffShift | null>(null);
  readonly memberId = input<string | null>(null);
  /** Who the shift is about, for the heading: a reference or a name. */
  readonly subject = input<string | null>(null);
  readonly busy = input(false);
  readonly failure = input<string | null>(null);

  readonly result = output<ShiftActionResult>();
  readonly dismiss = output<void>();

  protected readonly selectedMember = signal('');
  protected readonly start = signal('');
  protected readonly end = signal('');
  protected readonly breakMinutes = signal('');
  protected readonly reason = signal('');
  private readonly touched = signal(false);

  protected readonly reasons = computed<readonly ShiftReason[]>(() => {
    switch (this.kind()) {
      case 'void':
        return VOID_REASONS;
      case 'approve':
        return APPROVAL_REASONS;
      default:
        return ENTRY_REASONS;
    }
  });

  protected readonly titleKey = computed(() => TITLES[this.kind()]);
  protected readonly leadKey = computed(() => LEADS[this.kind()]);
  protected readonly confirmKey = computed(() => CONFIRMS[this.kind()]);
  protected readonly showsStart = computed(
    () => this.kind() === 'open' || this.kind() === 'correct',
  );
  protected readonly showsEnd = computed(
    () => this.kind() === 'close' || this.kind() === 'correct',
  );
  protected readonly showsBreak = computed(
    () => this.kind() === 'close' || this.kind() === 'correct',
  );
  protected readonly reasonRequired = computed(() => {
    switch (this.kind()) {
      case 'open':
        return this.isPast(this.start());
      case 'close':
        return this.isPast(this.end());
      default:
        return true;
    }
  });
  protected readonly reasonMissing = computed(
    () => this.touched() && this.reasonRequired() && this.reason() === '',
  );

  ngOnInit(): void {
    const now = toZonedDatetimeLocal(new Date(), this.zone());
    const shift = this.shift();
    switch (this.kind()) {
      case 'open':
        this.selectedMember.set(this.memberId() ?? '');
        this.start.set(now);
        break;
      case 'close':
        this.end.set(now);
        break;
      case 'correct':
        if (shift !== null) {
          this.start.set(toZonedDatetimeLocal(new Date(shift.startedAt), this.zone()));
          this.end.set(
            shift.endedAt === null
              ? ''
              : toZonedDatetimeLocal(new Date(shift.endedAt), this.zone()),
          );
          this.breakMinutes.set(
            shift.endedAt === null ? '' : String(Math.round(shift.breakSeconds / 60)),
          );
        }
        break;
      default:
        break;
    }
  }

  protected personLabel(person: ShiftPerson): string {
    return person.name === null
      ? person.displayReference
      : `${person.name} · ${person.displayReference}`;
  }

  protected reasonLabel(reason: ShiftReason): string {
    return this.i18n.t(REASON_KEYS[reason]);
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

  protected setReason(value: string): void {
    this.reason.set(value);
  }

  private isPast(local: string): boolean {
    if (!local) {
      return false;
    }
    return parseZonedDatetimeLocal(local, this.zone()).getTime() < Date.now() - PAST_GRACE_MS;
  }

  private chosenReason(): ShiftReason | null {
    return this.reason() === '' ? null : (this.reason() as ShiftReason);
  }

  protected submit(): void {
    this.touched.set(true);
    const reason = this.chosenReason();
    if (this.reasonRequired() && reason === null) {
      return;
    }
    const zone = this.zone();
    switch (this.kind()) {
      case 'open':
        if (this.selectedMember() === '' || !this.start()) {
          return;
        }
        this.result.emit({
          kind: 'open',
          memberId: this.selectedMember(),
          startedAt: parseZonedDatetimeLocal(this.start(), zone).toISOString(),
          reason,
        });
        return;
      case 'close': {
        if (!this.end()) {
          return;
        }
        const minutes = this.breakMinutes().trim();
        this.result.emit({
          kind: 'close',
          endedAt: parseZonedDatetimeLocal(this.end(), zone).toISOString(),
          breakSeconds: minutes === '' ? null : Math.max(0, Math.round(Number(minutes) * 60)),
          reason,
        });
        return;
      }
      case 'correct': {
        const shift = this.shift();
        if (shift === null || reason === null) {
          return;
        }
        const minutes = this.breakMinutes().trim();
        this.result.emit({
          kind: 'correct',
          startedAt: this.start()
            ? parseZonedDatetimeLocal(this.start(), zone).toISOString()
            : null,
          endedAt: this.end() ? parseZonedDatetimeLocal(this.end(), zone).toISOString() : null,
          breakSeconds: minutes === '' ? null : Math.max(0, Math.round(Number(minutes) * 60)),
          reason,
        });
        return;
      }
      case 'void':
        if (reason !== null) {
          this.result.emit({ kind: 'void', reason });
        }
        return;
      case 'approve':
        if (reason !== null) {
          this.result.emit({ kind: 'approve', reason });
        }
        return;
    }
  }
}
