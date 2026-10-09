import { ChangeDetectionStrategy, Component, computed, inject, input, output } from '@angular/core';

import { ContentEventsService } from '../../services/content-events.service';
import { ReducedMotionService } from '../../services/reduced-motion.service';
import type { ContentCarouselItem } from '../../types/content.types';
import { mediaAspectRatio, mediaPlan } from '../../utils/content-media';
import { resolveContentLink } from '../../utils/content-link';
import { languageTag } from '../../utils/content-text';
import { ContentImpressionDirective } from '../content-impression/content-impression.directive';
import { ContentLinkComponent } from '../content-link/content-link.component';
import { ContentMediaComponent } from '../content-media/content-media.component';

/**
 * One banner or promo card (ADR 0157): a picture with the brand's title, subtitle and call to
 * action. The carousel and the promo grid draw the same tile.
 *
 * It is a link only when the link goes somewhere the client allows (`resolveContentLink`); a tile
 * with no usable link is a plain figure with no call to action either -- a button label with
 * nothing behind it would be a lie. All text is interpolated, so markup in a title is shown, not
 * run. `failed` fires when the picture cannot be loaded, so the list can drop the tile.
 */
@Component({
  selector: 'app-content-tile',
  standalone: true,
  imports: [ContentImpressionDirective, ContentLinkComponent, ContentMediaComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './content-tile.component.html',
  styleUrl: './content-tile.component.scss',
})
export class ContentTileComponent {
  private readonly events = inject(ContentEventsService);
  private readonly reducedMotion = inject(ReducedMotionService);

  readonly item = input.required<ContentCarouselItem>();
  /** True for the first picture on the page, which must not wait to be scrolled near. */
  readonly eager = input(false);
  /** The `sizes` attribute for the picture: how wide the tile is drawn. */
  readonly sizes = input('85vw');
  readonly failed = output<void>();

  protected readonly plan = computed(() =>
    mediaPlan(this.item().media, this.reducedMotion.reduced()),
  );
  protected readonly aspect = computed(() => mediaAspectRatio(this.item().media));
  protected readonly lang = computed(() => languageTag(this.item().textLocale));
  protected readonly linked = computed(() => resolveContentLink(this.item().link) !== null);

  protected clicked(): void {
    const { id, revisionNo } = this.item();
    this.events.report(id, revisionNo, 'CLICK');
  }
}
