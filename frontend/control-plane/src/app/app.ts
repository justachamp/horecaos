import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterOutlet } from '@angular/router';

/**
 * The application root, which holds nothing.
 *
 * The console's chrome lives in ConsoleShell and is a routed component, so the
 * states that must render without it — an unreachable realm above all — are
 * simply routes outside it rather than a flag the shell has to honour.
 */
@Component({
  selector: 'app-root',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterOutlet],
  template: '<router-outlet />',
})
export class App {}
