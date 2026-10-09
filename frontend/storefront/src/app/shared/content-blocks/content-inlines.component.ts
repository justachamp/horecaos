import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

import type { EditorialInline } from '../../types/editorial.types';
import { contactUrlOrNull } from '../../utils/safe-url';

/**
 * A run of inline text: plain, bold, italic, or a link (ADR 0157, Decision 6).
 *
 * The text is written by interpolation only, so a run that happens to contain `<script>` is shown
 * as those characters. An `href` becomes a link only after the scheme rule is applied *here*, in
 * the client, whatever the server did: `https:`, `tel:` or `mailto:`, and nothing else. Anything
 * else (`http:`, `javascript:`, `data:`, a relative path, a string that does not parse) is drawn
 * as plain text with no anchor at all. An `https:` link opens in a new tab with
 * `rel="noopener noreferrer"`; a phone or mail link stays on the page.
 */
@Component({
  selector: 'app-content-inlines',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  // Angular drops the whitespace between a template's own tags, so the spacing between runs is
  // exactly the spacing in the text the brand typed.
  template: `@for (part of parts(); track $index) {
    @if (part.href) {
      <a
        [href]="part.href"
        [attr.target]="part.external ? '_blank' : null"
        [attr.rel]="part.external ? 'noopener noreferrer' : null"
        class="text-brand-accent underline"
        [class.font-bold]="part.bold"
        [class.italic]="part.italic"
        >{{ part.text }}</a
      >
    } @else if (part.bold && part.italic) {
      <strong
        ><em>{{ part.text }}</em></strong
      >
    } @else if (part.bold) {
      <strong>{{ part.text }}</strong>
    } @else if (part.italic) {
      <em>{{ part.text }}</em>
    } @else {
      <span>{{ part.text }}</span>
    }
  }`,
})
export class ContentInlinesComponent {
  readonly inlines = input<readonly EditorialInline[]>([]);

  protected readonly parts = computed(() =>
    this.inlines().map((inline) => {
      const href = contactUrlOrNull(inline.href);
      return {
        text: inline.text,
        bold: inline.bold === true,
        italic: inline.italic === true,
        href,
        external: href !== null && href.startsWith('https:'),
      };
    }),
  );
}
