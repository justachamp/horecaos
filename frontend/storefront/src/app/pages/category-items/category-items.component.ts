import {
  ChangeDetectionStrategy,
  Component,
  OnDestroy,
  OnInit,
  inject,
  signal,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router } from '@angular/router';
import { NavigationHistoryService } from '../../services/navigation-history.service';
import { FoodCardComponent } from '../../shared/food-card/food-card.component';
import { TranslatePipe } from '../../shared/translate/translate.pipe';
import { MenuService } from '../../services/menu.service';
import { LangService } from '../../services/lang.service';
import { TranslateService } from '../../services/translate.service';
import type { MenuItem } from '../../types/home.types';

@Component({
  selector: 'app-category-items',
  templateUrl: './category-items.component.html',
  styleUrl: './category-items.component.scss',
  imports: [CommonModule, FoodCardComponent, TranslatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CategoryItemsComponent implements OnInit, OnDestroy {
  private readonly router = inject(Router);
  private readonly history = inject(NavigationHistoryService);
  private readonly menuService = inject(MenuService);
  private readonly langService = inject(LangService);
  private readonly translate = inject(TranslateService);

  readonly loading = signal(false);
  readonly error = signal<string | null>(null);
  readonly categoryTitle = signal('');
  readonly items = signal<MenuItem[]>([]);

  ngOnInit(): void {
    const stored = sessionStorage.getItem('mar_selected_cat');
    let categoryId: string;
    let storedName: string | undefined;
    if (stored) {
      try {
        const category = JSON.parse(stored) as { id: string; name?: string };
        categoryId = category.id;
        storedName = category.name;
        if (storedName) {
          this.categoryTitle.set(storedName);
        }
      } catch {
        this.router.navigate(['/home']).catch(() => {});
        return;
      }
    } else {
      this.router.navigate(['/home']).catch(() => {});
      return;
    }
    this.loading.set(true);
    this.error.set(null);
    // Read out of the already-loaded menu rather than fetched. The platform has
    // no per-category endpoint because it does not need one: the category names
    // its products and the menu carries them all.
    this.menuService
      .categoryItems(categoryId, this.langService.langId())
      .then((res) => {
        this.categoryTitle.set(res.name || storedName || '');
        this.items.set(res.items as MenuItem[]);
      })
      .catch(() => this.error.set(this.translate.get('errors.generic')))
      .finally(() => this.loading.set(false));
  }
  ngOnDestroy(): void {
    sessionStorage.removeItem('mar_selected_cat');
  }

  goBack(): void {
    this.history.back('/home');
  }
}
