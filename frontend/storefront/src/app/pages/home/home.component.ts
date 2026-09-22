import {
  afterNextRender,
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  Injector,
  OnInit,
  inject,
  signal,
  computed,
  viewChild,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router } from '@angular/router';
import { SectionHeaderComponent } from '../../shared/section-header/section-header.component';
import { FoodCarouselComponent } from '../../shared/food-carousel/food-carousel.component';
import { FoodCardComponent } from '../../shared/food-card/food-card.component';
import { TopBarComponent } from '../../shared/top-bar/top-bar.component';
import { CartHintBadgeComponent } from '../../shared/cart-hint-badge/cart-hint-badge.component';
import { TranslatePipe } from '../../shared/translate/translate.pipe';
import { TranslateService } from '../../services/translate.service';
import { MenuService } from '../../services/menu.service';
import { DeliverySelectionService } from '../../services/delivery-selection.service';
import { FavouritesService } from '../../services/favourites.service';
import { LangService } from '../../services/lang.service';
import { UiCartService } from '../../services/ui-cart.service';
import { CustomerProfileService } from '../../services/customer-profile.service';
import { TelegramWebappService } from '../../services/telegram-webapp.service';
import { hardReloadTelegramEntryPage } from '../../utils/telegram-entry-reload';
import type { CustomerUiResponse, MenuItem, PopularCategory } from '../../types/home.types';
import { FEATURES } from '../../core/config/features';
import { APP_CONFIG } from '../../core/config/app-config';
import { Session } from '../../core/auth/session';
import {
  FulfillmentModeService,
  type FulfillmentModeAvailability,
} from '../../services/fulfillment-mode.service';
import { reasonMessageKey } from '../../core/api/problem-details';
import type { FulfillmentMode } from '../../services/cart.service';

const FULFILLMENT_MODE_KEY = 'horecaos_home_fulfillment_mode';

type UiMode = 'delivery' | 'pickup';

function toBackendMode(mode: UiMode): FulfillmentMode {
  return mode === 'pickup' ? 'PICKUP' : 'DELIVERY';
}

function readPersistedMode(): UiMode | null {
  try {
    const stored = localStorage.getItem(FULFILLMENT_MODE_KEY);
    return stored === 'delivery' || stored === 'pickup' ? stored : null;
  } catch {
    return null;
  }
}

function persistMode(mode: UiMode): void {
  try {
    localStorage.setItem(FULFILLMENT_MODE_KEY, mode);
  } catch {
    // The choice lasts for this page only.
  }
}

