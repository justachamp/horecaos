import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';

import { APP_CONFIG } from '../../core/config/app-config';
import { IconComponent } from '../../shared/icon/icon.component';
import { LangService } from '../../services/lang.service';
import { MenuGridComponent } from '../../shared/menu-grid/menu-grid.component';
import { MenuService } from '../../services/menu.service';
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
 * The design also shows a stories rail and a promotions carousel above the
 * menu; neither has a platform source, so neither is rendered here. They are
 * named in the component's own gap list rather than filled with placeholder
 * content, because a storefront that invents merchandising shows a tenant
 * something they never authored.
 */
@Component({
  selector: 'app-home',
  standalone: true,
  imports: [IconComponent, MenuGridComponent, TranslatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './home.component.html',
  styleUrl: './home.component.scss',
})
export class HomeComponent {
  private readonly menu = inject(MenuService);
  private readonly lang = inject(LangService);
  private readonly config = inject(APP_CONFIG);

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
