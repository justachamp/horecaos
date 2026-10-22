import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';

import { APP_CONFIG } from '../../core/config/app-config';
import { CourierProfileStore } from '../../core/courier/courier-profile.store';
import { formatDuration, formatTime, minorUnitsOf, parseMinorUnits } from '../../core/format';
import { errorMessage } from '../../core/i18n/error-message';
import { I18n } from '../../core/i18n/i18n';
import { TPipe } from '../../core/i18n/t.pipe';
import { ShiftStore } from '../../core/shift/shift.store';
import { WorkSession } from '../../core/work/work-session';
import { DutyCard } from '../duty/duty-card';

/**
 * The shift: open it at a branch, take a break, close it, declare the cash -- and, beneath it, go
 * on duty. What the screen shows is the platform's record (`GET .../me`), re-read after every
 * action, so it is always what the dispatcher's board is working from.
 */
@Component({
  selector: 'app-shift',
  imports: [TPipe, DutyCard],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './shift.html',
  styleUrl: './shift.css',
})
export class ShiftPage implements OnInit {
  protected readonly shifts = inject(ShiftStore);
  protected readonly profile = inject(CourierProfileStore);
  protected readonly i18n = inject(I18n);
  private readonly work = inject(WorkSession);
  private readonly config = inject(APP_CONFIG);

  protected readonly currency = this.config.currency;
  protected readonly confirmingClose = signal(false);
  protected readonly cashText = signal('');
  protected readonly cashInvalid = signal(false);

  protected readonly openedAt = computed(() => {
    const shift = this.profile.profile()?.openShift;
    return shift ? formatTime(shift.openedAt, this.i18n.locale(), this.config.displayTimeZone) : '';
  });

  protected readonly paid = computed(() =>
    formatDuration(this.shifts.lastClosed()?.paidSeconds ?? 0),
  );
  protected readonly onBreakTime = computed(() =>
    formatDuration(this.shifts.lastClosed()?.breakSeconds ?? 0),
  );

  protected readonly onBreak = computed(
    () => this.profile.profile()?.openShift?.dutyState === 'ON_BREAK',
  );

  protected readonly errorText = computed(() =>
    errorMessage(this.i18n, this.shifts.error(), 'shift.error'),
  );

  ngOnInit(): void {
    void this.work.begin();
  }

  protected choose(event: Event): void {
    this.shifts.choose((event.target as HTMLSelectElement).value);
  }

  protected askToClose(): void {
    this.confirmingClose.set(true);
  }

  protected keepWorking(): void {
    this.confirmingClose.set(false);
  }

  protected async close(): Promise<void> {
    this.confirmingClose.set(false);
    await this.shifts.close();
  }

  protected typeCash(event: Event): void {
    this.cashText.set((event.target as HTMLInputElement).value);
    this.cashInvalid.set(false);
  }

  protected async declare(): Promise<void> {
    const minor = parseMinorUnits(this.cashText(), this.currency);
    if (minor === null) {
      this.cashInvalid.set(true);
      return;
    }
    await this.shifts.declareCash(minor);
  }

  protected readonly cashUnit = minorUnitsOf(this.config.currency);
}
