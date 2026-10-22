import { Injectable, inject } from '@angular/core';

import { CourierProfileStore } from '../courier/courier-profile.store';
import { DutyStore } from '../telemetry/duty.store';

/**
 * What the app does about the courier's working state as it changes: the record is read, and
 * whatever hangs off an open shift follows it.
 *
 * `begin` runs once, when the courier is signed in. `reconcile` runs whenever something the app
 * did changes the record -- a shift opened, closed, a break started -- and makes the rest agree
 * with it: a session the platform still has open is picked up again, one it no longer has is let
 * go. `end` runs at sign-out. Screens never decide this for themselves, so no two of them can
 * disagree about whether the courier is working.
 */
@Injectable({ providedIn: 'root' })
export class WorkSession {
  private readonly profile = inject(CourierProfileStore);
  private readonly duty = inject(DutyStore);

  private started = false;

  /** Reads the record and brings the rest up to date. Idempotent: the shell may call it on every sign-in. */
  async begin(): Promise<void> {
    if (this.started) {
      return;
    }
    this.started = true;
    await this.refresh();
  }

  /** Re-reads the record, then reconciles. */
  async refresh(): Promise<void> {
    await this.profile.load();
    await this.reconcile();
  }

  /** Makes everything that depends on the shift agree with the record as it now stands. */
  async reconcile(): Promise<void> {
    if (this.profile.shiftBranch() === null) {
      this.duty.shiftEnded();
      return;
    }
    await this.duty.restore();
  }

  /** Sign-out: nothing may keep collecting for a courier who has left. */
  end(): void {
    this.started = false;
    this.duty.shiftEnded();
  }
}
