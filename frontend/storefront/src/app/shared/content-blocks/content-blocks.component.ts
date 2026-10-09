import { ChangeDetectionStrategy, Component, input, signal } from '@angular/core';

import type { EditorialBlock } from '../../types/editorial.types';
import { ContentMediaComponent } from '../content-media/content-media.component';
import { ContentInlinesComponent } from './content-inlines.component';

/**
 * The body of an editorial document (ADR 0157, Decision 6): the closed set of block types, each
 * with one fixed template.
 *
 * A body is data, never markup: there is no block that carries HTML and no code path here that
 * turns text into elements. A paragraph is a `<p>`, a heading an `<h2>` or `<h3>`, a quote a
 * `<blockquote>`, a list an `<ol>` or `<ul>`, an image a `<figure>` drawn from its renditions, a
 * divider an `<hr>`; the text inside is {@link ContentInlinesComponent}'s. A block type this
 * client does not know (the server may add one before a storefront is rebuilt) is skipped and the
 * rest of the body is drawn; an image that does not load takes only its own block with it.
 */
@Component({
  selector: 'app-content-blocks',
  standalone: true,
  imports: [ContentInlinesComponent, ContentMediaComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="space-y-4 text-sm leading-relaxed text-zinc-300" [attr.lang]="locale() || null">
      @for (block of blocks(); track $index) {
        @if (block.type === 'PARAGRAPH') {
          <p><app-content-inlines [inlines]="block.inlines" /></p>
        } @else if (block.type === 'HEADING') {
          @if (block.level === 2) {
            <h2 class="pt-2 text-lg font-semibold text-text-primary">
              <app-content-inlines [inlines]="block.inlines" />
            </h2>
          } @else {
            <h3 class="pt-1 text-base font-semibold text-text-primary">
              <app-content-inlines [inlines]="block.inlines" />
            </h3>
          }
        } @else if (block.type === 'QUOTE') {
          <blockquote class="border-l-2 border-brand-accent pl-3 italic">
            <app-content-inlines [inlines]="block.inlines" />
          </blockquote>
        } @else if (block.type === 'LIST') {
          @if (block.ordered) {
            <ol class="list-decimal space-y-1 pl-5">
              @for (item of block.items; track $index) {
                <li><app-content-inlines [inlines]="item" /></li>
              }
            </ol>
          } @else {
            <ul class="list-disc space-y-1 pl-5">
              @for (item of block.items; track $index) {
                <li><app-content-inlines [inlines]="item" /></li>
              }
            </ul>
          }
        } @else if (block.type === 'IMAGE') {
          @if (!failedImages().has($index)) {
            <figure class="m-0 space-y-1">
              <div class="overflow-hidden rounded-2xl bg-surface-raised">
                <app-content-media
                  [media]="block.media"
                  sizes="(min-width: 430px) 390px, 100vw"
                  (failed)="markFailed($index)"
                />
              </div>
              @if (block.caption; as caption) {
                <figcaption class="text-xs text-zinc-400">
                  <app-content-inlines [inlines]="caption" />
                </figcaption>
              }
            </figure>
          }
        } @else if (block.type === 'DIVIDER') {
          <hr class="border-zinc-700" />
        }
      }
    </div>
  `,
})
export class ContentBlocksComponent {
  readonly blocks = input<readonly EditorialBlock[]>([]);
  /** The language the body is written in. */
  readonly locale = input('');

  protected readonly failedImages = signal<ReadonlySet<number>>(new Set());

  protected markFailed(index: number): void {
    this.failedImages.update((set) => new Set(set).add(index));
  }
}
