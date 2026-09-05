import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';

import { APP_CONFIG } from '../../core/config/app-config';
import { IconComponent } from '../../shared/icon/icon.component';
import { LangService } from '../../services/lang.service';
import { MenuService } from '../../services/menu.service';
import { TranslatePipe } from '../../shared/translate/translate.pipe';
import type { CategoryItem, MenuCategory } from '../../types/home.types';

type LoadState = 'loading' | 'ready' | 'error';

/**
 * The Milliy home screen: brand, search, category rail, then the menu.
 *
 * Reads the same published menu the first storefront reads — one document per
 * location (ADR 0016), browsable without an account. The design also shows a
 * stories rail and a promotions carousel above the menu; neither has a
 * platform source, so neither is rendered here. They are named in the
 * component's own gap list rather than filled with placeholder content,
 * because a storefront that invents merchandising shows a tenant something
 * they never authored.
 */
@Component({
  selector: 'app-home',
  standalone: true,
  imports: [IconComponent, TranslatePipe],
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
  protected readonly activeCategoryId = signal<string | null>(null);

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

  protected selectCategory(categoryId: string | null): void {
    this.activeCategoryId.set(categoryId);
  }
}
