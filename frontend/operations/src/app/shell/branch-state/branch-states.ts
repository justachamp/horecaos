import { DestroyRef, Injectable, Signal, computed, inject, signal } from '@angular/core';

import { LocationScope } from '../../core/api/operations-paths';
import { ApiError } from '../../core/api/problem-details';
import { CurrentLocation } from '../../core/auth/current-location';
import {
  ChangeServiceStateRequest,
  LocationServiceStateView,
  LocationsApi,
} from '../../features/settings/locations/locations-api';

/** A branch somebody closed by hand and has not reopened, as the strip names it. */
export interface ClosedBranch {
  readonly locationId: string;
  readonly name: string;
  /** When it reopens by itself; null when someone must remember to (the dangerous case). */
  readonly until: string | null;
  readonly reasonCode: string | null;
  /** The branch's own time zone, so "until 21:30" is the branch's 21:30 wherever the reader sits. */
  readonly timeZone: string;
}

/** The zone a branch is shown in until its own has been read: the reader's own clock. */
export function deviceTimeZone(): string {
  return Intl.DateTimeFormat().resolvedOptions().timeZone;
}

/** How often a screen that shows the strip or the control asks again: another person can close a branch. */
export const BRANCH_STATE_POLL_MS = 30_000;

/**
 * Which branches of the operator's brand are shut by hand right now (brands-and-locations.md §6.2).
 *
 * One batched read answers for the whole brand (`GET .../locations/service-states`), so the shell's control,
 * the live board's strip and the branch list's strip share this one copy and one poll instead of each asking.
 * A branch counts as closed when its **effective** mode is `FORCE_CLOSED`: an elapsed `effectiveUntil`
 * reads as back on the schedule, with no job and no write, exactly as the server reads it.
 *
 * **Denied is silent.** A refused read (the operator may not see the brand's branches) leaves {@link denied}
 * set and the list empty, and the control and the strip draw nothing: «the shell control is omitted, not
 * disabled».
 */
@Injectable({ providedIn: 'root' })
export class BranchStates {
  private readonly api = inject(LocationsApi);
  private readonly location = inject(CurrentLocation);

  readonly states = signal<ReadonlyMap<string, LocationServiceStateView>>(new Map());
  /** Each branch's time zone, read once: a branch does not move. */
  private readonly zones = signal<ReadonlyMap<string, string>>(new Map());
  readonly denied = signal(false);
  readonly loaded = signal(false);
  /** The error the last read ended in, or null. A screen that must report a failed read (the branch list) asks. */
  readonly lastError = signal<ApiError | null>(null);

  private watchers = 0;
  private timer: ReturnType<typeof setInterval> | null = null;
  private inFlight: Promise<void> | null = null;

  /** The branches shut by hand, in the order the server lists them, named from the branch picker's own list. */
  readonly closed: Signal<readonly ClosedBranch[]> = computed(() => {
    const names = new Map(this.location.options().map((option) => [option.id, option.displayName]));
    return [...this.states().values()]
      .filter((state) => state.effectiveMode === 'FORCE_CLOSED')
      .map((state) => ({
        locationId: state.locationId,
        name: names.get(state.locationId) ?? state.locationId,
        until: state.effectiveUntil,
        reasonCode: state.reasonCode,
        timeZone: this.zones().get(state.locationId) ?? deviceTimeZone(),
      }));
  });

  /** The state of the branch the operator is looking at, once read. */
  readonly current: Signal<LocationServiceStateView | null> = computed(() => {
    const id = this.location.scope()?.locationId;
    return id === undefined ? null : (this.states().get(id) ?? null);
  });

  /**
   * Starts polling for as long as the caller lives. The first watcher starts the clock and reads at once; the
   * last one to go stops it. Idempotent per caller.
   */
  watch(destroyRef: DestroyRef): void {
    this.watchers += 1;
    if (this.watchers === 1) {
      void this.refresh();
      this.timer = setInterval(() => void this.refresh(), BRANCH_STATE_POLL_MS);
    }
    destroyRef.onDestroy(() => {
      this.watchers -= 1;
      if (this.watchers === 0 && this.timer !== null) {
        clearInterval(this.timer);
        this.timer = null;
      }
    });
  }

  /** Reads the brand's states again. Concurrent callers share one request. */
  refresh(): Promise<void> {
    if (this.inFlight !== null) {
      return this.inFlight;
    }
    this.inFlight = this.read().finally(() => {
      this.inFlight = null;
    });
    return this.inFlight;
  }

  private async read(): Promise<void> {
    await this.location.ensureLoaded();
    const scope = this.location.scope();
    if (scope === null) {
      this.denied.set(true);
      this.loaded.set(true);
      return;
    }
    try {
      const states = await this.api.serviceStates(scope);
      this.states.set(new Map(states.map((state) => [state.locationId, state])));
      this.denied.set(false);
      this.lastError.set(null);
      if (this.zones().size === 0 && states.length > 0) {
        await this.readZones(scope);
      }
    } catch (error) {
      this.lastError.set(error instanceof ApiError ? error : null);
      if (error instanceof ApiError && (error.status === 403 || error.status === 401)) {
        this.denied.set(true);
        this.states.set(new Map());
      }
      // Any other failure keeps the last answer: a strip that vanishes on a dropped request hides a closed branch.
    } finally {
      this.loaded.set(true);
    }
  }

  private async readZones(scope: LocationScope): Promise<void> {
    try {
      const locations = await this.api.list(scope);
      this.zones.set(new Map(locations.map((location) => [location.id, location.timezone])));
    } catch {
      // The names and states are what matter; a branch whose zone is unknown is shown in the reader's own.
    }
  }

  /** Writes one branch's state, then reads again. Rejects with the server's own error so the caller can say why. */
  async change(locationId: string, request: ChangeServiceStateRequest): Promise<void> {
    const base = this.location.scope();
    if (base === null) {
      return;
    }
    const scope: LocationScope = { tenantId: base.tenantId, brandId: base.brandId, locationId };
    await this.api.changeServiceState(scope, request);
    await this.reread();
  }

  /**
   * Reads again after a write. A poll already on its way may have left before the write landed, so this waits
   * for it and asks once more instead of sharing its answer.
   */
  async reread(): Promise<void> {
    if (this.inFlight !== null) {
      await this.inFlight;
    }
    await this.refresh();
  }

  /** Returns a branch to its schedule: the one-click reopen of the strip. */
  reopen(locationId: string): Promise<void> {
    return this.change(locationId, { mode: 'FOLLOW_SCHEDULE' });
  }
}
