import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';

import { CurrentLocation } from '../../core/auth/current-location';
import { TPipe } from '../../core/i18n/t.pipe';
import { SETTINGS_NAVIGATION } from './settings-nav';

/**
 * The Settings section's own shell: a grouped left rail (§Navigation) beside
 * whichever screen is routed under `/settings/**`.
 *
 * **What this deliberately does not render.** `docs/operations-spec/settings.md`
 * §1.1 specifies a scope bar with a brand picker, a location picker and a
 * "level being edited" readout, all driven by ADR 0030's resolution model.
 * That model has no HTTP surface yet (see `core/api/settings-paths.ts`'s own
 * doc comment), so every screen below reads and writes a fixed brand/location
 * pair — the operator's own, from {@link CurrentLocation} — rather than an
 * inheritable one. This header shows that pair as plain context, not as the
 * spec's scope bar: there is nothing to switch yet, because nothing here
 * resolves through a level a switch could change.
 */
@Component({
  selector: 'q-settings-shell',
  imports: [TPipe, RouterLink, RouterLinkActive, RouterOutlet],
  templateUrl: './settings-shell.html',
  styleUrl: './settings-shell.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SettingsShell {
  protected readonly location = inject(CurrentLocation);
  protected readonly groups = SETTINGS_NAVIGATION;

  constructor() {
    void this.location.ensureLoaded();
  }
}
