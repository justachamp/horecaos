import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { RouterOutlet } from '@angular/router';

import { DocumentDirection } from './core/i18n/document-direction';

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
export class App {
  // Constructed here so `<html dir>` follows the active language from the first frame (ADR 0149).
  protected readonly direction = inject(DocumentDirection);
}
