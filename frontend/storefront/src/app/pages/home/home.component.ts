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
import { Session } from '../../core/auth/session';

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
  private readonly delivery = inject(DeliverySelectionService);
  private readonly router = inject(Router);
  protected readonly telegramWebapp = inject(TelegramWebappService);
  private readonly translate = inject(TranslateService);
  private readonly profile = inject(CustomerProfileService);
  private readonly session = inject(Session);
  private readonly injector = inject(Injector);

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
  readonly deliveryMode = signal<'delivery' | 'pickup'>('delivery');

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

    void this.cartService.load();
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

  setDeliveryMode(mode: 'delivery' | 'pickup'): void {
    this.deliveryMode.set(mode);
    // The cart carries the mode and the platform fixes it at creation, so this
    // rebuilds the basket when it has to. Without it a customer could choose
    // collection and still be checked out for delivery -- and then be asked for
    // a delivery address they never wanted to give.
    void this.cartService.switchFulfillmentMode(mode === 'pickup' ? 'PICKUP' : 'DELIVERY');
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
