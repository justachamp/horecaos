import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  inject,
  signal,
} from '@angular/core';

import { ApiError } from '../../../core/api/problem-details';
import { CurrentLocation } from '../../../core/auth/current-location';
import { CurrentTenant } from '../../../core/auth/current-tenant';
import { SessionCapabilities } from '../../../core/auth/session-capabilities';
import {
  TimeZone,
  formatDateTime,
  formatDuration,
  formatTime,
  toZonedDatetimeLocal,
} from '../../../core/format/datetime';
import { I18n } from '../../../core/i18n/i18n';
import { MessageKey } from '../../../core/i18n/messages.en';
import { TPipe } from '../../../core/i18n/t.pipe';
import { ConfigurationApi } from '../../settings/configuration-api';
import { describeApiError } from '../../orders/order-errors';
import { AttendanceNoticeDialog } from './attendance-notice-dialog';
import { AttendanceStatus } from './attendance-status';
import { EntryDialogResult, RosterEntryDialog } from './roster-entry-dialog';
import { GridDay, RosterWeekGrid } from './roster-week-grid';
import { ShiftActionDialog, ShiftActionKind, ShiftActionResult } from './shift-action-dialog';
import {
  RosterEntry,
  ShiftPerson,
  StaffShift,
  StaffShiftsApi,
  TodayList,
  TodayRow,
  TodayStatus,
  ShiftStatus,
} from './staff-shifts-api';

type LoadState = 'loading' | 'off' | 'denied' | 'error' | 'ready';
type Tab = 'week' | 'today' | 'timesheet';

/** The pick-up point of a dialog: which act, on which row, for whom. */
interface ActionTarget {
  readonly kind: ShiftActionKind;
  readonly shift: { readonly id: string; readonly version: number } | null;
  readonly full: StaffShift | null;
  readonly memberId: string | null;
  readonly subject: string | null;
}

interface EntryTarget {
  readonly entry: RosterEntry | null;
  readonly date: string | null;
  readonly memberId: string | null;
}

const TODAY_KEYS: Readonly<Record<TodayStatus, MessageKey>> = {
  LATE: 'staff.shifts.today.status.LATE',
  MISSED_TODAY: 'staff.shifts.today.status.MISSED_TODAY',
  WORKING_WITHOUT_SHIFT: 'staff.shifts.today.status.WORKING_WITHOUT_SHIFT',
  OPEN_TOO_LONG: 'staff.shifts.today.status.OPEN_TOO_LONG',
  WORKING: 'staff.shifts.today.status.WORKING',
  CLOSED: 'staff.shifts.today.status.CLOSED',
  SCHEDULED: 'staff.shifts.today.status.SCHEDULED',
};

const SHIFT_KEYS: Readonly<Record<ShiftStatus, MessageKey>> = {
  OPEN: 'staff.shifts.shiftStatus.OPEN',
  AWAITING_APPROVAL: 'staff.shifts.shiftStatus.AWAITING_APPROVAL',
  CLOSED: 'staff.shifts.shiftStatus.CLOSED',
  VOIDED: 'staff.shifts.shiftStatus.VOIDED',
};

/** The today list is re-read this often while that tab is the one in view. */
const TODAY_POLL_MS = 30_000;

/**
 * Staff «Смены» (ADR 0156, operations IA 9.6): the branch's rota, who is working now, and the
 * timesheet. Three tabs and one screen, because a manager moves between them in a minute: plan the
 * week, see today, correct what was recorded.
 *
 * **A shift is a timesheet, never a permission.** Nothing here decides whether a person may sign in or
 * take an order; it records hours. The screen says so in the week caption and again in the notice a
 * tenant reads before switching attendance on.
 *
 * **Absent until switched on.** A tenant that has not enabled attendance sees an explanation and, for
 * the one who can change configuration, the enable flow with the notice; everyone else is told to ask.
 * The shift routes answer as absent while it is off, so there is nothing to render.
 *
 * **«Сегодня» reads the branch's own day.** It is polled while it is the tab in view and says when it was
 * read; «Работает без смены» sees only people who take orders, and the screen says that too.
 */
