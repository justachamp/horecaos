import { Injectable, inject } from '@angular/core';

import { CourierProfileStore } from '../courier/courier-profile.store';
import { DeliveriesStore } from '../deliveries/deliveries.store';
import { OffersStore } from '../offers/offers.store';
import { DutyStore } from '../telemetry/duty.store';

/**
 * What the app does about the courier's working state as it changes: the record is read, and
 * whatever hangs off an open shift follows it.
 *
 * `begin` runs once, when the courier is signed in. `reconcile` runs whenever something the app
 * did changes the record -- a shift opened, closed, a break started -- and makes the rest agree
 * with it: a session the platform still has open is picked up again, one it no longer has is let
 * go; offers are polled for while a shift is open; what the courier carries is read with a shift
 * or without. `end` runs at sign-out and leaves nothing of the courier's work in memory. Screens never decide this for themselves, so no two of them can
 * disagree about whether the courier is working.
 */
@Injectable({ providedIn: 'root' })
export class WorkSession {
  private readonly profile = inject(CourierProfileStore);
  private readonly duty = inject(DutyStore);
  private readonly offers = inject(OffersStore);
  private readonly deliveries = inject(DeliveriesStore);

  private started = false;

  /** Reads the record and brings the rest up to date. Idempotent: the shell may call it on every sign-in. */
  async begin(): Promise<void> {
    if (this.started) {
      return;
    }
    this.started = true;
    await this.profile.load();
    await this.reconcile();
  }

  /** Re-reads the record after something changed it, then reconciles. */
  async refresh(): Promise<void> {
    await this.profile.load(true);
    await this.reconcile();
  }

  /** Makes everything that depends on the shift agree with the record as it now stands. */
  async reconcile(): Promise<void> {
    const shift = this.profile.shiftBranch();
    if (shift === null) {
      this.duty.shiftEnded();
      this.offers.stop();
    } else {
      await this.duty.restore();
      this.offers.start(shift);
    }
    // What the courier still carries is read with or without a shift: a delivery is finished
    // after the shift closes, not abandoned.
    await this.deliveries.refresh();
  }

  /** Sign-out: nothing may keep collecting for a courier who has left. */
  end(): void {
    this.started = false;
    this.duty.shiftEnded();
    this.offers.stop();
    this.deliveries.clear();
  }
}
