import {
  ChangeDetectionStrategy,
  Component,
  effect,
  inject,
  input,
  signal,
  untracked,
} from '@angular/core';

import { ApiError } from '../../../core/api/problem-details';
import { CurrentLocation } from '../../../core/auth/current-location';
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
import { ShiftStatus, StaffShift, StaffShiftsApi } from './staff-shifts-api';

type TabState = 'loading' | 'ready' | 'denied' | 'error';

const STATUS_KEYS: Readonly<Record<ShiftStatus, MessageKey>> = {
  OPEN: 'staff.shifts.shiftStatus.OPEN',
  AWAITING_APPROVAL: 'staff.shifts.shiftStatus.AWAITING_APPROVAL',
  CLOSED: 'staff.shifts.shiftStatus.CLOSED',
  VOIDED: 'staff.shifts.shiftStatus.VOIDED',
};

/**
 * «Смены» on a person's card (ADR 0156, operations IA 9.2): that person's recorded shifts at the branch
 * the console is open on -- when they started, finished, how many hours were paid and whether one is
 * waiting for an approval. It reads; the corrections live on the screen «Смены», where the whole day
 * is in view.
 *
 * The card shows it only when the tenant records attendance and the viewer may read shifts. Times are
 * the branch's own zone, taken from the rota read the same way the screen does; this tab shows the
 * device's zone instead, which is the same place for a manager standing in the branch and honest about
 * what it is otherwise.
 */
@Component({
  selector: 'q-person-shifts-tab',
  imports: [TPipe],
  templateUrl: './person-shifts-tab.html',
  styleUrl: './person-shifts-tab.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PersonShiftsTab {
  private readonly api = inject(StaffShiftsApi);
  private readonly location = inject(CurrentLocation);
  protected readonly i18n = inject(I18n);

  readonly memberId = input.required<string>();

  protected readonly state = signal<TabState>('loading');
  protected readonly shifts = signal<readonly StaffShift[]>([]);
  protected readonly failure = signal<string | null>(null);
  protected readonly zone: TimeZone = Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC';

  constructor() {
    effect(() => {
      const memberId = this.memberId();
      untracked(() => void this.load(memberId));
    });
  }

  private async load(memberId: string): Promise<void> {
    this.state.set('loading');
    await this.location.ensureLoaded();
    const scope = this.location.scope();
    if (scope === null) {
      this.state.set('denied');
      return;
    }
    try {
      const page = await this.api.shiftsOf(scope, memberId);
      this.shifts.set(page.items);
      this.state.set('ready');
    } catch (error) {
      if (error instanceof ApiError && error.status === 403) {
        this.state.set('denied');
        return;
      }
      this.failure.set(
        error instanceof ApiError
          ? describeApiError(error, (key, values) => this.i18n.t(key, values))
          : this.i18n.t('error.unknown.noReference'),
      );
      this.state.set('error');
    }
  }

  protected window(shift: StaffShift): string {
    const end = shift.endedAt === null ? '' : formatTime(new Date(shift.endedAt), this.zone);
    return `${formatDateTime(new Date(shift.startedAt), this.zone)}–${end}`;
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
