import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';

import { ReducedMotionService } from '../../services/reduced-motion.service';
import type { ContentCarouselItem } from '../../types/content.types';
import { mediaPlan } from '../../utils/content-media';
import { TranslatePipe } from '../translate/translate.pipe';
import { ContentTileComponent } from '../content-tile/content-tile.component';

/**
 * The two-column grid of promo cards (ADR 0157, `promoGrid`): the same tile as the carousel, laid
 * out in a grid. Like the carousel, a tile with no drawable or loadable picture goes, and an
 * empty grid draws nothing.
 */
@Component({
  selector: 'app-content-promo-grid',
  standalone: true,
  imports: [ContentTileComponent, TranslatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './content-promo-grid.component.html',
  styleUrl: './content-promo-grid.component.scss',
})
export class ContentPromoGridComponent {
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
