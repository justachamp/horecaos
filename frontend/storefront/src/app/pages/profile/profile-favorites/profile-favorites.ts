import { ChangeDetectionStrategy, Component, OnInit, inject, signal, computed } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FoodCardComponent } from '../../../shared/food-card/food-card.component';
import { TranslatePipe } from '../../../shared/translate/translate.pipe';
import { BackDirective } from '../../../shared/back/back.directive';
import { MenuService } from '../../../services/menu.service';
import { LangService } from '../../../services/lang.service';
import { TranslateService } from '../../../services/translate.service';
import { FavouritesService } from '../../../services/favourites.service';
import type { MenuItem } from '../../../types/home.types';

@Component({
  selector: 'app-profile-favorites',
  templateUrl: './profile-favorites.html',
  styleUrl: './profile-favorites.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
  standalone: true,
  imports: [CommonModule, FoodCardComponent, TranslatePipe, BackDirective],
})
export class ProfileFavoritesComponent implements OnInit {
  private readonly menuService = inject(MenuService);
  private readonly langService = inject(LangService);
  private readonly translate = inject(TranslateService);
  private readonly favouritesService = inject(FavouritesService);

  readonly loading = signal(true);
  readonly error = signal<string | null>(null);
  private readonly baseFavorites = signal<MenuItem[]>([]);

  /** Filtered by favouritesService so removals update the list */
  readonly favorites = computed(() => {
    const base = this.baseFavorites();
    const added = this.favouritesService.addedIds();
    return base.filter((i) => added.has(i.id));
  });

  ngOnInit(): void {
    this.loading.set(true);
    this.error.set(null);
    // The favourited ids are local (see FavouritesService); the items themselves
    // are resolved against the live menu, so a dish that has been withdrawn or
    // that this branch does not serve simply drops out of the list rather than
    // showing as a card that cannot be ordered.
    // Read the list from the platform first: it is the customer's, not this
    // device's, and a session that has never opened this screen holds nothing.
    this.favouritesService
      .load()
      .then(() => this.menuService.home(this.langService.langId()))
      .then((data) => {
        const favourited = new Set(this.favouritesService.ids());
        const items = data.menu.category_items
          .flatMap((category) => category.items)
          .filter((item) => favourited.has(item.id));
        this.baseFavorites.set(items);
      })
      .catch(() => this.error.set(this.translate.get('errors.generic')))
      .finally(() => this.loading.set(false));
  }
}