@Component({
  selector: 'app-home',
  standalone: true,
  templateUrl: './home.component.html',
  styleUrl: './home.component.scss',
  imports: [CommonModule, SectionHeaderComponent, FoodCarouselComponent, FoodCardComponent, TopBarComponent, CartHintBadgeComponent, TranslatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class HomeComponent implements OnInit {
  private readonly menuService = inject(MenuService);
  private readonly favourites = inject(FavouritesService);
  private readonly langService = inject(LangService);
  private readonly cartService = inject(UiCartService);
  /** The tenant's own name, for copy that greets the customer by brand. */
  protected readonly brandName = inject(APP_CONFIG).brand.displayName;
  private readonly delivery = inject(DeliverySelectionService);
  private readonly router = inject(Router);
  protected readonly telegramWebapp = inject(TelegramWebappService);
  private readonly translate = inject(TranslateService);
  private readonly profile = inject(CustomerProfileService);
  private readonly session = inject(Session);
  private readonly injector = inject(Injector);
  private readonly fulfillmentModes = inject(FulfillmentModeService);

  private readonly menuSection = viewChild<ElementRef<HTMLElement>>('menuSection');

  constructor() {
    hardReloadTelegramEntryPage();
  }

  readonly loading = signal(false);
  readonly error = signal<string | null>(null);
  readonly uiData = signal<CustomerUiResponse | null>(null);

  /** Categories for menu filter (id, label for display) */
  categories: { id: string; label: string }[] = [];

  /** Selected category id; null = "Hammasi" (all) */
  readonly selectedCategoryId = signal<string | null>(null);

  /** 'delivery' | 'pickup' - which mode is selected */
  readonly deliveryMode = signal<UiMode>('delivery');

  /**
   * Which modes this channel sells at this location, and which of those can
   * be ordered right now (`GET .../fulfillment-modes`).
   *
   * `null` until the read answers -- a screen with no answer yet shows both
   * tabs rather than hiding one that will turn out to be sold, since hiding
   * a tab that is in fact offered is worse than showing one for a moment
   * that a still-loading answer will filter out.
   */
  readonly modes = signal<readonly FulfillmentModeAvailability[] | null>(null);

  private soldSet(): ReadonlySet<FulfillmentMode> {
    const modes = this.modes();
    return new Set((modes ?? []).filter((m) => m.sold).map((m) => m.mode));
  }

  /** True while the read has not answered yet, or the mode is genuinely sold. */
  readonly deliverySold = computed(() => this.modes() === null || this.soldSet().has('DELIVERY'));
  readonly pickupSold = computed(() => this.modes() === null || this.soldSet().has('PICKUP'));

  /**
   * Why the currently selected mode cannot be ordered right now, in the
   * customer's language, or `null` when it can (or the read has not
   * answered yet). Closed, outside hours, at capacity and no-live-menu all
   * land here -- the mode is sold, just not orderable this moment.
   */
  readonly currentModeUnavailableMessage = computed(() => {
    this.translate.current();
    const modes = this.modes();
    if (!modes) {
      return null;
    }
    const entry = modes.find((m) => m.mode === toBackendMode(this.deliveryMode()));
    if (!entry || entry.serviceable) {
      return null;
    }
    const key = reasonMessageKey(entry.reason) ?? 'home.modeUnavailableToday';
    return this.translate.get(key);
  });

  /** Popular items carousel (from API populars or first category items) */
  popularItems: MenuItem[] = [];

  /** Menu category items - used for sections */
  readonly menuCategoryItems = signal<{ id: string; name: string; items: MenuItem[] }[]>([]);

  /** Filtered sections based on selected category */
  readonly filteredSections = computed(() => {
    const id = this.selectedCategoryId();
    const items = this.menuCategoryItems();
    if (!id) return items;
    return items.filter((s) => s.id === id);
  });

  /** Offer data for banner section (from API) */
  readonly offerData = signal<CustomerUiResponse['offer']>(null);

  readonly topBarAddressValue = computed(() => {
    this.translate.current();
    return this.cartService.deliveryAddressName() || this.translate.get('cart.addressNotSelected');
  });

  /** Populars with their items (for nav) */
  populars: PopularCategory[] = [];

  /** True when cart has items (for showing cart-hint-badge) */
  readonly hasCartItems = computed(() => this.cartService.totalItemsCount() > 0);

  ngOnInit(): void {
    this.loading.set(true);
    this.error.set(null);
    // One request where there used to be one composite endpoint. The platform
    // serves the whole published menu for a location and every browse screen is
    // a read of that document -- see MenuService.
    this.menuService
      .home(this.langService.langId())
      .then((data) => {
        this.uiData.set(data);
        this.applyUiData(data);
        afterNextRender(() => this.scrollToMenuIfNeeded(), { injector: this.injector });
      })
      .catch(() => this.error.set(this.translate.get('errors.generic')))
      .finally(() => this.loading.set(false));

    // Public, like the menu -- choosing a mode to browse in must not require
    // an account. Best effort: a failed read leaves `modes()` null, which
    // reads as "show every tab" rather than as an error that blocks the
    // whole page.
    //
    // Awaited (not fire-and-forget) below, before the cart is loaded: the
    // default it resolves to (a persisted preference, or whichever mode the
    // channel actually sells) is also what `applyDefaultMode` pushes onto
    // `UiCartService.fulfillmentMode`, which defaults to DELIVERY and
    // otherwise only ever changes from an explicit tab click. Loading the
    // cart before that sync landed used to build/read it under whatever the
    // service's stale default still was -- DELIVERY, on a channel that might
    // sell only PICKUP -- while the tab already showed Pickup selected.
    const modesReady = this.fulfillmentModes
      .modes()
      .then((modes) => {
        this.modes.set(modes);
        this.applyDefaultMode(modes);
      })
      .catch(() => this.modes.set(null));

    // Everything below this line is the customer's own state -- a basket, a
    // favourites list, an account -- and the platform has no anonymous form
    // of any of it (none of /carts, /me/favourites, /me is in
    // SecurityConfiguration's permitAll list). An anonymous visitor cannot
    // hold any of them, so asking would only spend a request on a 401 the
    // interceptor quietly swallows. Guarded the same way BottomNavComponent
    // already guards its own cart read.
    if (!this.session.isAuthenticated()) {
      return;
    }

    void modesReady.then(() => this.cartService.load());
    // Only the address id survives a reload, so the top bar would report "no
    // address" over a choice the customer already made until the row is read
    // back. Authenticated-only for the same reason as the reads around it: the
    // address book is the customer's own and an anonymous visitor has none.
    void this.delivery.ensureAddressResolved();
    // The hearts on the food cards read this, so it has to be loaded before
    // they are drawn or every card starts unmarked and flickers. Skipped
    // entirely while FEATURES.favourites is off: there is no backend for it
    // yet, and every card renders with no heart at all (see FoodCardComponent),
    // so nothing needs the list.
    if (FEATURES.favourites) {
      this.favourites.load().catch(() => {
        // A guest has no list; the interceptor reports anything else.
      });
    }

    // Read once into the shared service rather than mirrored into localStorage.
    // The copy in storage was the app's only record of who the customer was, and
    // every screen trusted it without knowing how old it was. A guest -- signed
    // in with no account at this brand yet -- resolves to null and is not an
    // error.
    this.profile.load().catch(() => {
      // Reported by the error interceptor; the home screen renders without it.
    });
  }

  private applyUiData(data: CustomerUiResponse): void {
    const menu = data.menu;

    this.offerData.set(data.offer ?? null);

    const populars = (data.populars ?? []) as PopularCategory[];
    this.populars = populars;

    this.categories = (menu.category_items ?? []).map((ci) => ({
      id: ci.id,
      label: ci.name
    }));

    /** Ommabop! uses different items to avoid duplicating the first popular section */
    this.popularItems =
      populars.length > 1
        ? (populars[1].items ?? [])
        : (menu.category_items?.[0]?.items ?? []);

    this.menuCategoryItems.set((menu.category_items ?? []).map((ci) => ({
      id: ci.id,
      name: ci.name,
      items: ci.items ?? []
    })));
  }

  selectCategoryForNav(cat: { id: string; label: string }): void {
    sessionStorage.setItem('mar_selected_cat', JSON.stringify(cat));
  }

  selectCategory(id: string | null): void {
    this.selectedCategoryId.set(id);
  }

  /**
   * Picks the mode to open on, once the channel's real answer is in.
   *
   * The persisted choice wins when it is still sold -- a customer who always
   * picks up should not be defaulted back to delivery on every visit. Failing
   * that, whichever sold mode the current selection already is stands;
   * failing *that* (the default `'delivery'` was never actually offered),
   * this falls to the other sold mode instead of opening on a tab that turns
   * out to be hidden.
   */
  private applyDefaultMode(modes: readonly FulfillmentModeAvailability[]): void {
    const sold = new Set(modes.filter((m) => m.sold).map((m) => m.mode));
    if (sold.size === 0) {
      // Nothing sold at all -- leave the current selection; every tab will
      // read as unavailable and the screens downstream explain why.
      return;
    }
    const persisted = readPersistedMode();
    let mode = this.deliveryMode();
    if (persisted && sold.has(toBackendMode(persisted))) {
      mode = persisted;
    } else if (!sold.has(toBackendMode(mode))) {
      mode = sold.has('DELIVERY') ? 'delivery' : 'pickup';
    }
    this.deliveryMode.set(mode);
    // Keeps `UiCartService.fulfillmentMode` -- which defaults to DELIVERY and
    // otherwise changes only from `setDeliveryMode`'s explicit tab click --
    // in step with whatever this resolved to. Without it, a pickup-only
    // channel or a persisted pickup preference left the cart service still
    // building/reading a DELIVERY cart while the tab already showed Pickup
    // selected. `switchFulfillmentMode` itself no-ops once the mode already
    // matches, so this is safe to call every time the read settles.
    void this.cartService.switchFulfillmentMode(toBackendMode(mode));
  }

  setDeliveryMode(mode: UiMode): void {
    this.deliveryMode.set(mode);
    persistMode(mode);
    // The cart carries the mode and the platform fixes it at creation, so this
    // rebuilds the basket when it has to. Without it a customer could choose
    // collection and still be checked out for delivery -- and then be asked for
    // a delivery address they never wanted to give.
    void this.cartService.switchFulfillmentMode(toBackendMode(mode));
  }

  goToSearch(): void {
    this.router.navigate(['/search']);
  }

  private scrollToMenuIfNeeded(): void {
    const state = history.state as { scrollToMenyu?: boolean } | null;
    if (!state?.scrollToMenyu) {
      return;
    }
    this.menuSection()?.nativeElement.scrollIntoView({ behavior: 'auto', block: 'start' });
  }
}
