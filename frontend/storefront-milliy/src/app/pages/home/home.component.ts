import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  signal,
  untracked,
} from '@angular/core';

import { APP_CONFIG } from '../../core/config/app-config';
import { ContentBrandSwitcherComponent } from '../../shared/content-brand-switcher/content-brand-switcher.component';
import { ContentCarouselComponent } from '../../shared/content-carousel/content-carousel.component';
import { ContentGroupsComponent } from '../../shared/content-groups/content-groups.component';
import { ContentPopupComponent } from '../../shared/content-popup/content-popup.component';
import { ContentPromoGridComponent } from '../../shared/content-promo-grid/content-promo-grid.component';
import { ContentService } from '../../services/content.service';
import { ContentStoriesComponent } from '../../shared/content-stories/content-stories.component';
import { EditorialLinksComponent } from '../../shared/editorial-links/editorial-links.component';
import { EditorialService } from '../../services/editorial.service';
import { IconComponent } from '../../shared/icon/icon.component';
import { LangService } from '../../services/lang.service';
import { MenuGridComponent } from '../../shared/menu-grid/menu-grid.component';
import { MenuService } from '../../services/menu.service';
import { PublicReviewsComponent } from '../../shared/public-reviews/public-reviews.component';
import { TranslatePipe } from '../../shared/translate/translate.pipe';
import type { CategoryItem, MenuCategory } from '../../types/home.types';

type LoadState = 'loading' | 'ready' | 'error';

/**
 * The Milliy home screen: brand, search, category rail, then the menu.
 *
 * Reads the same published menu the first storefront reads — one document per
 * location (ADR 0016), browsable without an account. The menu is drawn by
 * `MenuGridComponent`, whose dish cards show a dish that cannot be bought right
 * now as **sold out** or **not on sale right now** (rows 4.4c/4.4d, 4.2g)
 * rather than hiding it or leaving the customer to find out on its page.
 *
 * Above the menu sits what the brand published for the home screen (ADR 0157): a stories
 * rail, a carousel of banners and promo cards, a promo grid, product groups, and an entry
 * pop-up. It is read by `ContentService` in a request of its own, issued beside the menu's and
 * never awaited by it: a failed read is "no content" and the screen then looks exactly as it did
 * before content existed. Nothing here is invented -- a brand that has published nothing shows
 * nothing, because a storefront that makes up merchandising shows a tenant something they never
 * authored.
 *
 * Product groups are joined against the menu this page already holds, so they appear with it; the
 * rest of the content does not wait for the menu.
 *
 * Under the menu sits the restaurant's public rating and, if it has chosen to show them, the comments its
 * customers agreed to make public (ADR 0159). That block renders nothing at all until the platform says
 * there is something to show.
 */
@Component({
  selector: 'app-home',
  standalone: true,
  imports: [
    ContentBrandSwitcherComponent,
    ContentCarouselComponent,
    ContentGroupsComponent,
    ContentPopupComponent,
    ContentPromoGridComponent,
    ContentStoriesComponent,
    EditorialLinksComponent,
    IconComponent,
    MenuGridComponent,
    PublicReviewsComponent,
    TranslatePipe,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './home.component.html',
  styleUrl: './home.component.scss',
})
export class HomeComponent {
  private readonly menu = inject(MenuService);
  private readonly lang = inject(LangService);
  private readonly config = inject(APP_CONFIG);
  private readonly content = inject(ContentService);
  private readonly editorial = inject(EditorialService);

  /**
   * A category to show in the menu, from `?category=` -- where a content link to a category lands
   * (`withComponentInputBinding` feeds the query parameter in).
   */
  readonly category = input<string | undefined>(undefined);

  protected readonly stories = this.content.stories;
  protected readonly carousel = this.content.carousel;
  protected readonly promoGrid = this.content.promoGrid;
  protected readonly groups = this.content.groups;
  protected readonly popup = this.content.popup;
  protected readonly brandSwitcher = this.content.brandSwitcher;
  protected readonly layout = this.content.layout;

  protected readonly brandName = this.config.brand.displayName;
  protected readonly state = signal<LoadState>('loading');
  protected readonly categories = signal<readonly MenuCategory[]>([]);
  protected readonly items = signal<readonly CategoryItem[]>([]);
  protected readonly currency = this.menu.currency;

  /** Whether the branch has anything at all to show; a category with no dishes is not a menu. */
  protected readonly hasDishes = computed(() =>
    this.items().some((category) => category.items.length > 0),
  );

  constructor() {
    void this.load();
    // Which editorial pages the brand runs, so the links below show for those only. A failed read
    // is "none", and it never rejects.
    void this.editorial.loadFeatures();
    // The content read is its own request, started beside the menu's and in the visitor's language;
    // it re-reads when the language changes. It never rejects, so there is nothing to catch here.
    effect(() => {
      const locale = this.lang.langId();
      untracked(() => void this.content.load(locale));
    });
  }

  protected async load(): Promise<void> {
    this.state.set('loading');
    try {
      const response = await this.menu.home(this.lang.langId(), this.config.defaultLocationId);
      this.categories.set(response.menu.categories);
      this.items.set(response.menu.category_items);
      this.state.set('ready');
    } catch {
      this.state.set('error');
    }
  }
}
