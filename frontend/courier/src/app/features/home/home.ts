import { ChangeDetectionStrategy, Component, OnInit, computed, inject } from '@angular/core';
import { RouterLink } from '@angular/router';

import { AuthService } from '../../core/auth/auth.service';
import { APP_CONFIG } from '../../core/config/app-config';
import type { DutyState, EngagementStatus } from '../../core/courier/courier-profile';
import { CourierProfileStore } from '../../core/courier/courier-profile.store';
import { formatDate, formatTime } from '../../core/format';
import { I18n } from '../../core/i18n/i18n';
import type { MessageKey } from '../../core/i18n/message-keys';
import { TPipe } from '../../core/i18n/t.pipe';
import { WorkSession } from '../../core/work/work-session';

/** Typed maps rather than built keys: a status the platform adds is a compile error here, not raw text on a phone. */
const DUTY_KEYS: Readonly<Record<DutyState, MessageKey>> = {
  AVAILABLE: 'home.shift.duty.AVAILABLE',
  ON_BREAK: 'home.shift.duty.ON_BREAK',
  AT_CAPACITY: 'home.shift.duty.AT_CAPACITY',
  UNREACHABLE: 'home.shift.duty.UNREACHABLE',
};

const ENGAGEMENT_KEYS: Readonly<Record<EngagementStatus, MessageKey>> = {
  PENDING_VERIFICATION: 'home.engagement.PENDING_VERIFICATION',
  ACTIVE: 'home.engagement.ACTIVE',
  SUSPENDED_COMPLIANCE: 'home.engagement.SUSPENDED_COMPLIANCE',
  SUSPENDED_OPERATIONAL: 'home.engagement.SUSPENDED_OPERATIONAL',
  ENDED: 'home.engagement.ENDED',
};

/**
 * Today: who the platform says this courier is, where they work, and whether a shift is open.
 *
 * Read-only in this release -- opening a shift, breaks and the duty indicator arrive with the
 * shift screens. Every fact here comes from `GET .../me`, so what the courier sees is what the
 * dispatcher's board is working from.
 */
@Component({
  selector: 'app-home',
  imports: [TPipe, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './home.html',
  styleUrl: './home.css',
})
export class HomePage implements OnInit {
  protected readonly store = inject(CourierProfileStore);
  protected readonly i18n = inject(I18n);
  private readonly auth = inject(AuthService);
  private readonly config = inject(APP_CONFIG);
  private readonly work = inject(WorkSession);

  protected readonly dutyKeys = DUTY_KEYS;
  protected readonly engagementKeys = ENGAGEMENT_KEYS;

  protected readonly name = computed(() => this.auth.claims()?.name ?? null);

  protected readonly errorMessage = computed(() =>
    this.store.error()?.isNetwork ? this.i18n.t('error.network') : this.i18n.t('error.generic'),
  );

  protected readonly validUntil = computed(() => {
    const until = this.store.profile()?.engagement?.registrationValidUntil;
    return until ? formatDate(until, this.i18n.locale()) : null;
  });

  protected readonly openedAt = computed(() => {
    const shift = this.store.profile()?.openShift;
    return shift ? formatTime(shift.openedAt, this.i18n.locale(), this.config.displayTimeZone) : '';
  });

  ngOnInit(): void {
    // Begin first: if the first read of the record failed, this one is the read that brings the
    // shift's offers, duty and deliveries up; the load after it is the usual fresh look.
    void this.work.begin();
    void this.store.load();
  }

  /** Through the work session, not the store: a record that finally loads has to start what hangs off the shift. */
  protected reload(): void {
    void this.work.refresh();
  }
}
