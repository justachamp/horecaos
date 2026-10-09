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
import type { CategoryItem, MenuCategory, MenuItem } from '../../types/home.types';
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
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly reducedMotion = inject(ReducedMotionService);

  readonly categories = input.required<readonly MenuCategory[]>();
  /**
   * A category the screen asks the grid to show -- a content link to a category lands here (ADR
   * 0157). Applied once per request, and only when the menu has that category: a link to one it
   * lacks leaves the grid showing everything. The customer is free to choose another afterwards.
   */
  readonly requestedCategoryId = input<string | null>(null);
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

  /** The sections to draw: the chosen category only, and never an empty one. */
  protected readonly visible = computed(() => {
    const active = this.activeCategoryId();
    return this.sections().filter(
      (section) => section.items.length > 0 && (active === null || section.id === active),
    );
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

  protected selectCategory(categoryId: string | null): void {
    this.activeCategoryId.set(categoryId);
  }
}
