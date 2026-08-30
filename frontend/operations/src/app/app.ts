import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterOutlet } from '@angular/router';

/**
 * The bootstrap component.
 *
 * Deliberately nothing but an outlet. The console's frame — rail, top bar, the
 * always-visible late count — is the `Shell`, which is a *routed* component so
 * that the login callback can render outside it. A shell nailed to the root
 * would paint a rail around a page that has no session yet.
 */
@Component({
  selector: 'q-root',
  imports: [RouterOutlet],
  template: '<router-outlet />',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class App {}
