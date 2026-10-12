import { ChangeDetectionStrategy, Component, computed, input, signal } from '@angular/core';

import type { ContentCarouselItem, ContentCategoryRef } from '../../types/content.types';
import { TranslatePipe } from '../translate/translate.pipe';
import { ContentTileComponent } from '../content-tile/content-tile.component';

/**
 * The home banner carousel (ADR 0157, `HOME_CAROUSEL`): a horizontal CSS scroll-snap strip of
 * banners and promo cards, in the order of the document.
 *
 * Scroll-snap rather than a script: it needs no JavaScript to scroll, it works with touch,
 * trackpad and keyboard (the strip takes focus), and it costs nothing to ship. A tile whose media
 * fails to load disappears, and when every tile has gone the section goes with them; an empty
 * document leaves no empty frame behind.
 */
@Component({
  selector: 'app-content-carousel',
  standalone: true,
  imports: [ContentTileComponent, TranslatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'contents' },
  template: `
    @if (visible().length > 0) {
      <section [attr.aria-label]="'content.carousel' | t">
        <ul
          class="no-scrollbar -mx-4 flex snap-x snap-mandatory gap-3 overflow-x-auto px-4 pb-1"
          tabindex="0"
        >
          @for (item of visible(); track keyOf(item); let first = $first) {
            <li
              [class]="
                visible().length === 1
                  ? 'w-full shrink-0 snap-center'
                  : 'w-[88%] shrink-0 snap-center'
              "
            >
              <app-content-tile
                [item]="item"
                [categories]="categories()"
                [eager]="first"
                sizes="(min-width: 430px) 380px, 88vw"
                (failed)="markFailed(item)"
              />
            </li>
          }
        </ul>
      </section>
    }
  `,
})
export class ContentCarouselComponent {
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
