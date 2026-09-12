import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';

import { ScopeBar } from '../../shared/ui/scope-bar/scope-bar';
import { TPipe } from '../../core/i18n/t.pipe';
import { FeatureFlags } from '../../core/feature-flags';
import { visibleSettings } from './settings-nav';
import { SettingsScope } from './settings-scope';

/**
 * The Settings section's own shell: settings.md §1.1's scope bar, sticky
 * above a grouped left rail beside whichever screen is routed under
 * `/settings/**` (wave P31, gap map row `10/X.1`).
 *
 * Used to print the operator's own `brandId`/`locationId` pair as raw UUIDs
 * — plain context from {@link CurrentLocation}, with nothing to switch and
 * no brand-level option, because ADR 0030's resolver had no HTTP surface.
 * {@link SettingsScope} is that surface's shared state: a real brand and
 * location picker, backed by `?brand=&location=` in the URL.
 */
@Component({
  selector: 'q-settings-shell',
  imports: [TPipe, RouterLink, RouterLinkActive, RouterOutlet, ScopeBar],
  templateUrl: './settings-shell.html',
  styleUrl: './settings-shell.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SettingsShell {
  protected readonly scope = inject(SettingsScope);
  private readonly flags = inject(FeatureFlags);
  protected readonly groups = computed(() => visibleSettings((flag) => this.flags.isOn(flag)));

  constructor() {
    void this.flags.ensureLoaded();
  }

  protected onBrandChange(brandId: string): void {
    this.scope.setBrand(brandId);
  }

  protected onLocationChange(locationId: string | null): void {
    this.scope.setLocation(locationId);
  }
}
