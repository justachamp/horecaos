import { ChangeDetectionStrategy, Component, computed, input, output, signal } from '@angular/core';

import type { CategoryItem, MenuCategory, MenuItem } from '../../types/home.types';
import { filterOptionsOf, matchesAllTags } from '../../utils/menu-tags';
import { DishCardComponent } from '../dish-card/dish-card.component';
import { TranslatePipe } from '../translate/translate.pipe';

/**
 * The menu as a category rail over a list of dish cards.
 *
 * One component for the two places a menu is shown -- the home screen and the
 * table-QR screen -- so a dish looks and reads the same on both, including the
 * sold-out and sale-window states (`DishCardComponent`). It owns only the
 * category filter; what the menu contains, and what to say when it is empty,
 * stays with the screen that read it.
 */
@Component({
  selector: 'app-menu-grid',
  standalone: true,
  imports: [DishCardComponent, TranslatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './menu-grid.component.html',
  styleUrl: './menu-grid.component.scss',
})
export class MenuGridComponent {
  readonly categories = input.required<readonly MenuCategory[]>();
  readonly sections = input.required<readonly CategoryItem[]>();
  readonly currency = input<string | null>(null);
  /** False on the table screen, where a dish is not a link into the delivery basket. */
  readonly linked = input(true);
  /** True at a table that takes orders: each dish carries the controls for the table's basket. */
  readonly ordering = input(false);
  /** What the table's basket holds, by variant id. */
  readonly quantities = input<Readonly<Record<string, number>>>({});
  /** A write to the basket is in flight. */
  readonly busy = input(false);
  /** A dish's portion was asked for in a new quantity. */
  readonly quantityChange = output<{ variantId: string; quantity: number }>();
  /** A dish's options are to be chosen for one of its portions. */
  readonly choose = output<{ item: MenuItem; variantId: string }>();

  protected readonly activeCategoryId = signal<string | null>(null);

  /** ADR 0174: the tags the guest has selected as filters. Selected chips combine as AND. */
  protected readonly selectedTags = signal<ReadonlySet<string>>(new Set());

  /** The filter chips the whole menu offers: each tag its author made a filter option, once. */
  protected readonly filterOptions = computed(() =>
    filterOptionsOf(this.sections().flatMap((section) => section.items)),
  );

  /**
   * The sections to draw: the chosen category only, the dishes that carry every selected tag, and never an
   * empty section.
   */
  protected readonly visible = computed(() => {
    const active = this.activeCategoryId();
    const selected = this.selectedTags();
    return this.sections()
      .filter((section) => active === null || section.id === active)
      .map((section) => ({
        ...section,
        items: section.items.filter((item) => matchesAllTags(item, selected)),
      }))
      .filter((section) => section.items.length > 0);
  });

  /** True when a filter is on and nothing matches it, so the screen says so rather than going blank. */
  protected readonly nothingMatches = computed(
    () => this.selectedTags().size > 0 && this.visible().length === 0,
  );

  protected selectCategory(categoryId: string | null): void {
    this.activeCategoryId.set(categoryId);
  }

  protected toggleTag(tagId: string): void {
    this.selectedTags.update((current) => {
      const next = new Set(current);
      if (!next.delete(tagId)) {
        next.add(tagId);
      }
      return next;
    });
  }

  protected clearTags(): void {
    this.selectedTags.set(new Set());
  }
}
