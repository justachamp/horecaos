import { DOCUMENT } from '@angular/common';
import { Injectable, computed, inject, signal } from '@angular/core';

import { IntentKeys } from '../api/intent-keys';
import { ApiError, toApiError } from '../api/problem-details';
import { CourierApi } from '../courier/courier-api';
import type { DutySession } from '../courier/courier-duty';
import { CourierProfileStore } from '../courier/courier-profile.store';
import type { BranchRef } from '../courier/courier-shift';
import { DeviceId } from '../device/device-id';
import { WAKE_LOCK } from '../device/wake-lock';
import { NoticeStore } from '../notice/notice.store';
import { TelemetrySender } from './telemetry-sender';

/** No position for this long while on duty and the indicator stops saying "tracking". */
export const GAP_AFTER_MS = 45_000;

const TICK_MS = 5_000;

/** What the always-visible on-duty indicator (ADR 0045) can truthfully say. */
export type DutyIndicator = 'off' | 'tracking' | 'searching' | 'denied' | 'break' | 'unreachable';

export type WakeLockState = 'none' | 'held' | 'refused' | 'released';

/** A refusal the app makes itself, before it asks the platform; the code doubles as the reason so one table of messages serves both. */
function refusal(code: 'NOTICE_REQUIRED' | 'NO_OPEN_SHIFT'): ApiError {
  return new ApiError({ status: 0, code, reason: code, detail: 'refused before asking' });
}

/**
 * Whether the courier is on duty -- the platform's duty session, and the handset's side of it:
 * the telemetry sender and the screen wake lock (ADR 0045, ADR 0179 D7).
 *
 * **Nothing is collected until all three are true:** the courier has acknowledged the transparency
 * notice, a shift is open, and the platform has opened a session. The session is the platform's
 * and ends with the shift (`DutySessionLifecyclePort`), so this store lets go when the shift ends
 * and when the platform says no session is open; it never keeps collecting on its own say-so.
 *
 * **The wake lock is the reason a PWA can track at all, and the browser takes it back whenever the
 * page is hidden.** So it is asked for again when the page returns. While the screen is off the
 * phone samples nothing -- the limitation ADR 0179 states and the duty screen repeats.
 */
@Injectable({ providedIn: 'root' })
export class DutyStore {
  private readonly api = inject(CourierApi);
  private readonly profile = inject(CourierProfileStore);
  private readonly notice = inject(NoticeStore);
  private readonly sender = inject(TelemetrySender);
  private readonly lockSource = inject(WAKE_LOCK);
  private readonly device = inject(DeviceId);
  private readonly keys = inject(IntentKeys);
  private readonly document = inject(DOCUMENT);

  private readonly open = signal<DutySession | null>(null);
  private readonly working = signal(false);
  private readonly failure = signal<ApiError | null>(null);
  private readonly lockState = signal<WakeLockState>('none');
  private readonly needsNotice = signal(false);
  private readonly platformClosed = signal(false);
  private readonly clock = signal(Date.now());

  private where: BranchRef | null = null;
  private release: (() => Promise<void>) | null = null;
  private ticker: ReturnType<typeof setInterval> | null = null;

  /** Deliveries the courier carries now; the deliveries store keeps it current, the sender reports it. */
  readonly assignments = signal(0);

  readonly session = this.open.asReadonly();
  readonly busy = this.working.asReadonly();
  readonly error = this.failure.asReadonly();
  readonly wakeLock = this.lockState.asReadonly();
  /** A session is open on the platform but this phone cannot collect for it until the notice is acknowledged here. */
  readonly awaitingNotice = this.needsNotice.asReadonly();
  /** The platform ended the session under the courier (a close elsewhere, the sweeper). */
  readonly endedByPlatform = this.platformClosed.asReadonly();

  /** Why the handset is not giving a position, when it is not: shown beside the switch that fixes it. */
  readonly locationProblem = computed(() => this.sender.locationProblem());

  readonly onDuty = computed(() => this.open() !== null && this.sender.running());

  readonly indicator = computed<DutyIndicator>(() => {
    if (!this.onDuty()) {
      return 'off';
    }
    if (this.sender.paused() || this.sender.suspended()) {
      return 'break';
    }
    if (this.sender.locationProblem() === 'denied') {
      return 'denied';
    }
    if (this.sender.failure() === 'unreachable') {
      return 'unreachable';
    }
    const last = this.sender.lastFixAt();
    return last !== null && this.clock() - last > GAP_AFTER_MS ? 'searching' : 'tracking';
  });

