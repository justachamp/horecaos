import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { NavigationEnd, Router, RouterOutlet } from '@angular/router';
import { toSignal } from '@angular/core/rxjs-interop';
import { filter, map, startWith } from 'rxjs/operators';

import { BottomNavComponent } from './shared/bottom-nav/bottom-nav.component';

/** Routes that own the whole viewport and hide the tab bar. */
const FULL_BLEED = ['/checkout'];

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [RouterOutlet, BottomNavComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './app.html',
  styleUrl: './app.scss',
})
export class App {
  private readonly router = inject(Router);

  /**
   * Whether the tab bar shows.
   *
   * Derived from navigation rather than set imperatively per screen, so a new
   * route cannot forget to say — the default is that the tabs are present.
   */
  protected readonly showTabs = toSignal(
    this.router.events.pipe(
      filter((event): event is NavigationEnd => event instanceof NavigationEnd),
      map((event) => !FULL_BLEED.some((path) => event.urlAfterRedirects.startsWith(path))),
      startWith(true),
    ),
    { initialValue: true },
  );
}
