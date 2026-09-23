import { Pipe, PipeTransform } from '@angular/core';

/**
 * Splits a channel page's "markdown-ish" plain text body into paragraphs.
 *
 * V0404's own migration comment is the contract this pipe implements:
 * "plain text with blank-line paragraph breaks, never raw HTML" — so unlike
 * {@link TermsSectionsPipe}'s sibling handling for `q-rich-text` HTML,
 * there is no `[innerHTML]` branch here to sanitize around. Angular's
 * default text interpolation in the template already escapes every
 * paragraph, so a body containing `<script>` renders as the literal text,
 * never as markup — safe by construction rather than by an allowlist that
 * has to keep up with a new tag.
 */
@Pipe({ name: 'channelPageContent', standalone: true })
export class ChannelPageContentPipe implements PipeTransform {
  transform(value: string | null | undefined): string[] {
    if (!value) return [];
    return value
      .split(/\n\s*\n/)
      .map((paragraph) => paragraph.trim())
      .filter((paragraph) => paragraph.length > 0);
  }
}
