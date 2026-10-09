import { ChangeDetectionStrategy, Component, computed, input, signal } from '@angular/core';

import type { ContentCarouselItem, ContentCategoryRef } from '../../types/content.types';
import { TranslatePipe } from '../translate/translate.pipe';
import { ContentTileComponent } from '../content-tile/content-tile.component';

/**
 * The promo cards that sit in a two-column grid on the home screen (ADR 0157, Release 2): the same
 * tile as the carousel, in a grid. A card whose media fails to load disappears; no cards, no grid.
 */
@Component({
  selector: 'app-content-promo-grid',
  standalone: true,
  imports: [ContentTileComponent, TranslatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'contents' },
  template: `
    @if (visible().length > 0) {
      <section [attr.aria-label]="'content.promoGrid' | t">
        <ul class="grid grid-cols-2 gap-3">
          @for (item of visible(); track keyOf(item)) {
            <li>
              <app-content-tile
                [item]="item"
                [categories]="categories()"
                ratio="card"
                sizes="(min-width: 430px) 190px, 44vw"
                (failed)="markFailed(item)"
              />
            </li>
          }
        </ul>
      </section>
    }
  `,
})
export class ContentPromoGridComponent {
  readonly items = input<readonly ContentCarouselItem[]>([]);
  readonly categories = input<readonly ContentCategoryRef[]>([]);

  private readonly failedKeys = signal<ReadonlySet<string>>(new Set());

  protected readonly visible = computed(() => {
    const failed = this.failedKeys();
    return this.items().filter((item) => !failed.has(this.keyOf(item)));
  });

  protected keyOf(item: ContentCarouselItem): string {
    return `${item.id}#${item.revisionNo}`;
  }

  protected markFailed(item: ContentCarouselItem): void {
    this.failedKeys.update((keys) => new Set(keys).add(this.keyOf(item)));
  }
}
