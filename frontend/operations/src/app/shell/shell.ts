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
import { ConsoleProfile } from '../core/auth/console-profile';
import { CurrentLocation, LocationOption } from '../core/auth/current-location';
import { OwnProfile } from '../core/auth/own-profile';
import { OwnPreferences } from '../core/preferences/own-preferences';
import { SessionCapabilities } from '../core/auth/session-capabilities';
import { RegionalFormatSync } from '../core/format/regional-format-sync';
import { I18n, LOCALES, Locale, isLocale } from '../core/i18n/i18n';
import { PlatformLocales } from '../core/i18n/platform-locales';
import { TPipe } from '../core/i18n/t.pipe';
import { RealtimeClient } from '../core/realtime/realtime-client';
import { ShortcutRegistry, modifierCap } from '../shared/keyboard/shortcut-registry';
import { ConnectionStateBanner } from '../shared/ui/connection-state-banner';
import { LiveBadge } from '../shared/ui/live-badge';
import { RefreshIndicator } from '../shared/ui/refresh-indicator';
import { Toasts } from '../shared/ui/toast';
import { ToastHost } from '../shared/ui/toast-host';
import { CallBar } from './call-bar';
import { LocaleScopeOffer } from './locale-scope-offer';
import { ShellMessageKey, shellMessages } from './shell-messages';
import { ShortcutSheet } from './shortcut-sheet';
import { NAVIGATION, NavGroup, NavItem, admitsSection } from './navigation';
import { ServiceStatus } from './service-status';
import { SupportBanner } from './support-banner';
import { VoicePresence } from './voice-presence';
import { BranchStateControl } from './branch-state/branch-state-control';
import { CommandPalette } from './palette/command-palette';

/** The longest sign-out waits for the person's last preference change to reach the platform. */
const SIGN_OUT_FLUSH_MS = 1500;

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
    LocaleScopeOffer,
    BranchStateControl,
    CommandPalette,
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
  private readonly preferences = inject(OwnPreferences);
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
  protected readonly consoleProfile = inject(ConsoleProfile);

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
  protected readonly navigation = computed<readonly NavGroup[]>(() => {
    const hidden = this.consoleProfile.hiddenSections();
    const deliveries = this.consoleProfile.isDeliveries();
    return NAVIGATION.map((group) => ({
      ...group,
      // The capability filter first, then the profile (ADR 0164): the profile can only take things off
      // a rail the capability filter already allowed, never put back a section the operator may not use.
      items: this.forProfile(
        group.items.filter(
          (item) =>
            admitsSection(item, (capability) => this.capabilities.has(capability)) &&
            !hidden.includes(item.path),
        ),
        deliveries,
      ),
    })).filter((group) => group.items.length > 0);
  });

  /**
   * The deliveries profile's rail (ADR 0164 Decision 5): Delivery first within its group, and Orders
   * called what it is for this tenant -- deliveries. Nothing else about the rail changes, and a standard
   * profile is returned untouched.
   */
  private forProfile(items: readonly NavItem[], deliveries: boolean): readonly NavItem[] {
    if (!deliveries) {
      return items;
    }
    const relabelled = items.map((item) =>
      item.path === '/orders' ? { ...item, label: 'shell.nav.deliveries' as const } : item,
    );
    return [
      ...relabelled.filter((item) => item.path === '/delivery'),
      ...relabelled.filter((item) => item.path !== '/delivery'),
    ];
  }

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

  /**
   * The language just chosen on this device, while the line offering to make it the account's is showing
   * (ADR 0160). Only a person with a staff record to write to is offered it, and only when the account does
   * not already carry that language.
   */
  protected readonly localeOffer = signal<Locale | null>(null);

  /** Whether the quick jump (`⌘K`) is open. The palette itself loads the first time it is. */
  protected readonly paletteOpen = signal(false);

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
    // ADR 0160: the person's own columns, saved views and default branch. The board and the branch picker
    // read them as signals; a failed read leaves the code's defaults, and nothing waits on it here.
    void this.preferences.ensureLoaded();
    // ADR 0164: how the console opens for this tenant. Read here for the rail; the root redirect, the
    // New order screen and Statistics ask the same service. A profile that cannot be read is the
    // standard console, so a slow answer only ever shows the rail it always showed.
    void this.consoleProfile.ensureLoaded();
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
            // settings.md §1.6: the quick jump over the sections, the settings and the order board's number
            // search. It fires from a text field -- a person who is typing an address and wants the order
            // board should not have to leave the field first -- but not over a dialog.
            keys: ['k'],
            modifier: 'ctrlOrCmd',
            caps: [`${modifierCap()}+K`],
            label: () => text('keys.palette'),
            inField: true,
            run: () => this.paletteOpen.set(true),
          },
          {
            // brands-and-locations.md §1.8: the branch list from anywhere. Offered to whoever has the
            // Settings section, which is where the list lives.
            keys: [],
            sequence: ['g', 'l'],
            caps: ['g', 'l'],
            label: () => text('keys.goLocations'),
            enabled: () => this.capabilities.has('TENANT_READ'),
            run: () => void this.router.navigateByUrl('/settings/locations'),
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
      this.offerAccountLanguage(value);
    }
  }

  /**
   * The switcher decides for this device, which is already done. When the person has a record to write to
   * and their account carries another language, say so once and offer to make this their language
   * everywhere (`q-locale-scope-offer`); leaving it alone keeps the choice on this device.
   */
  private offerAccountLanguage(chosen: Locale): void {
    const member = this.ownProfile.member();
    if (member === null) {
      this.localeOffer.set(null);
      return;
    }
    const accountTag = member.uiLocale === null ? null : this.registry.canonical(member.uiLocale);
    this.localeOffer.set(accountTag === chosen ? null : chosen);
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

  /** ADR 0164: «Показать все» -- the put-away sections, for this person on this device. */
  protected toggleShowAll(): void {
    this.consoleProfile.toggleShowAll();
  }

  protected async signOut(): Promise<void> {
    // `Toasts.clear()`'s own doc: a sign-out "must not leave the previous
    // session's words on screen" — a toast raised just before the operator
    // clicks sign-out must not still be reading on a shared kiosk terminal
    // once the next operator sits down.
    this.toasts.clear();
    // A column ticked a moment ago is still waiting out its debounce; it is written before the token goes.
    // Bounded, because a sign-out that waits on a slow network is worse than a toggle that is lost. The
    // mirror and the remembered account language are removed once nobody is signed in (`OwnPreferences`).
    await Promise.race([
      this.preferences.flush().catch(() => undefined),
      new Promise<void>((resolve) => setTimeout(resolve, SIGN_OUT_FLUSH_MS)),
    ]);
    this.auth.logout().subscribe();
  }
}
