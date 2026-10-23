import { ChangeDetectionStrategy, Component, computed, input, signal } from '@angular/core';

import type { ContentBrandSwitcherEntry } from '../../types/content.types';
import { mediaPlan } from '../../utils/content-media';
import { initialOf } from '../../utils/content-text';
import { ContentMediaComponent } from '../content-media/content-media.component';
import { TranslatePipe } from '../translate/translate.pipe';

/**
 * The brand switcher (ADR 0157 Release 4, ADR 0183 D17): a row of the tenant's other brands under
 * the header, each a link to that brand's own website.
 *
 * It draws nothing for a brand that offers none, so a single-brand storefront is exactly what it
 * was. A link is a plain `https:` anchor to an address the platform resolved from the target
 * website's verified hostname on this read (and `parseContentHome` has checked again); it leaves
 * this storefront, so it opens with `rel="noopener noreferrer"`. A logo that cannot load gives way
 * to the brand's first letter, since the name is still worth following.
 */
@Component({
  selector: 'app-content-brand-switcher',
  standalone: true,
  imports: [ContentMediaComponent, TranslatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './content-brand-switcher.component.html',
  styleUrl: './content-brand-switcher.component.scss',
})
export class ContentBrandSwitcherComponent {
  readonly entries = input.required<readonly ContentBrandSwitcherEntry[]>();

  private readonly brokenLogos = signal<ReadonlySet<string>>(new Set());

  protected readonly rail = computed(() =>
    this.entries().map((entry) => ({
      entry,
      // A logo is a picture: a poster if it were ever a video, never a playing clip.
      plan:
        entry.logo && !this.brokenLogos().has(entry.targetBrandId)
          ? mediaPlan(entry.logo, true)
          : null,
      initial: initialOf(entry.name),
    })),
  );

  protected logoFailed(id: string): void {
    this.brokenLogos.update((ids) => new Set(ids).add(id));
  }
}
