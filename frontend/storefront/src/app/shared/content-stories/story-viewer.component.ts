import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
} from '@angular/core';

import { ContentEventsService } from '../../services/content-events.service';
import { ReducedMotionService } from '../../services/reduced-motion.service';
import type { ContentCategoryRef, ContentStoryGroup } from '../../types/content.types';
import { resolveContentLink } from '../../utils/content-links';
import { ContentLinkComponent } from '../content-link/content-link.component';
import { ContentMediaComponent } from '../content-media/content-media.component';
import { FocusTrapDirective } from '../focus-trap/focus-trap.directive';
import { TranslatePipe } from '../translate/translate.pipe';

/**
 * The full-screen story player (ADR 0157, Release 2).
 *
 * A dialog (`role="dialog"`, `aria-modal`, named by the story) with a progress bar per slide. Each
 * slide shows for its own `durationSeconds`, then the next one follows; a tap on the right of the
 * screen goes forward, on the left goes back, the arrow keys do the same, and the close button and
 * Escape leave. Running off the end of the last slide, or tapping forward on it, finishes the story:
 * a completion is reported once and the viewer closes. Leaving early reports nothing.
 *
 * <h2>Motion</h2>
 * Video slides are muted and inline. Under `prefers-reduced-motion` a video slide shows its poster
 * only and does not advance by itself (the visitor taps forward); image slides still advance, since
 * a still picture changing is not motion.
 *
 * A slide whose media cannot load is skipped; on the last slide that ends the story without
 * counting it as seen.
 *
 * This component is loaded with `@defer` by the story rail, so none of it is in the initial chunk
 * of a home screen that has no stories.
 */
@Component({
  selector: 'app-story-viewer',
  standalone: true,
  imports: [ContentLinkComponent, ContentMediaComponent, FocusTrapDirective, TranslatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: './story-viewer.component.scss',
  host: {
    '(document:keydown.escape)': 'close()',
    '(document:keydown.arrowright)': 'next()',
    '(document:keydown.arrowleft)': 'previous()',
  },
  template: `
    <div
      role="dialog"
      aria-modal="true"
      appFocusTrap
      tabindex="-1"
      class="fixed inset-0 z-[80] flex justify-center bg-black"
      [attr.aria-label]="story().title"
      [attr.lang]="story().textLocale || null"
    >
      <div class="relative h-full w-full max-w-[430px] overflow-hidden text-white">
        @for (current of shown(); track index()) {
          <div class="absolute inset-0">
            <app-content-media
              [media]="current.media"
              [eager]="true"
              [loop]="false"
              sizes="(min-width: 430px) 430px, 100vw"
              (failed)="onSlideFailed()"
            />
          </div>
        }

        <button
          type="button"
          class="absolute inset-y-0 left-0 z-10 w-1/3"
          [attr.aria-label]="'content.storyPrevious' | t"
          (click)="previous()"
        ></button>
        <button
          type="button"
          class="absolute inset-y-0 right-0 z-10 w-2/3"
          [attr.aria-label]="'content.storyNext' | t"
          (click)="next()"
        ></button>

        <ol
          data-testid="story-progress"
          aria-hidden="true"
          class="absolute inset-x-0 top-0 z-20 flex gap-1 px-3 pt-3"
        >
          @for (s of story().slides; track $index) {
            <li class="h-1 flex-1 overflow-hidden rounded-full bg-white/30">
              <span
                class="story-fill block h-full w-full bg-white"
                [class.story-fill-active]="$index === index()"
                [style.transform]="$index < index() ? 'scaleX(1)' : null"
                [style.animation-duration.s]="$index === index() ? slide().durationSeconds : null"
              ></span>
            </li>
          }
        </ol>

        <div class="absolute inset-x-0 top-5 z-30 flex items-center justify-between px-3 pt-2">
          <p class="truncate text-sm font-semibold drop-shadow">{{ story().title }}</p>
          <button
            type="button"
            data-autofocus
            class="flex h-9 w-9 items-center justify-center rounded-full bg-black/50"
            [attr.aria-label]="'content.storyClose' | t"
            (click)="close()"
          >
            <svg
              class="h-4 w-4"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              stroke-width="2"
              aria-hidden="true"
            >
              <path stroke-linecap="round" stroke-linejoin="round" d="M6 6l12 12M18 6L6 18" />
            </svg>
          </button>
        </div>

        <div
          class="pointer-events-none absolute inset-x-0 bottom-0 z-30 space-y-3 bg-gradient-to-t from-black/70 to-transparent p-4 pb-8"
        >
          @if (slide().caption; as caption) {
            <p data-testid="story-caption" class="text-sm leading-snug">{{ caption }}</p>
          }
          @if (target(); as link) {
            <div data-testid="story-cta" class="pointer-events-auto">
              <app-content-link
                [target]="link"
                anchorClass="block w-full rounded-full bg-brand-accent px-4 py-3 text-center text-sm font-semibold text-white"
                (activated)="onCta()"
              >
                {{ 'content.learnMore' | t }}
              </app-content-link>
            </div>
          }
        </div>

        <p class="sr-only" aria-live="polite">
          {{ 'content.storySlide' | t: { current: index() + 1, total: story().slides.length } }}
        </p>
      </div>
    </div>
  `,
})
export class StoryViewerComponent {
  private readonly events = inject(ContentEventsService);
  private readonly motion = inject(ReducedMotionService);

  readonly story = input.required<ContentStoryGroup>();
  readonly categories = input<readonly ContentCategoryRef[]>([]);
  /** The viewer should go away: finished, skipped out of, or nothing left to show. */
  readonly closed = output<void>();

  protected readonly index = signal(0);
  protected readonly slide = computed(() => this.story().slides[this.index()]);
  /** A one-element list, so the media is rebuilt (not patched) for every slide. */
  protected readonly shown = computed(() => [this.slide()]);
  protected readonly target = computed(() =>
    resolveContentLink(this.slide().link, this.categories()),
  );

  private finished = false;

  constructor() {
    effect((onCleanup) => {
      const slide = this.slide();
      if (slide.media.kind === 'VIDEO' && this.motion.reduced()) {
        return;
      }
      const timer = setTimeout(() => this.next(), slide.durationSeconds * 1000);
      onCleanup(() => clearTimeout(timer));
    });
  }

  protected next(): void {
    if (this.index() < this.story().slides.length - 1) {
      this.index.update((i) => i + 1);
    } else {
      this.finish();
    }
  }

  protected previous(): void {
    if (this.index() > 0) {
      this.index.update((i) => i - 1);
    }
  }

  protected close(): void {
    this.closed.emit();
  }

  protected onCta(): void {
    this.events.click(this.story().id, this.story().revisionNo);
  }

  protected onSlideFailed(): void {
    if (this.index() < this.story().slides.length - 1) {
      this.index.update((i) => i + 1);
    } else {
      // Nothing left to show, and the last slide was never seen: leave without a completion.
      this.closed.emit();
    }
  }

  private finish(): void {
    if (!this.finished) {
      this.finished = true;
      this.events.completion(this.story().id, this.story().revisionNo);
    }
    this.closed.emit();
  }
}
