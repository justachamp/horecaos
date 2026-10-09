import { ChangeDetectionStrategy, Component, computed, inject, input, output } from '@angular/core';

import { ContentEventsService } from '../../services/content-events.service';
import type { ContentCarouselItem, ContentCategoryRef } from '../../types/content.types';
import { resolveContentLink } from '../../utils/content-links';
import { ContentImpressionDirective } from '../content-impression/content-impression.directive';
import { ContentLinkComponent } from '../content-link/content-link.component';
import { ContentMediaComponent } from '../content-media/content-media.component';

/**
 * One banner or promo card: the picture, the brand's words over it, and a tap target when the item
 * links somewhere.
 *
 * A tile is a link only when its link resolves to somewhere this storefront can go (`NONE`, and
 * `PROMOTION` which has no screen here, are plain figures), and only a link shows its call to
 * action: a "Order now" button that does nothing is worse than no button. Text is written with
 * interpolation only, in the language the document says it is in (`lang`), so a title that happens
 * to contain markup is shown as text.
 *
 * It reports an impression when it has been on screen and a click when the link is taken. When its
 * image (or video) cannot be shown it says so with {@link failed} and its owner removes it.
 */
@Component({
  selector: 'app-content-tile',
  standalone: true,
  imports: [ContentLinkComponent, ContentMediaComponent, ContentImpressionDirective],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'block' },
  template: `
    <article
      class="relative overflow-hidden rounded-2xl bg-surface-raised"
      [class]="ratio() === 'card' ? 'aspect-[4/3]' : 'aspect-[16/7]'"
      [appContentImpression]="impressionTarget()"
    >
      <app-content-link
        [target]="target()"
        anchorClass="relative m-0 block h-full w-full"
        (activated)="onActivated()"
      >
        <app-content-media
          [media]="item().media"
          [eager]="eager()"
          [sizes]="sizes()"
          (failed)="failed.emit()"
        />
        @if (item().title || item().subtitle || (target() && item().ctaLabel)) {
          <div
            class="pointer-events-none absolute inset-x-0 bottom-0 bg-gradient-to-t from-black/70 to-transparent p-3 text-white"
            [attr.lang]="item().textLocale || null"
          >
            @if (item().title) {
              <h3 class="text-sm font-semibold leading-snug">{{ item().title }}</h3>
            }
            @if (item().subtitle) {
              <p class="mt-0.5 text-xs leading-snug opacity-90">{{ item().subtitle }}</p>
            }
            @if (target() && item().ctaLabel) {
              <span
                class="mt-2 inline-block rounded-full bg-brand-accent px-3 py-1 text-xs font-semibold text-white"
                >{{ item().ctaLabel }}</span
              >
            }
          </div>
        }
      </app-content-link>
    </article>
  `,
})
export class ContentTileComponent {
  private readonly events = inject(ContentEventsService);

  readonly item = input.required<ContentCarouselItem>();
  /** The menu's categories, so a category link can carry its name to the category page. */
  readonly categories = input<readonly ContentCategoryRef[]>([]);
  /** The first image on the page loads at once; every other one is lazy. */
  readonly eager = input(false);
  readonly sizes = input('100vw');
  /** `wide` for the carousel, `card` for the two-column grid. */
  readonly ratio = input<'wide' | 'card'>('wide');
  /** The media could not be shown; the tile should be removed. */
  readonly failed = output<void>();

  protected readonly target = computed(() =>
    resolveContentLink(this.item().link, this.categories()),
  );
  protected readonly impressionTarget = computed(() => ({
    id: this.item().id,
    revisionNo: this.item().revisionNo,
  }));

  protected onActivated(): void {
    this.events.click(this.item().id, this.item().revisionNo);
  }
}
