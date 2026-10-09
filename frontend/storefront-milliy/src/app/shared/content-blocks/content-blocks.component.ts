import { NgTemplateOutlet } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, input, signal } from '@angular/core';

import type { EditorialBlock, Inline } from '../../types/editorial.types';
import { safeLink, type SafeLink } from '../../utils/content-href';
import { mediaAspectRatio, mediaPlan, type MediaPlan } from '../../utils/content-media';
import { ContentMediaComponent } from '../content-media/content-media.component';

/** A run of text, with its link only if the link passed the scheme rule. */
interface Run {
  readonly text: string;
  readonly bold: boolean;
  readonly italic: boolean;
  readonly link: SafeLink | null;
}

type Drawn =
  | { readonly key: number; readonly type: 'PARAGRAPH' | 'QUOTE'; readonly runs: readonly Run[] }
  | {
      readonly key: number;
      readonly type: 'HEADING';
      readonly level: 2 | 3;
      readonly runs: readonly Run[];
    }
  | {
      readonly key: number;
      readonly type: 'LIST';
      readonly ordered: boolean;
      readonly items: readonly (readonly Run[])[];
    }
  | {
      readonly key: number;
      readonly type: 'IMAGE';
      readonly plan: MediaPlan;
      readonly alt: string;
      readonly aspect: string | null;
      readonly caption: readonly Run[] | null;
    }
  | { readonly key: number; readonly type: 'DIVIDER' };

function runsOf(inlines: readonly Inline[]): readonly Run[] {
  return inlines.map((inline) => ({
    text: inline.text,
    bold: inline.bold === true,
    italic: inline.italic === true,
    link: inline.href === undefined ? null : safeLink(inline.href),
  }));
}

/**
 * A body -- news, a recipe, a vacancy's text -- drawn block by block (ADR 0157, Decision 6).
 *
 * <h2>One renderer, a fixed template per block type</h2>
 *
 * The server sends typed blocks, never markup, and this is the only component that draws them. Each
 * block type has its own fixed piece of template; text reaches the page by interpolation only, so a
 * `<script>` in a paragraph is shown as the characters it is made of. A block of a type the template
 * does not have is skipped. The set of elements and attributes it can create is closed, and a
 * corpus spec holds it to that.
 *
 * <h2>Links</h2>
 *
 * A run's `href` is set only after `safeLink` has re-applied the scheme rule: `https:` (opened in a
 * new tab with `rel="noopener noreferrer"`), `tel:` or `mailto:`. Anything else is the run's words as
 * plain text, with nothing to click.
 *
 * An image that cannot be loaded is removed; the rest of the body stays.
 */
@Component({
  selector: 'app-content-blocks',
  standalone: true,
  imports: [NgTemplateOutlet, ContentMediaComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './content-blocks.component.html',
  styleUrl: './content-blocks.component.scss',
})
export class ContentBlocksComponent {
  readonly blocks = input.required<readonly EditorialBlock[]>();
  /** The language of the body, when it is not the page's own. */
  readonly lang = input<string | null>(null);

  private readonly failedImages = signal<ReadonlySet<number>>(new Set());

  protected readonly drawn = computed<readonly Drawn[]>(() => {
    const out: Drawn[] = [];
    this.blocks().forEach((block, key) => {
      switch (block.type) {
        case 'PARAGRAPH':
        case 'QUOTE':
          out.push({ key, type: block.type, runs: runsOf(block.inlines) });
          break;
        case 'HEADING':
          out.push({ key, type: 'HEADING', level: block.level, runs: runsOf(block.inlines) });
          break;
        case 'LIST':
          out.push({ key, type: 'LIST', ordered: block.ordered, items: block.items.map(runsOf) });
          break;
        case 'IMAGE': {
          const plan = mediaPlan(block.media, false);
          if (plan !== null && !this.failedImages().has(key)) {
            out.push({
              key,
              type: 'IMAGE',
              plan,
              alt: block.media.alt ?? '',
              aspect: mediaAspectRatio(block.media),
              caption: block.caption ? runsOf(block.caption) : null,
            });
          }
          break;
        }
        case 'DIVIDER':
          out.push({ key, type: 'DIVIDER' });
          break;
        default:
          // A block type this component has no template for is skipped.
          break;
      }
    });
    return out;
  });

  protected dropImage(key: number): void {
    this.failedImages.update((keys) => new Set(keys).add(key));
  }
}
