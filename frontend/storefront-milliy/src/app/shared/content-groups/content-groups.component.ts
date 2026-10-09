import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';

import { ContentEventsService } from '../../services/content-events.service';
import type { ContentGroup } from '../../types/content.types';
import type { CategoryItem } from '../../types/home.types';
import { resolveGroupItems } from '../../utils/content-groups';
import { languageTag } from '../../utils/content-text';
import { ContentImpressionDirective } from '../content-impression/content-impression.directive';
import { DishCardComponent } from '../dish-card/dish-card.component';

/**
 * The home product groups (ADR 0157): for each group a titled rail of the dish cards the menu
 * already draws, joined against the menu the page holds.
 *
 * A group is a view over the menu, never a source of products: an id the menu lacks is skipped, a
 * by-category group takes that category's own dishes in the menu's own order, and a group left with
 * nothing draws nothing -- no title over an empty rail. The server is never asked for a product.
 */
@Component({
  selector: 'app-content-groups',
  standalone: true,
  imports: [ContentImpressionDirective, DishCardComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './content-groups.component.html',
  styleUrl: './content-groups.component.scss',
})
export class ContentGroupsComponent {
  private readonly events = inject(ContentEventsService);

  readonly groups = input.required<readonly ContentGroup[]>();
  /** The menu the page already holds. */
  readonly sections = input.required<readonly CategoryItem[]>();
  readonly currency = input<string | null>(null);

  protected readonly rails = computed(() =>
    this.groups()
      .map((group) => ({
        group,
        items: resolveGroupItems(group, this.sections()),
        lang: languageTag(group.textLocale),
      }))
      .filter((rail) => rail.items.length > 0),
  );

  protected clicked(group: ContentGroup): void {
    this.events.report(group.id, group.revisionNo, 'CLICK');
  }
}
