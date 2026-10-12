import { ChangeDetectionStrategy, Component, booleanAttribute, input, output } from '@angular/core';

import { BLOCK_LIMITS, Inline, isAllowedHref } from './block-document';

/** Already translated, like every label `q-rich-text` takes. */
export interface RichTextRunLabels {
  readonly text: string;
  readonly bold: string;
  readonly italic: string;
  readonly link: string;
  readonly linkAddress: string;
  readonly linkRefused: string;
  readonly addRun: string;
  readonly removeRun: string;
}

/**
 * The runs of one block: the plain-text pieces a paragraph, a heading, a quote, a list item or a
 * caption is made of, each with `bold`, `italic` and an optional link (ADR 0157, Decision 6).
 *
 * **Structured, not WYSIWYG, on purpose.** A run is a row -- its text, two toggles and a link
 * address -- so what the author edits is exactly what is stored: a typed run, never a string of
 * markup that a parser has to interpret. There is no way to type a tag and have it become one, and
 * a link whose scheme the grammar refuses is flagged on its row while the author types it. A
 * `<textarea>` only ever accepts plain text, which is the property the HTML mode of this editor
 * already rests on.
 *
 * Presentational: it holds no state of its own beyond what the host passes in and reports every
 * change as the complete new list of runs.
 */
@Component({
  selector: 'q-rich-text-runs',
  templateUrl: './rich-text-runs.html',
  styleUrl: './rich-text-runs.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class RichTextRuns {
  readonly runs = input.required<readonly Inline[]>();
  readonly labels = input.required<RichTextRunLabels>();
  readonly disabled = input(false, { transform: booleanAttribute });
  /** A caption is a line, a paragraph is not: it only changes how tall the box starts. */
  readonly rows = input(2);

  readonly runsChange = output<readonly Inline[]>();

  protected readonly maxRuns = BLOCK_LIMITS.maxRunsPerBlock;
  protected readonly maxRunCharacters = BLOCK_LIMITS.maxRunCharacters;

  /**
   * A run with a link address -- even a still-empty one, which is how the row is opened -- shows the
   * address field. The empty address is dropped when the document is compacted.
   */
  protected linkVisible(run: Inline): boolean {
    return run.href !== undefined;
  }

  protected linkRefused(run: Inline): boolean {
    return run.href !== undefined && run.href !== '' && !isAllowedHref(run.href);
  }

  protected setText(index: number, text: string): void {
    this.replace(index, { ...this.runs()[index], text });
  }

  protected toggle(index: number, flag: 'bold' | 'italic'): void {
    const run = this.runs()[index];
    const { [flag]: current, ...rest } = run;
    this.replace(index, current === true ? rest : { ...rest, [flag]: true });
  }

  protected toggleLink(index: number): void {
    const run = this.runs()[index];
    if (this.linkVisible(run)) {
      const { href: _removed, ...rest } = run;
      this.replace(index, rest);
    } else {
      this.replace(index, { ...run, href: '' });
    }
  }

  protected setHref(index: number, href: string): void {
    const run = this.runs()[index];
    this.replace(index, { ...run, href });
  }

  protected add(): void {
    if (this.runs().length < this.maxRuns) {
      this.runsChange.emit([...this.runs(), { text: '' }]);
    }
  }

  protected remove(index: number): void {
    const next = this.runs().filter((_, i) => i !== index);
    this.runsChange.emit(next.length > 0 ? next : [{ text: '' }]);
  }

  private replace(index: number, run: Inline): void {
    this.runsChange.emit(this.runs().map((existing, i) => (i === index ? run : existing)));
  }
}
