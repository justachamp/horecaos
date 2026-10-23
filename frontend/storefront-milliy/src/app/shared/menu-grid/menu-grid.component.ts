import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
  untracked,
} from '@angular/core';

import { ReducedMotionService } from '../../services/reduced-motion.service';
import type { StorefrontLayout } from '../../types/content.types';
import type { CategoryItem, MenuCategory, MenuItem } from '../../types/home.types';
import { filterOptionsOf, matchesAllTags } from '../../utils/menu-tags';
import { CategoriesOverlayComponent } from '../categories-overlay/categories-overlay.component';
import { DishCardComponent } from '../dish-card/dish-card.component';
import { TranslatePipe } from '../translate/translate.pipe';

/**
 * How many categories the marketplace layout's compact rail names before the rest go behind «all
 * categories» (ADR 0183 D17). Five fits a phone's width without much scrolling and keeps the rail a
 * rail.
 */
export const COMPACT_RAIL_SIZE = 5;

/**
 * The menu as a category rail over a list of dish cards.
 *
 * One component for the two places a menu is shown -- the home screen and the
 * table-QR screen -- so a dish looks and reads the same on both, including the
 * sold-out and sale-window states (`DishCardComponent`). It owns only the
 * category filter; what the menu contains, and what to say when it is empty,
 * stays with the screen that read it.
 *
 * With the brand's `MARKETPLACE` layout (ADR 0183 D17) the rail is compact: the first few categories
 * and a button that opens every category in an overlay. Anything else is today's rail, all of them.
 */
@Component({
  selector: 'app-menu-grid',
  standalone: true,
  imports: [CategoriesOverlayComponent, DishCardComponent, TranslatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './menu-grid.component.html',
  styleUrl: './menu-grid.component.scss',
})
export class MenuGridComponent {
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly reducedMotion = inject(ReducedMotionService);

  readonly categories = input.required<readonly MenuCategory[]>();
  /**
   * A category the screen asks the grid to show -- a content link to a category lands here (ADR
   * 0157). Applied once per request, and only when the menu has that category: a link to one it
   * lacks leaves the grid showing everything. The customer is free to choose another afterwards.
   */
  readonly requestedCategoryId = input<string | null>(null);
  /** The brand's category layout; the table screen leaves it at the standard rail. */
  readonly layout = input<StorefrontLayout>('STANDARD');
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
  protected readonly overlayOpen = signal(false);

  protected readonly marketplace = computed(() => this.layout() === 'MARKETPLACE');

  /**
   * The categories the rail draws. Standard: all of them, as it always did. Marketplace: the first
   * few, with the chosen category kept in view when it is further along, so choosing from the overlay
   * never leaves the rail showing nothing selected.
   */
  protected readonly railCategories = computed(() => {
    const all = this.categories();
    if (!this.marketplace() || all.length <= COMPACT_RAIL_SIZE) {
      return all;
    }
    const head = all.slice(0, COMPACT_RAIL_SIZE);
    const active = this.activeCategoryId();
    const chosen = active === null ? undefined : all.find((category) => category.id === active);
    if (chosen === undefined || head.some((category) => category.id === chosen.id)) {
      return head;
    }
    return [...head.slice(0, COMPACT_RAIL_SIZE - 1), chosen];
  });

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

  private appliedRequest: string | null = null;

  constructor() {
    effect(() => {
      const requested = this.requestedCategoryId();
      const known = requested !== null && this.categories().some((c) => c.id === requested);
      if (!known || requested === this.appliedRequest) {
        return;
      }
      this.appliedRequest = requested;
      untracked(() => {
        this.activeCategoryId.set(requested);
        const element = this.host.nativeElement;
        if (typeof element.scrollIntoView === 'function') {
          element.scrollIntoView({
            block: 'start',
            behavior: this.reducedMotion.reduced() ? 'auto' : 'smooth',
          });
        }
      });
    });
  }

  /** True when a filter is on and nothing matches it, so the screen says so rather than going blank. */
  protected readonly nothingMatches = computed(
    () => this.selectedTags().size > 0 && this.visible().length === 0,
  );

  protected selectCategory(categoryId: string | null): void {
    this.activeCategoryId.set(categoryId);
  }

  /** A choice made in the «all categories» overlay: apply it and close the overlay. */
  protected chooseFromOverlay(categoryId: string | null): void {
    this.selectCategory(categoryId);
    this.overlayOpen.set(false);
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
