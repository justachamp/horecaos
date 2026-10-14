import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  inject,
  signal,
} from '@angular/core';

import { SessionCapabilities } from '../../core/auth/session-capabilities';
import { I18n } from '../../core/i18n/i18n';
import { BranchStates, ClosedBranch } from './branch-states';
import { branchStateText } from './branch-state-text';
import { clockIn } from './branch-time';

/**
 * «Closed by hand: Chilonzor (until cancelled) · Yunusobod (until 21:30)», with a one-click reopen each, at the
 * top of the live board and the branch list whenever any branch is shut by hand (brands-and-locations.md §6.2).
 *
 * «This strip is the whole mechanism against the Thursday-fryer failure, and it should be impossible to
 * dismiss.» So it has no close control, no remembered state and no timer of its own: it is drawn from the
 * brand's current states and stops being drawn only when the last branch is open again. A branch with no
 * expiry says so, because that is the one somebody must remember. The reopen buttons are the only thing the
 * capability gates; the strip itself is for everyone who can read the branches.
 */
@Component({
  selector: 'q-closed-branch-strip',
  templateUrl: './closed-branch-strip.html',
  styleUrl: './closed-branch-strip.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ClosedBranchStrip {
  private readonly states = inject(BranchStates);
  private readonly capabilities = inject(SessionCapabilities);
  private readonly i18n = inject(I18n);

  protected readonly closed = computed(() => (this.states.denied() ? [] : this.states.closed()));
  protected readonly canReopen = computed(() =>
    this.capabilities.has('LOCATION_SERVICE_STATE_CHANGE'),
  );
  protected readonly reopening = signal<string | null>(null);
  protected readonly failed = signal<string | null>(null);

  constructor() {
    this.states.watch(inject(DestroyRef));
  }

  protected text(
    key: Parameters<typeof branchStateText.text>[1],
    values?: Readonly<Record<string, string | number>>,
  ): string {
    return branchStateText.text(this.i18n.locale(), key, values);
  }

  protected until(branch: ClosedBranch): string {
    return branch.until === null
      ? this.text('strip.untilCancelled')
      : this.text('strip.until', { time: clockIn(new Date(branch.until), branch.timeZone) });
  }

  protected async reopen(branch: ClosedBranch): Promise<void> {
    if (this.reopening() !== null) {
      return;
    }
    this.reopening.set(branch.locationId);
    this.failed.set(null);
    try {
      await this.states.reopen(branch.locationId);
    } catch {
      this.failed.set(branch.name);
    } finally {
      this.reopening.set(null);
    }
  }
}
