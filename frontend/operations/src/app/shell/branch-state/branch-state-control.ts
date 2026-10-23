import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  inject,
  signal,
} from '@angular/core';

import { CurrentLocation } from '../../core/auth/current-location';
import { SessionCapabilities } from '../../core/auth/session-capabilities';
import { I18n } from '../../core/i18n/i18n';
import { ShortcutRegistry } from '../../shared/keyboard/shortcut-registry';
import { BranchStateDialog } from './branch-state-dialog';
import { BranchStates, deviceTimeZone } from './branch-states';
import { branchStateText } from './branch-state-text';
import { clockIn } from './branch-time';

/**
 * The shell header's branch status control (brands-and-locations.md §6.2): one button, always in view to
 * anyone who holds `location.service-state.change`, saying whether the branch they are looking at is taking
 * orders and opening the status dialog. During service nobody navigates to Settings -> Branches -> Edit, so
 * the lever is in the header.
 *
 * **Omitted, not disabled**, for anyone who may not change a state (§6.5), and while the branch's state has
 * not been read: a control that cannot act does not appear. The capability test is the console's usual
 * usability affordance, never the check -- the server refuses the write to anyone without the capability at
 * that branch.
 *
 * `c` opens the same dialog from anywhere in the console (§6.6); it is registered with the control, so it
 * exists exactly when the control does.
 */
@Component({
  selector: 'q-branch-state-control',
  imports: [BranchStateDialog],
  templateUrl: './branch-state-control.html',
  styleUrl: './branch-state-control.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class BranchStateControl {
  private readonly capabilities = inject(SessionCapabilities);
  private readonly states = inject(BranchStates);
  private readonly location = inject(CurrentLocation);
  private readonly i18n = inject(I18n);

  /** The branch the dialog is open for, or null while it is shut. */
  protected readonly dialogFor = signal<string | null>(null);

  protected readonly visible = computed(
    () =>
      this.capabilities.has('LOCATION_SERVICE_STATE_CHANGE') &&
      !this.states.denied() &&
      this.states.current() !== null,
  );

  protected readonly closed = computed(
    () => this.states.current()?.effectiveMode === 'FORCE_CLOSED',
  );
  protected readonly forcedOpen = computed(
    () => this.states.current()?.effectiveMode === 'FORCE_OPEN',
  );

  protected readonly label = computed(() => {
    const state = this.states.current();
    const locale = this.i18n.locale();
    if (state === null) {
      return '';
    }
    if (state.effectiveMode === 'FORCE_CLOSED') {
      if (state.effectiveUntil === null) {
        return branchStateText.text(locale, 'control.closed');
      }
      const zone = this.states
        .closed()
        .find((branch) => branch.locationId === state.locationId)?.timeZone;
      return branchStateText.text(locale, 'control.closedUntil', {
        time: clockIn(new Date(state.effectiveUntil), zone ?? deviceTimeZone()),
      });
    }
    return branchStateText.text(
      locale,
      state.effectiveMode === 'FORCE_OPEN' ? 'control.forcedOpen' : 'control.open',
    );
  });

  protected readonly ariaLabel = computed(() =>
    branchStateText.text(this.i18n.locale(), 'control.aria', { state: this.label() }),
  );

  constructor() {
    const destroyRef = inject(DestroyRef);
    this.states.watch(destroyRef);
    inject(ShortcutRegistry).register(
      {
        id: 'branch-state',
        title: () => branchStateText.text(this.i18n.locale(), 'keys.title'),
        shortcuts: [
          {
            keys: ['c'],
            caps: ['c'],
            label: () => branchStateText.text(this.i18n.locale(), 'keys.open'),
            enabled: () => this.visible() && this.dialogFor() === null,
            run: () => this.open(),
          },
        ],
      },
      destroyRef,
    );
  }

  protected open(): void {
    const id = this.location.scope()?.locationId;
    if (id !== undefined) {
      this.dialogFor.set(id);
    }
  }
}
