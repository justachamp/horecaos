import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';

import { ReducedMotionService } from '../../services/reduced-motion.service';
import type { ContentCarouselItem } from '../../types/content.types';
import { mediaPlan } from '../../utils/content-media';
import { TranslatePipe } from '../translate/translate.pipe';
import { ContentTileComponent } from '../content-tile/content-tile.component';

/**
 * The home carousel of banners and promo cards (ADR 0157): a horizontal scroll-snap list, one tile
 * per item in the brand's order.
 *
 * A tile that has no picture it can draw, or whose picture fails to load, disappears; a carousel
 * left with no tile draws nothing at all -- no heading, no empty frame.
 */
@Component({
  selector: 'app-content-carousel',
  standalone: true,
  imports: [ContentTileComponent, TranslatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './content-carousel.component.html',
  styleUrl: './content-carousel.component.scss',
})
export class ContentCarouselComponent {
  private readonly reducedMotion = inject(ReducedMotionService);

  readonly items = input.required<readonly ContentCarouselItem[]>();

  private readonly failedIds = signal<ReadonlySet<string>>(new Set());

  protected readonly visible = computed(() =>
    this.items().filter(
      (item) =>
        !this.failedIds().has(item.id) &&
        mediaPlan(item.media, this.reducedMotion.reduced()) !== null,
    ),
  );

  protected drop(id: string): void {
    this.failedIds.update((ids) => new Set(ids).add(id));
  }
}