@Component({
  selector: 'q-staff-shifts-page',
  imports: [TPipe, RosterWeekGrid, RosterEntryDialog, ShiftActionDialog, AttendanceNoticeDialog],
  templateUrl: './staff-shifts-page.html',
  styleUrl: './staff-shifts-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class StaffShiftsPage {
  private readonly api = inject(StaffShiftsApi);
  private readonly status = inject(AttendanceStatus);
  private readonly tenant = inject(CurrentTenant);
  private readonly location = inject(CurrentLocation);
  private readonly capabilities = inject(SessionCapabilities);
  private readonly configuration = inject(ConfigurationApi);
  protected readonly i18n = inject(I18n);

  protected readonly state = signal<LoadState>('loading');
  protected readonly loadError = signal<string | null>(null);
  protected readonly tab = signal<Tab>('week');
  protected readonly zone = signal<TimeZone>('UTC');

  // --- the week
  protected readonly weekStart = signal(mondayOf(isoOf(new Date())));
  protected readonly entries = signal<readonly RosterEntry[]>([]);
  protected readonly people = signal<readonly ShiftPerson[]>([]);
  private navigated = false;

  // --- today
  protected readonly today = signal<TodayList | null>(null);
  protected readonly todayError = signal<string | null>(null);

  // --- the timesheet
  protected readonly timesheetFrom = signal(mondayOf(isoOf(new Date())));
  protected readonly timesheetTo = signal(addDays(mondayOf(isoOf(new Date())), 6));
  protected readonly shifts = signal<readonly StaffShift[]>([]);
  protected readonly nextCursor = signal<string | null>(null);
  protected readonly timesheetState = signal<'idle' | 'loading' | 'ready' | 'error'>('idle');
  protected readonly timesheetError = signal<string | null>(null);

  // --- acting
  protected readonly busy = signal(false);
  protected readonly failure = signal<string | null>(null);
  protected readonly notice = signal<string | null>(null);
  protected readonly entryTarget = signal<EntryTarget | null>(null);
  protected readonly actionTarget = signal<ActionTarget | null>(null);
  protected readonly noticeOpen = signal(false);

  protected readonly canManage = computed(() => this.capabilities.has('STAFF_SHIFT_MANAGE'));
  protected readonly canApprove = computed(() => this.capabilities.has('STAFF_SHIFT_APPROVE'));
  protected readonly canEnable = computed(() =>
    this.capabilities.has('TENANT_CONFIGURATION_WRITE'),
  );

  protected readonly days = computed<readonly GridDay[]>(() => {
    const today = this.zonedToday();
    const formatter = new Intl.DateTimeFormat(this.i18n.locale(), {
      weekday: 'short',
      timeZone: 'UTC',
    });
    return Array.from({ length: 7 }, (_, index) => {
      const iso = addDays(this.weekStart(), index);
      const [year, month, day] = iso.split('-').map(Number);
      const weekday = formatter.format(new Date(Date.UTC(year, month - 1, day)));
      return {
        iso,
        label: `${weekday} ${iso.slice(8, 10)}.${iso.slice(5, 7)}`,
        isToday: iso === today,
      };
    });
  });

  protected readonly weekLabel = computed(() => {
    const first = this.weekStart();
    const last = addDays(first, 6);
    return `${first.slice(8, 10)}.${first.slice(5, 7)} – ${last.slice(8, 10)}.${last.slice(5, 7)}.${last.slice(0, 4)}`;
  });

  protected readonly draftCount = computed(
    () => this.entries().filter((entry) => entry.status === 'DRAFT').length,
  );

  protected readonly todayRows = computed(() => this.today()?.rows ?? []);

  protected readonly asOf = computed(() => {
    const read = this.today();
    return read === null ? '' : formatTime(new Date(read.asOf), this.zone());
  });

  constructor() {
    void this.load();
    const timer = setInterval(() => {
      if (this.tab() === 'today' && this.state() === 'ready' && this.actionTarget() === null) {
        void this.loadToday();
      }
    }, TODAY_POLL_MS);
    inject(DestroyRef).onDestroy(() => clearInterval(timer));
  }

  // ------------------------------------------------------------------------------- loading

  protected retry(): void {
    void this.load();
  }

  private scope() {
    return this.location.scope();
  }

  private async load(): Promise<void> {
    this.state.set('loading');
    await this.tenant.ensureLoaded();
    await this.location.ensureLoaded();
    await this.status.ensureLoaded();
    if (!this.tenant.tenantId() || this.scope() === null) {
      this.state.set(this.tenant.denied() || this.location.denied() ? 'denied' : 'error');
      return;
    }
    if (!this.status.enabled()) {
      this.state.set('off');
      return;
    }
    try {
      await this.loadWeek();
      this.state.set('ready');
    } catch (error) {
      this.failLoad(error);
    }
  }

  private failLoad(error: unknown): void {
    if (error instanceof ApiError && error.status === 403) {
      this.state.set('denied');
      return;
    }
    this.loadError.set(this.describe(error));
    this.state.set('error');
  }

  private async loadWeek(): Promise<void> {
    const scope = this.scope();
    if (scope === null) {
      return;
    }
    const from = this.weekStart();
    const week = await this.api.week(scope, from, addDays(from, 6));
    this.zone.set(week.timezone);
    this.entries.set(week.entries);
    // The first read is anchored on the browser's day; the branch's own day decides the week once it is known.
    if (!this.navigated) {
      const anchored = mondayOf(this.zonedToday());
      if (anchored !== from) {
        this.weekStart.set(anchored);
        this.timesheetFrom.set(anchored);
        this.timesheetTo.set(addDays(anchored, 6));
        await this.loadWeek();
        return;
      }
    }
    if (this.canManage()) {
      this.people.set(await this.api.rosterable(scope));
    }
  }

  protected selectTab(tab: Tab): void {
    this.tab.set(tab);
    if (tab === 'today') {
      void this.loadToday();
    } else if (tab === 'timesheet' && this.timesheetState() === 'idle') {
      void this.loadTimesheet(false);
    }
  }

  protected async loadToday(): Promise<void> {
    const scope = this.scope();
    if (scope === null) {
      return;
    }
    try {
      this.today.set(await this.api.today(scope));
      this.todayError.set(null);
    } catch (error) {
      this.todayError.set(this.describe(error));
    }
  }

  protected async loadTimesheet(more: boolean): Promise<void> {
    const scope = this.scope();
    if (scope === null) {
      return;
    }
    this.timesheetState.set('loading');
    try {
      const page = await this.api.shifts(scope, {
        from: this.timesheetFrom(),
        to: this.timesheetTo(),
        cursor: more ? this.nextCursor() : null,
      });
      this.shifts.set(more ? [...this.shifts(), ...page.items] : page.items);
      this.nextCursor.set(page.nextCursor);
      this.timesheetState.set('ready');
    } catch (error) {
      this.timesheetError.set(this.describe(error));
      this.timesheetState.set('error');
    }
  }

  protected setTimesheetFrom(value: string): void {
    if (value) {
      this.timesheetFrom.set(value);
      void this.loadTimesheet(false);
    }
  }

  protected setTimesheetTo(value: string): void {
    if (value) {
      this.timesheetTo.set(value);
      void this.loadTimesheet(false);
    }
  }

  // ---------------------------------------------------------------------------- week moves

  protected shiftWeek(weeks: number): void {
    this.navigated = true;
    this.weekStart.set(addDays(this.weekStart(), weeks * 7));
    void this.reloadWeek();
  }

  protected thisWeek(): void {
    this.navigated = false;
    this.weekStart.set(mondayOf(this.zonedToday()));
    void this.reloadWeek();
  }

  private async reloadWeek(): Promise<void> {
    try {
      await this.loadWeek();
    } catch (error) {
      this.failure.set(this.describe(error));
    }
  }

  private zonedToday(): string {
    return toZonedDatetimeLocal(new Date(), this.zone()).slice(0, 10);
  }

  // ------------------------------------------------------------------------ rota actions

  protected openEntry(target: EntryTarget): void {
    this.failure.set(null);
    this.entryTarget.set(target);
  }

  protected addEntry(cell: { date: string; memberId: string | null }): void {
    this.openEntry({ entry: null, date: cell.date, memberId: cell.memberId });
  }

  protected editEntry(entry: RosterEntry): void {
    this.openEntry({ entry, date: null, memberId: null });
  }

  protected closeEntryDialog(): void {
    this.entryTarget.set(null);
    this.failure.set(null);
  }

  protected async onEntryResult(result: EntryDialogResult): Promise<void> {
    const scope = this.scope();
    const target = this.entryTarget();
    if (scope === null || target === null) {
      return;
    }
    await this.run(async () => {
      if (result.kind === 'cancel') {
        if (target.entry !== null) {
          await this.api.cancelEntry(scope, target.entry, result.reason);
        }
      } else if (target.entry === null) {
        await this.api.createEntry(scope, result.draft);
      } else {
        await this.api.updateEntry(scope, target.entry, result.draft);
      }
      this.entryTarget.set(null);
      await this.loadWeek();
    });
  }

  protected async publishWeek(): Promise<void> {
    const scope = this.scope();
    if (scope === null) {
      return;
    }
    const from = this.weekStart();
    await this.run(async () => {
      const published = await this.api.publish(scope, from, addDays(from, 6));
      this.notice.set(this.i18n.t('staff.shifts.week.published', { count: published.published }));
      await this.loadWeek();
    });
  }

  protected async copyPreviousWeek(): Promise<void> {
    const scope = this.scope();
    if (scope === null) {
      return;
    }
    const to = this.weekStart();
    await this.run(async () => {
      const copied = await this.api.copyWeek(scope, addDays(to, -7), to);
      this.notice.set(
        this.i18n.t('staff.shifts.week.copied', {
          created: copied.created,
          skipped: copied.skipped,
        }),
      );
      await this.loadWeek();
    });
  }

  // --------------------------------------------------------------------- timesheet actions

  protected act(
    kind: ShiftActionKind,
    shift: StaffShift | null,
    person: ShiftPerson | null,
    ref: { id: string; version: number } | null = shift,
  ): void {
    this.failure.set(null);
    this.actionTarget.set({
      kind,
      shift: ref,
      full: shift,
      memberId: person?.memberId ?? null,
      subject: person === null ? null : this.personLabel(person),
    });
  }

  protected openFromToday(row: TodayRow): void {
    this.act('open', null, row.person);
  }

  protected closeFromToday(row: TodayRow): void {
    const ref =
      row.shiftId !== null && row.shiftVersion !== null
        ? { id: row.shiftId, version: row.shiftVersion }
        : null;
    this.act('close', null, row.person, ref);
  }

  protected approveFromToday(row: TodayRow): void {
    const ref =
      row.shiftId !== null && row.shiftVersion !== null
        ? { id: row.shiftId, version: row.shiftVersion }
        : null;
    this.act('approve', null, row.person, ref);
  }

  protected closeAction(): void {
    this.actionTarget.set(null);
    this.failure.set(null);
  }

  protected async onActionResult(result: ShiftActionResult): Promise<void> {
    const scope = this.scope();
    const target = this.actionTarget();
    if (scope === null || target === null) {
      return;
    }
    await this.run(async () => {
      switch (result.kind) {
        case 'open':
          await this.api.openFor(scope, result.memberId, result.startedAt, result.reason);
          break;
        case 'close':
          if (target.shift !== null) {
            await this.api.closeFor(
              scope,
              target.shift,
              result.endedAt,
              result.breakSeconds,
              result.reason,
            );
          }
          break;
        case 'correct':
          if (target.shift !== null) {
            await this.api.correct(
              scope,
              target.shift,
              {
                ...(result.startedAt === null ? {} : { startedAt: result.startedAt }),
                ...(result.endedAt === null ? {} : { endedAt: result.endedAt }),
                ...(result.breakSeconds === null ? {} : { breakSeconds: result.breakSeconds }),
              },
              result.reason,
            );
          }
          break;
        case 'void':
          if (target.shift !== null) {
            await this.api.voidShift(scope, target.shift, result.reason);
          }
          break;
        case 'approve':
          if (target.shift !== null) {
            await this.api.approve(scope, target.shift, result.reason);
          }
          break;
      }
      this.actionTarget.set(null);
      await Promise.all([this.loadToday(), this.refreshTimesheet()]);
    });
  }

  private async refreshTimesheet(): Promise<void> {
    if (this.timesheetState() !== 'idle') {
      await this.loadTimesheet(false);
    }
  }

  // ----------------------------------------------------------------------- enabling

  protected openNotice(): void {
    this.failure.set(null);
    this.noticeOpen.set(true);
  }

  protected closeNotice(): void {
    this.noticeOpen.set(false);
    this.failure.set(null);
  }

  protected async enable(reason: string): Promise<void> {
    const tenantId = this.tenant.tenantId();
    if (!tenantId) {
      return;
    }
    await this.run(async () => {
      await this.configuration.setValue(tenantId, 'workforce.attendance.enabled', {
        scopeType: 'TENANT',
        explicitNull: false,
        booleanValue: true,
        reason,
      });
      this.status.markEnabled();
      this.noticeOpen.set(false);
      await this.load();
    });
  }

  // ------------------------------------------------------------------------- rendering

  protected personLabel(person: ShiftPerson | null): string {
    if (person === null) {
      return this.i18n.t('staff.shifts.person.unknown');
    }
    return person.name ?? person.displayReference;
  }

  protected todayLabel(status: TodayStatus): string {
    return this.i18n.t(TODAY_KEYS[status]);
  }

  protected shiftLabel(status: ShiftStatus): string {
    return this.i18n.t(SHIFT_KEYS[status]);
  }

  /** The class the status pill wears: a problem reads differently from a normal state. */
  protected todayTone(status: TodayStatus): 'alert' | 'ok' | 'quiet' {
    switch (status) {
      case 'LATE':
      case 'MISSED_TODAY':
      case 'WORKING_WITHOUT_SHIFT':
      case 'OPEN_TOO_LONG':
        return 'alert';
      case 'WORKING':
        return 'ok';
      default:
        return 'quiet';
    }
  }

  protected time(instant: string | null): string {
    return instant === null ? '' : formatTime(new Date(instant), this.zone());
  }

  protected dateTime(instant: string | null): string {
    return instant === null ? '' : formatDateTime(new Date(instant), this.zone());
  }

  protected plannedWindow(row: TodayRow): string {
    return row.plannedStart === null || row.plannedEnd === null
      ? ''
      : `${this.time(row.plannedStart)}–${this.time(row.plannedEnd)}`;
  }

  protected hours(seconds: number | null): string {
    if (seconds === null) {
      return '';
    }
    return formatDuration(seconds / 60, this.units());
  }

  protected variance(seconds: number | null): string {
    if (seconds === null) {
      return '';
    }
    const sign = seconds < 0 ? '−' : '+';
    return `${sign}${formatDuration(Math.abs(seconds) / 60, this.units())}`;
  }

  private units() {
    return {
      hour: this.i18n.t('staff.shifts.unit.hour'),
      minute: this.i18n.t('staff.shifts.unit.minute'),
    };
  }

  private describe(error: unknown): string {
    return error instanceof ApiError
      ? describeApiError(error, (key, values) => this.i18n.t(key, values))
      : this.i18n.t('error.unknown.noReference');
  }

  /** Runs a write with the dialogs' shared busy flag and failure text. */
  private async run(act: () => Promise<void>): Promise<void> {
    this.busy.set(true);
    this.failure.set(null);
    this.notice.set(null);
    try {
      await act();
    } catch (error) {
      this.failure.set(this.describe(error));
    } finally {
      this.busy.set(false);
    }
  }
}

// ------------------------------------------------------------------------------------ dates

function isoOf(date: Date): string {
  const month = String(date.getMonth() + 1).padStart(2, '0');
  const day = String(date.getDate()).padStart(2, '0');
  return `${date.getFullYear()}-${month}-${day}`;
}

function addDays(iso: string, days: number): string {
  const [year, month, day] = iso.split('-').map(Number);
  const moved = new Date(Date.UTC(year, month - 1, day + days));
  return `${moved.getUTCFullYear()}-${String(moved.getUTCMonth() + 1).padStart(2, '0')}-${String(moved.getUTCDate()).padStart(2, '0')}`;
}

/** The Monday of the week an ISO date falls in: weeks on the rota run Monday to Sunday. */
function mondayOf(iso: string): string {
  const [year, month, day] = iso.split('-').map(Number);
  const weekday = new Date(Date.UTC(year, month - 1, day)).getUTCDay();
  return addDays(iso, -((weekday + 6) % 7));
}