  /** Looks for a session already open (after a reload, or one opened on another phone). */
  async restore(): Promise<void> {
    const where = this.profile.shiftBranch();
    if (where === null) {
      return;
    }
    try {
      const { session } = await this.api.currentDuty(where);
      if (session === null) {
        this.needsNotice.set(false);
        if (this.open() !== null) {
          this.detach();
        }
        return;
      }
      if (!this.notice.acknowledged()) {
        this.needsNotice.set(true);
        return;
      }
      if (this.open()?.sessionId !== session.sessionId) {
        this.attach(session, where);
      }
    } catch (cause) {
      this.failure.set(toApiError(cause));
    }
  }

  async goOnDuty(): Promise<void> {
    if (this.working()) {
      return;
    }
    this.failure.set(null);
    const where = this.profile.shiftBranch();
    if (where === null) {
      this.failure.set(refusal('NO_OPEN_SHIFT'));
      return;
    }
    if (!this.notice.acknowledged()) {
      this.failure.set(refusal('NOTICE_REQUIRED'));
      return;
    }
    this.working.set(true);
    try {
      const session = await this.keys.run(`duty-open:${where.locationId}`, (key) =>
        this.api.openDuty(where, this.device.value(), key),
      );
      this.attach(session, where);
    } catch (cause) {
      this.failure.set(toApiError(cause));
    } finally {
      this.working.set(false);
    }
  }

  async goOffDuty(): Promise<void> {
    const session = this.open();
    const where = this.where;
    if (session === null || where === null || this.working()) {
      return;
    }
    this.failure.set(null);
    this.working.set(true);
    try {
      await this.keys.run(`duty-close:${session.sessionId}`, (key) =>
        this.api.closeDuty(where, session.sessionId, key),
      );
      this.detach();
    } catch (cause) {
      // Still on duty and still collecting: the platform has not heard, and the indicator must not lie.
      this.failure.set(toApiError(cause));
    } finally {
      this.working.set(false);
    }
  }

  /** The shift ended (the courier's close, or a manager's): the platform has closed the session. */
  shiftEnded(): void {
    this.platformClosed.set(false);
    this.detach();
  }

  /** A break the courier started: nothing is sampled or sent until it ends. */
  breakStarted(): void {
    this.sender.pause();
  }

  breakEnded(): void {
    this.sender.resume();
  }

  private attach(session: DutySession, where: BranchRef): void {
    this.detach();
    this.where = where;
    this.open.set(session);
    this.needsNotice.set(false);
    this.platformClosed.set(false);
    this.sender.start({
      where,
      session,
      activeAssignments: () => this.assignments(),
      onRejected: () => this.endedByPlatformNow(),
    });
    if (session.status === 'SUSPENDED') {
      this.sender.pause();
    }
    this.clock.set(Date.now());
    this.ticker = setInterval(() => this.clock.set(Date.now()), TICK_MS);
    this.document.addEventListener('visibilitychange', this.onVisibility);
    void this.takeLock();
  }

  private detach(): void {
    this.sender.stop();
    if (this.ticker !== null) {
      clearInterval(this.ticker);
      this.ticker = null;
    }
    this.document.removeEventListener('visibilitychange', this.onVisibility);
    const release = this.release;
    this.release = null;
    void release?.().catch(() => undefined);
    this.lockState.set('none');
    this.open.set(null);
    this.where = null;
  }

  private endedByPlatformNow(): void {
    this.detach();
    this.platformClosed.set(true);
    void this.profile.load();
  }

  private async takeLock(): Promise<void> {
    if (this.release !== null) {
      return;
    }
    const release = await this.lockSource.request(() => {
      this.release = null;
      if (this.open() !== null) {
        this.lockState.set('released');
      }
    });
    if (this.open() === null) {
      // Went off duty while the browser was deciding.
      await release?.().catch(() => undefined);
      return;
    }
    this.release = release;
    this.lockState.set(release === null ? 'refused' : 'held');
  }

  private readonly onVisibility = (): void => {
    if (
      this.document.visibilityState !== 'hidden' &&
      this.open() !== null &&
      this.release === null
    ) {
      void this.takeLock();
    }
  };
}
