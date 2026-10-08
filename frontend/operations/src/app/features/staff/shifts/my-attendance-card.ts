import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
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
} from '../../../core/format/datetime';
import { I18n } from '../../../core/i18n/i18n';
import { MessageKey } from '../../../core/i18n/messages.en';
import { TPipe } from '../../../core/i18n/t.pipe';
import { describeApiError } from '../../orders/order-errors';
import { AttendanceStatus } from './attendance-status';
import { MyAttendance, ShiftStatus, StaffShift, StaffShiftsApi } from './staff-shifts-api';

type CardState = 'loading' | 'ready' | 'error';

const STATUS_KEYS: Readonly<Record<ShiftStatus, MessageKey>> = {
  OPEN: 'staff.shifts.shiftStatus.OPEN',
  AWAITING_APPROVAL: 'staff.shifts.mine.awaiting',
  CLOSED: 'staff.shifts.shiftStatus.CLOSED',
  VOIDED: 'staff.shifts.shiftStatus.VOIDED',
};

/**
 * «Моя смена» on «Моя работа» (ADR 0156): the signed-in person starts and ends their own shift, and
 * sees their last few.
 *
 * **Absent unless it applies.** The card renders nothing for a tenant that has not switched attendance
 * on, and nothing for a person who does not hold `staff.shift.clock`: a screen that explains a feature
 * the person cannot use is noise on the page they open to see their own work.
 *
 * **The server's clock, not the device's.** A tap sends no time -- the platform stamps the instant it
 * received the request, so a phone with a wrong clock cannot move a shift. A double tap is one tap:
 * the button is held while the request is out, and the request carries an Idempotency-Key besides.
 * Times are shown in the device's zone: the person is at the workplace when they tap, and the card
 * knows no branch zone of its own.
 */
@Component({
  selector: 'q-my-attendance-card',
  imports: [TPipe],
  templateUrl: './my-attendance-card.html',
  styleUrl: './my-attendance-card.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class MyAttendanceCard implements OnInit {
  private readonly api = inject(StaffShiftsApi);
  private readonly status = inject(AttendanceStatus);
  private readonly tenant = inject(CurrentTenant);
  private readonly location = inject(CurrentLocation);
  private readonly capabilities = inject(SessionCapabilities);
  protected readonly i18n = inject(I18n);

  protected readonly state = signal<CardState>('loading');
  protected readonly mine = signal<MyAttendance | null>(null);
  protected readonly busy = signal(false);
  protected readonly failure = signal<string | null>(null);
  protected readonly zone: TimeZone = Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC';

  /** Whether the card is on this page at all: attendance is on, and this person may clock. */
  protected readonly applies = computed(
    () => this.status.enabled() === true && this.capabilities.has('STAFF_SHIFT_CLOCK'),
  );

  protected readonly open = computed(() => this.mine()?.open ?? null);
  protected readonly next = computed(() => this.mine()?.upcoming[0] ?? null);
  protected readonly recent = computed(() => this.mine()?.recent ?? []);

  ngOnInit(): void {
    void this.load();
  }

  private async load(): Promise<void> {
    await this.tenant.ensureLoaded();
    await this.status.ensureLoaded();
    const tenantId = this.tenant.tenantId();
    if (!tenantId || !this.applies()) {
      return;
    }
    try {
      this.mine.set(await this.api.mine(tenantId));
      this.state.set('ready');
    } catch {
      this.state.set('error');
    }
  }

  protected retry(): void {
    this.state.set('loading');
    void this.load();
  }

  protected async clockIn(): Promise<void> {
    const tenantId = this.tenant.tenantId();
    if (!tenantId || this.busy()) {
      return;
    }
    // The branch the person is rostered at next, else the one the console is open on.
    const locationId = this.next()?.locationId ?? this.location.scope()?.locationId ?? null;
    if (locationId === null) {
      this.failure.set(this.i18n.t('staff.shifts.mine.noBranch'));
      return;
    }
    await this.act(() => this.api.clockIn(tenantId, locationId));
  }

  protected async clockOut(): Promise<void> {
    const tenantId = this.tenant.tenantId();
    if (!tenantId || this.busy()) {
      return;
    }
    await this.act(() => this.api.clockOut(tenantId));
  }

  private async act(call: () => Promise<StaffShift>): Promise<void> {
    const tenantId = this.tenant.tenantId();
    this.busy.set(true);
    this.failure.set(null);
    try {
      await call();
      if (tenantId) {
        this.mine.set(await this.api.mine(tenantId));
      }
    } catch (error) {
      this.failure.set(
        error instanceof ApiError
          ? describeApiError(error, (key, values) => this.i18n.t(key, values))
          : this.i18n.t('error.unknown.noReference'),
      );
    } finally {
      this.busy.set(false);
    }
  }

  protected time(instant: string | null): string {
    return instant === null ? '' : formatTime(new Date(instant), this.zone);
  }

  protected dateTime(instant: string | null): string {
    return instant === null ? '' : formatDateTime(new Date(instant), this.zone);
  }

  protected window(shift: StaffShift): string {
    return `${this.dateTime(shift.startedAt)}–${this.time(shift.endedAt)}`;
  }

  protected hours(seconds: number | null): string {
    return seconds === null
      ? ''
      : formatDuration(seconds / 60, {
          hour: this.i18n.t('staff.shifts.unit.hour'),
          minute: this.i18n.t('staff.shifts.unit.minute'),
        });
  }

  protected statusLabel(status: ShiftStatus): string {
    return this.i18n.t(STATUS_KEYS[status]);
  }
}
