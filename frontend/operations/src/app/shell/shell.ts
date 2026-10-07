import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  HostListener,
  computed,
  inject,
  signal,
} from '@angular/core';
import { Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';

import { Auth } from '../core/auth/auth';
import { BrandChoice } from '../core/auth/brand-choice';
import { CurrentLocation, LocationOption } from '../core/auth/current-location';
import { OwnProfile } from '../core/auth/own-profile';
import { SessionCapabilities } from '../core/auth/session-capabilities';
import { RegionalFormatSync } from '../core/format/regional-format-sync';
import { I18n, LOCALES, Locale, isLocale } from '../core/i18n/i18n';
import { PlatformLocales } from '../core/i18n/platform-locales';
import { TPipe } from '../core/i18n/t.pipe';
import { RealtimeClient } from '../core/realtime/realtime-client';
import { ShortcutRegistry } from '../shared/keyboard/shortcut-registry';
import { ConnectionStateBanner } from '../shared/ui/connection-state-banner';
import { LiveBadge } from '../shared/ui/live-badge';
import { RefreshIndicator } from '../shared/ui/refresh-indicator';
import { Toasts } from '../shared/ui/toast';
import { ToastHost } from '../shared/ui/toast-host';
import { CallBar } from './call-bar';
import { ShellMessageKey, shellMessages } from './shell-messages';
import { ShortcutSheet } from './shortcut-sheet';
import { NAVIGATION, NavGroup } from './navigation';
import { ServiceStatus } from './service-status';
import { SupportBanner } from './support-banner';
import { VoicePresence } from './voice-presence';

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
 * **The brand and location pickers (wave 50, row `X.1` of batch 18).**
 * `docs/operations-spec/settings.md` §1.1 specifies a scope bar -- brand
 * picker, location picker, a level readout, the selection carried in the URL
 * query -- for Settings screens, and that bar lives under Settings. The shell
 * carries the pickers the other 76 screens (Orders, Kitchen, Delivery, the
 * catalogue, …) depend on, because `CurrentLocation` and `CurrentBrand` are
 * what they already read: putting the switch where every screen already reads
 * its answer means none of those screens has to change. The location picker
 * hides itself at one option; the **brand picker** (batch 18) does the same
 * for a tenant with one brand, and for an operator whose own grant names the
 * brand, so only a tenant-wide operator of a multi-brand tenant ever sees it.
 * Choosing a brand re-points `CurrentBrand` and `CurrentLocation` (see
 * `BrandChoice`) and re-creates the routed screen, because the screens read
 * their brand when they load and not on every change.
 *
 * **The keyboard (batch 18, row `X.1`).** One `keydown` listener on the
 * document hands every key to `ShortcutRegistry`; this component registers the
 * keys that belong to the shell itself (F2, `?`), and each screen registers its
 * own for as long as it is mounted. `?` opens the cheat-sheet over whatever is
 * registered.
 */
@Component({
  selector: 'q-shell',
  imports: [
    RouterOutlet,
    RouterLink,
    RouterLinkActive,
    TPipe,
    SupportBanner,
    ToastHost,
    CallBar,
    ConnectionStateBanner,
    LiveBadge,
    RefreshIndicator,
    ShortcutSheet,
  ],
  templateUrl: './shell.html',
  styleUrl: './shell.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Shell {
  private readonly router = inject(Router);
  private readonly i18n = inject(I18n);
  private readonly currentLocation = inject(CurrentLocation);
  private readonly capabilities = inject(SessionCapabilities);
  private readonly voicePresence = inject(VoicePresence);
  protected readonly auth = inject(Auth);
  private readonly ownProfile = inject(OwnProfile);
  protected readonly status = inject(ServiceStatus);
  // Field injection, not a call inside the constructor body: this is what
  // guarantees the connection is opened by the time the shell mounts,
  // mirroring `voicePresence`/`status` above — see `RealtimeClient`'s own
  // doc for why it connects from its constructor rather than behind a
  // `start()` a caller might forget.
  protected readonly realtime = inject(RealtimeClient);
  private readonly toasts = inject(Toasts);
  private readonly shortcuts = inject(ShortcutRegistry);
  protected readonly brandChoice = inject(BrandChoice);

  /**
   * The rail, filtered to sections this operator has any business in
   * (operations IA §9.1c) — a courtesy, never enforcement: the API refuses
   * the calls behind a hidden section either way (ADR 0025). A group left
   * with no items after filtering is dropped too, so an empty "People"
   * heading never prints above nothing.
   *
   * Hiding rather than disabling — the same "omit, do not disable" rule
   * `not-built-page.ts` already follows for an unbuilt screen — because a
   * greyed-out rail item teaches an operator that grey means "try again
   * later", and a wrong refusal reads as a bug, not as her own job.
   */
  protected readonly navigation = computed<readonly NavGroup[]>(() =>
    NAVIGATION.map((group) => ({
      ...group,
      items: group.items.filter((item) => this.capabilities.has(item.capability)),
    })).filter((group) => group.items.length > 0),
  );

  /**
   * The name on the account chip: the tenant's own record of the signed-in
   * person (ADR 0139), else the token's `name` claim for an account the tenant
   * keeps no record for. The claim goes stale the moment «Личные данные» saves a
   * new name, which is why it is only the fallback.
   */
  protected readonly accountName = computed(
    () => this.ownProfile.displayName() ?? this.auth.displayName(),
  );

  private readonly registry = inject(PlatformLocales);

  /**
   * The languages the switcher offers: those this build has a catalogue for **and** the registry has
   * live in the staff-UI tier (ADR 0149). A language declared but not live is never offered; before
   * the registry has been read (it is read before the shell draws) every catalogue is on offer.
   */
  protected readonly locales = computed<readonly Locale[]>(() => {
    const live = this.registry.active('STAFF_UI');
    return live.length === 0 ? LOCALES : LOCALES.filter((locale) => live.includes(locale));
  });
  protected readonly locale = this.i18n.locale;

  /** Every location the picker may offer — see this class's own doc comment. */
  protected readonly locationOptions = this.currentLocation.options;

  /** The `<select>`'s current value; `''` while nothing has resolved yet. */
  protected readonly selectedLocationId = computed(
    () => this.currentLocation.scope()?.locationId ?? '',
  );

  /**
   * Bumped when the operator picks another brand. Keys the router outlet in `shell.html`, so the
   * open screen is created afresh for the new brand instead of going on showing the old one.
   */
  private readonly brandSwitches = signal(0);
  protected readonly outletKeys = computed(() => [this.brandSwitches()]);

  protected readonly brandOptions = this.brandChoice.options;
  protected readonly selectedBrandId = computed(() => this.brandChoice.brandId() ?? '');

  constructor() {
    this.registerShortcuts();
    // The shell mounts before any routed screen does, so kicking off
    // resolution here — rather than waiting for the first screen to call
    // `ensureLoaded()` itself — is what lets the picker be populated by the
    // time an operator with more than one location first looks at it. Safe
    // to call again from every screen that also depends on `CurrentLocation`:
    // `ensureLoaded()` memoizes and replays the same promise.
    void this.currentLocation.ensureLoaded();
    // Same reasoning, for the rail filter above: fetched once here rather
    // than waiting for a routed screen, so the fourteen-entry flash the
    // unfiltered rail would otherwise show is as short as the session
    // context read allows.
    void this.capabilities.ensureLoaded();
    // The chip's name (ADR 0139). Cosmetic: a refusal leaves the token claim.
    void this.ownProfile.ensureLoaded();
    // Row 10.12: the brand's own money and phone formats, applied to every
    // formatter in the console. Constructing the sync is what starts it — it
    // follows the location's brand from here on, so it is built here, with
    // the other shell-wide loaders, rather than by whichever screen first
    // happens to show an amount.
    inject(RegionalFormatSync);
    // IA X.37: starts the presence/screen-pop poll the shell's own call bar
    // reads, here rather than waiting for an operator to open
    // `/orders/call-centre` first — the entire point of the bar is that a
    // ringing call reaches an operator on any screen. Idempotent, same
    // shape as `ensureLoaded` above; `call-centre-page.ts` calls it again
    // for the case where that page is opened directly.
    this.voicePresence.start();
    // Wave P08, row 0.1f: the rail's own open/late badges get their own
    // fetch here, rather than reading zero until an operator opens Orders
    // and freezing the moment they leave it — see `service-status.ts`'s own
    // doc. Idempotent, the same shape as every `start()` above.
    this.status.start();
  }

  /**
   * Every key goes to the registry (see {@link ShortcutRegistry}); this is the only `keydown`
   * listener the console has. Bound on the document rather than on an element because the shell's
   * keys have to work while focus is in a search box, a filter, or nothing at all.
   */
  @HostListener('document:keydown', ['$event'])
  protected onKeydown(event: KeyboardEvent): void {
    this.shortcuts.dispatch(event);
  }

  /**
   * The shell's own keys: **F2** starts an order from anywhere (orders.md §5.8), the till-key
   * convention every restaurant system in this market already uses, so it is the one binding staff
   * arrive already knowing -- and it fires from inside a text field and over a dialog, because an
   * operator who has to reach for a mouse to start an order will not use a shortcut at all. **`?`**
   * opens the cheat-sheet. **Esc** is listed for completeness: every dialog and popover closes
   * itself on it (`OverlayBehaviour`), so there is nothing here to run.
   */
  private registerShortcuts(): void {
    const text = (key: ShellMessageKey): string => shellMessages.text(this.i18n.locale(), key);
    this.shortcuts.register(
      {
        id: 'shell',
        title: () => text('keys.scope.global'),
        shortcuts: [
          {
            keys: ['F2'],
            caps: ['F2'],
            label: () => this.i18n.t('shell.newOrder'),
            inField: true,
            overDialog: true,
            run: () => void this.startOrder(),
          },
          {
            keys: ['?'],
            caps: ['?'],
            label: () => text('keys.open'),
            run: () => this.shortcuts.openSheet(),
          },
          {
            keys: ['Escape'],
            caps: ['Esc'],
            label: () => text('keys.closeDialog'),
          },
        ],
      },
      inject(DestroyRef),
    );
  }

  protected openShortcutSheet(): void {
    this.shortcuts.openSheet();
  }

  protected text(key: ShellMessageKey): string {
    return shellMessages.text(this.i18n.locale(), key);
  }

  protected onBrandChange(brandId: string): void {
    if (brandId === this.brandChoice.brandId()) {
      return;
    }
    this.brandChoice.select(brandId);
    this.brandSwitches.update((count) => count + 1);
  }

  protected startOrder(): Promise<boolean> {
    // A route, not a modal. The draft has to survive the operator glancing at
    // the queue and coming back; losing a half-built basket because somebody
    // checked whether 4819 shipped is unforgivable, and a modal cannot offer
    // that. orders.md §5 (wave P13) owns the screen behind this route.
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
    // `Toasts.clear()`'s own doc: a sign-out "must not leave the previous
    // session's words on screen" — a toast raised just before the operator
    // clicks sign-out must not still be reading on a shared kiosk terminal
    // once the next operator sits down.
    this.toasts.clear();
    this.auth.logout().subscribe();
  }
}
