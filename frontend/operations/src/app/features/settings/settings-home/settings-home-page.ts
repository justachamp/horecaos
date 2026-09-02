import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink } from '@angular/router';

import { TPipe } from '../../../core/i18n/t.pipe';
import { SETTINGS_NAVIGATION } from '../settings-nav';

/**
 * 10.0 Settings home — `docs/operations-spec/settings.md` §10.0.
 *
 * **Deliberately not built here: the readiness panel.** The spec's readiness
 * panel ("Location has no active fiscal assignment", "Channel active with no
 * enabled payment method", …) computes live, cross-cutting counts over
 * several modules' data and links each into a filtered view. Building it
 * honestly needs at least one aggregate read endpoint this wave did not add
 * — see the wave's final report for why that was left for a follow-up rather
 * than rushed. What is here is the index half of §10.0: the six groups, each
 * screen with its one-line purpose, so "where is the thing I need" still has
 * an answer even without "is this restaurant ready to trade".
 */
@Component({
  selector: 'q-settings-home-page',
  imports: [TPipe, RouterLink],
  templateUrl: './settings-home-page.html',
  styleUrl: './settings-home-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SettingsHomePage {
  protected readonly groups = SETTINGS_NAVIGATION;
}
