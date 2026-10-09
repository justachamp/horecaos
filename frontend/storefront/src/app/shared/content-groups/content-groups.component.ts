import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';

import { ContentEventsService } from '../../services/content-events.service';
import type { ContentGroup } from '../../types/content.types';
import { resolveGroupItems, type MenuSection } from '../../utils/content-groups';
import { ContentImpressionDirective } from '../content-impression/content-impression.directive';
import { FoodCarouselComponent } from '../food-carousel/food-carousel.component';

/**
 * The brand's product groups on the home screen ("Chef's picks", "New this week"): a titled rail of
 * dishes per group.
 *
 * The document names products and categories by id and nothing else, so the dishes come from the
 * menu the page already holds (`resolveGroupItems`): the server is never asked for products a
 * second time, and what a dish costs or whether it is sold out stays the menu's business. A group
 * that ends up with no dishes (every id gone from the menu, or the menu not loaded yet) renders
 * nothing, not even its title.
 *
 * Dishes are drawn by the app's own food card, so adding to the cart, the product link and the
 * favourite heart behave exactly as they do under the menu below. A tap on a dish link counts as
 * a click on the group.
 */
@Component({
  selector: 'app-content-groups',
  standalone: true,
  imports: [FoodCarouselComponent, ContentImpressionDirective],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'contents' },
  template: `
    @for (section of sections(); track section.group.id + '#' + section.group.revisionNo) {
      <section
        class="space-y-2"
        [attr.lang]="section.group.textLocale || null"
        [appContentImpression]="{ id: section.group.id, revisionNo: section.group.revisionNo }"
        [impressionThreshold]="0.1"
        (click)="onClick($event, section.group)"
      >
        <h2 class="text-lg font-semibold text-text-primary">{{ section.group.title }}</h2>
        <app-food-carousel [items]="section.items" />
      </section>
    }
  `,
})
export class ContentGroupsComponent {
  private readonly events = inject(ContentEventsService);

  readonly groups = input<readonly ContentGroup[]>([]);
  /** The menu the page already holds: each category with its dishes, in the menu's order. */
  readonly menu = input<readonly MenuSection[]>([]);

  protected readonly sections = computed(() =>
    this.groups()
      .map((group) => ({ group, items: resolveGroupItems(group, this.menu()) }))
      .filter((section) => section.items.length > 0),
  );

  protected onClick(event: Event, group: ContentGroup): void {
    const target = event.target;
    if (target instanceof Element && target.closest('a') !== null) {
      this.events.click(group.id, group.revisionNo);
    }
  }
}
