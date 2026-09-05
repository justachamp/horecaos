import { ChangeDetectionStrategy, Component, HostListener, computed, inject } from '@angular/core';
import { Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';

import { Auth } from '../core/auth/auth';
import { CurrentLocation, LocationOption } from '../core/auth/current-location';
import { I18n, LOCALES, Locale, isLocale } from '../core/i18n/i18n';
import { TPipe } from '../core/i18n/t.pipe';
import { NAVIGATION } from './navigation';
import { ServiceStatus } from './service-status';

/**
 * The console shell: rail, top bar, and the routed view.
 *
 * This console belongs to one restaurant's staff during service. It is used
 * standing up, under time pressure, while a phone is ringing — which is the only
 * fact that matters for its design. Three consequences shape this component:
 *
 * 1. **Taking an order is a first-class destination, not a button on a list.**
 *    It has its own control above navigation and its own keyboard entry point,
 *    because on a busy evening it is the single most repeated task in the
 *    building.
 *
 * 2. **The queue is never hidden.** Nothing in this application is a full-screen
 *    modal. An operator taking a new order must still be able to see that 4819
 *    has gone late.
 *
 * 3. **The late count is always visible**, on every screen, whatever the
 *    operator is doing. See `service-status.ts`.
 *
 * **The location picker (wave 50).** `docs/operations-spec/settings.md`
 * §1.1 specifies a full scope bar — brand picker, location picker, a level
 * readout, the selection carried in the URL query — for Settings screens.
 * This is only the location half, and it lives here rather than under
 * Settings because `CurrentLocation` is what the other 76 location-scoped
 * screens (Orders, Kitchen, Delivery, Reservations, Capacity, …) already
 * depend on: putting the switch where every screen already reads its answer
 * means none of those screens has to change. No brand picker, no level
 * readout, no URL query param — an operator whose scope spans more than one
 * brand is still pinned to whichever one `CurrentBrand` resolves first (see
 * that class's own doc comment). `CurrentLocation.options()` already hides
 * itself down to nothing when there is at most one choice, so this template
 * only has to ask "is there more than one" — see the `.location` block in
 * `shell.html`.
 */
@Component({
  selector: 'q-shell',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, TPipe],
  templateUrl: './shell.html',
  styleUrl: './shell.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Shell {
  private readonly router = inject(Router);
  private readonly i18n = inject(I18n);
  private readonly currentLocation = inject(CurrentLocation);
  protected readonly auth = inject(Auth);
  protected readonly status = inject(ServiceStatus);

  protected readonly navigation = NAVIGATION;
  protected readonly locales = LOCALES;
  protected readonly locale = this.i18n.locale;

  /** Every location the picker may offer — see this class's own doc comment. */
  protected readonly locationOptions = this.currentLocation.options;

  /** The `<select>`'s current value; `''` while nothing has resolved yet. */
  protected readonly selectedLocationId = computed(
    () => this.currentLocation.scope()?.locationId ?? '',
  );

  constructor() {
    // The shell mounts before any routed screen does, so kicking off
    // resolution here — rather than waiting for the first screen to call
    // `ensureLoaded()` itself — is what lets the picker be populated by the
    // time an operator with more than one location first looks at it. Safe
    // to call again from every screen that also depends on `CurrentLocation`:
    // `ensureLoaded()` memoizes and replays the same promise.
    void this.currentLocation.ensureLoaded();
  }

  /**
   * F2 starts an order, from anywhere.
   *
   * One shortcut, not a scheme. An operator who has to reach for a mouse to
   * start an order will not use a shortcut at all, and F2 is the till-key
   * convention every restaurant system in this market already uses — so it is
   * the one binding staff arrive already knowing.
   *
   * Bound on the document rather than on an element because it has to work while
   * focus is in a search box, a filter, or nothing at all.
   */
  @HostListener('document:keydown', ['$event'])
  protected onKeydown(event: KeyboardEvent): void {
    if (event.key !== 'F2' || event.defaultPrevented) {
      return;
    }
    event.preventDefault();
    void this.startOrder();
  }

  protected startOrder(): Promise<boolean> {
    // A route, not a modal. The draft has to survive the operator glancing at
    // the queue and coming back; losing a half-built basket because somebody
    // checked whether 4819 shipped is unforgivable, and a modal cannot offer
    // that. The screen behind this route is not built — orders.md §5 owns it.
    return this.router.navigateByUrl('/orders/new');
  }

  protected onLocaleChange(value: string): void {
    if (isLocale(value)) {
      this.i18n.setLocale(value as Locale);
    }
  }

  protected onLocationChange(locationId: string): void {
    this.currentLocation.selectLocation(locationId);
  }

  /**
   * `Chilanzar`, or `Chilanzar — suspended`/`Chilanzar — draft` — the inline
   * status `settings.md` §1.1 asks every location picker to carry for a
   * `SUSPENDED` or `DRAFT` branch. An `<option>` cannot hold a styled chip,
   * so this is plain text; the settings-screen version of this picker
   * (§1.1, not built this wave) can afford the real chip.
   */
  protected locationOptionLabel(option: LocationOption): string {
    switch (option.status) {
      case 'SUSPENDED':
        return `${option.displayName} — ${this.i18n.t('shell.locationPicker.status.SUSPENDED')}`;
      case 'DRAFT':
        return `${option.displayName} — ${this.i18n.t('shell.locationPicker.status.DRAFT')}`;
      default:
        return option.displayName;
    }
  }

  protected signOut(): void {
    this.auth.logout().subscribe();
  }
}
