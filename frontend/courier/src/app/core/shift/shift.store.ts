import { Injectable, computed, inject, signal } from '@angular/core';

import { IntentKeys } from '../api/intent-keys';
import { ApiError, toApiError } from '../api/problem-details';
import { CourierApi } from '../courier/courier-api';
import { CourierProfileStore } from '../courier/courier-profile.store';
import type { BranchRef, ClosedShift } from '../courier/courier-shift';
import { LocationService } from '../device/location.service';
import { DutyStore } from '../telemetry/duty.store';
import { WorkSession } from '../work/work-session';

/**
 * The courier's own shift: open it at a branch, take and end breaks, close it, and declare the
 * cash handed over (ADR 0042).
 *
 * The platform is the only one who knows a shift's state, so every action ends by re-reading the
 * record (`WorkSession.refresh`) instead of flipping a local flag: what the screen shows is
 * always what the dispatcher's board is working from. A shift's opening and closing point is the
 * handset's position when it has one and nothing when it does not -- a courier is never refused a
 * shift because a phone would not locate itself.
 */
@Injectable({ providedIn: 'root' })
export class ShiftStore {
  private readonly api = inject(CourierApi);
  private readonly profile = inject(CourierProfileStore);
  private readonly locations = inject(LocationService);
  private readonly duty = inject(DutyStore);
  private readonly work = inject(WorkSession);
  private readonly keys = inject(IntentKeys);

  private readonly chosen = signal<string | null>(null);
  private readonly working = signal(false);
  private readonly failure = signal<ApiError | null>(null);
  private readonly closed = signal<ClosedShift | null>(null);
  private readonly closedAt = signal<BranchRef | null>(null);
  private readonly declared = signal(false);

  readonly busy = this.working.asReadonly();
  readonly error = this.failure.asReadonly();
  /** What the last close in this visit computed; null before one and after the next shift opens. */
  readonly lastClosed = this.closed.asReadonly();
  readonly cashDeclared = this.declared.asReadonly();

  /** The branch a new shift would open at: the one chosen (if it is still theirs), else the main one. */
  readonly selectedBranch = computed(() => {
    const branches = this.profile.profile()?.branches ?? [];
    return (
      branches.find((branch) => branch.locationId === this.chosen()) ??
      branches.find((branch) => branch.primary) ??
      branches[0] ??
      null
    );
  });

  /** Only a branch the courier is bound to can be chosen; anything else is ignored. */
  choose(locationId: string): void {
    if (this.profile.profile()?.branches.some((branch) => branch.locationId === locationId)) {
      this.chosen.set(locationId);
    }
  }

  async open(): Promise<void> {
    const branch = this.selectedBranch();
    if (branch === null) {
      return;
    }
    const where: BranchRef = { brandId: branch.brandId, locationId: branch.locationId };
    await this.act(async () => {
      await this.keys.runWith(
        `shift-open:${where.locationId}`,
        () => this.locations.point(),
        (key, point) => this.api.openShift(where, point, key),
      );
      this.closed.set(null);
      this.closedAt.set(null);
      this.declared.set(false);
      await this.work.refresh();
    });
  }

  async close(): Promise<void> {
    const shift = this.profile.profile()?.openShift;
    const where = this.profile.shiftBranch();
    if (!shift || where === null) {
      return;
    }
    await this.act(async () => {
      const result = await this.keys.runWith(
        `shift-close:${shift.shiftId}`,
        () => this.locations.point(),
        (key, point) => this.api.closeShift(where, shift.shiftId, point, key),
      );
      this.closed.set(result);
      this.closedAt.set(where);
      this.declared.set(false);
      this.duty.shiftEnded();
      await this.work.refresh();
    });
  }

  async startBreak(): Promise<void> {
    await this.breakCall('break-start', (where, shiftId, key) =>
      this.api.startBreak(where, shiftId, key),
    ).then((done) => done && this.duty.breakStarted());
  }

  async endBreak(): Promise<void> {
    await this.breakCall('break-end', (where, shiftId, key) =>
      this.api.endBreak(where, shiftId, key),
    ).then((done) => done && this.duty.breakEnded());
  }

  /** The courier's count of the cash in the bag, against the handover the close opened. */
  async declareCash(declaredMinor: number): Promise<void> {
    const handoverId = this.closed()?.cashHandoverId ?? null;
    const where = this.closedAt();
    if (handoverId === null || where === null) {
      return;
    }
    await this.act(async () => {
      // The amount is in the body, so a different amount is a different intent.
      await this.keys.run(`cash-declare:${handoverId}:${declaredMinor}`, (key) =>
        this.api.declareCash(where, handoverId, declaredMinor, key),
      );
      this.declared.set(true);
    });
  }

  private async breakCall(
    intent: string,
    call: (where: BranchRef, shiftId: string, key: string) => Promise<void>,
  ): Promise<boolean> {
    const shift = this.profile.profile()?.openShift;
    const where = this.profile.shiftBranch();
    if (!shift || where === null) {
      return false;
    }
    return this.act(async () => {
      await this.keys.run(`${intent}:${shift.shiftId}`, (key) => call(where, shift.shiftId, key));
      await this.work.refresh();
    });
  }

  /** One action at a time; a failure is kept for the screen, never thrown at it. */
  private async act(action: () => Promise<void>): Promise<boolean> {
    if (this.working()) {
      return false;
    }
    this.working.set(true);
    this.failure.set(null);
    try {
      await action();
      return true;
    } catch (cause) {
      this.failure.set(toApiError(cause));
      return false;
    } finally {
      this.working.set(false);
    }
  }
}
