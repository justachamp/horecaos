import { DOCUMENT } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  Injector,
  afterNextRender,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
  viewChild,
} from '@angular/core';

import { ContentEventsService } from '../../services/content-events.service';
import { ReducedMotionService } from '../../services/reduced-motion.service';
import type { ContentStoryGroup } from '../../types/content.types';
import { resolveContentLink } from '../../utils/content-link';
import { mediaPlan } from '../../utils/content-media';
import { languageTag } from '../../utils/content-text';
import { trapTab } from '../../utils/focus-trap';
import { ContentLinkComponent } from '../content-link/content-link.component';
import { ContentMediaComponent } from '../content-media/content-media.component';
import { IconComponent } from '../icon/icon.component';
import { TranslatePipe } from '../translate/translate.pipe';

/**
 * The full-screen story viewer (ADR 0157, Release 2).
 *
 * Progress bars across the top, a tap on the right or left edge for the next or previous slide, the
 * slide moving on by itself after its own `durationSeconds`, a caption, a call to action when the
 * slide's link goes somewhere the client allows, and a close button. Arrow keys and Escape work
 * too. When the last slide runs out (or "next" is tapped on it) the viewer reports a COMPLETION and
 * closes; closing any other way is not a completion.
 *
 * Video plays muted and inline. Under `prefers-reduced-motion` a video slide shows only its poster
 * and does not move on by itself -- the visitor taps to continue -- while a still image still
 * advances by its duration (the bar does not animate; the global reduced-motion rule sees to that).
 *
 * It is a modal dialog: focus goes to the close button, Tab stays inside, and focus goes back to
 * where it was when the viewer goes.
 */
@Component({
  selector: 'app-content-story-viewer',
  standalone: true,
  imports: [ContentLinkComponent, ContentMediaComponent, IconComponent, TranslatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './content-story-viewer.component.html',
  styleUrl: './content-story-viewer.component.scss',
})
export class ContentStoryViewerComponent {
  private readonly events = inject(ContentEventsService);
  private readonly reducedMotion = inject(ReducedMotionService);
  private readonly document = inject(DOCUMENT);

  readonly story = input.required<ContentStoryGroup>();
  /** The viewer is done: closed, or the story ended. */
  readonly closed = output<void>();

  protected readonly index = signal(0);
  /** Bumped by every move, so the slide's own time starts over even when the index does not change. */
  private readonly moves = signal(0);
  private readonly finished = signal(false);
  private readonly dialog = viewChild.required<ElementRef<HTMLElement>>('dialog');
  private readonly closeButton = viewChild<ElementRef<HTMLButtonElement>>('closeButton');
  private readonly restoreTo = this.document.activeElement as HTMLElement | null;

  protected readonly slide = computed(() => {
    const slides = this.story().slides;
    // A story republished with fewer slides while it is open must not leave the index dangling.
    return slides[Math.min(this.index(), slides.length - 1)];
  });
  protected readonly plan = computed(() => {
    return mediaPlan(this.slide().media, this.reducedMotion.reduced());
  });
  protected readonly hasLink = computed(() => resolveContentLink(this.slide().link) !== null);
  protected readonly lang = computed(() => languageTag(this.story().textLocale));

  constructor() {
    const injector = inject(Injector);

    // The slide moves on by itself, restarting on every move. A video under reduced motion waits
    // for a tap.
    effect((onCleanup) => {
      this.index();
      this.moves();
      const slide = this.slide();
      if (this.finished()) {
        return;
      }
      if (slide.media.kind === 'VIDEO' && this.reducedMotion.reduced()) {
        return;
      }
      const timer = setTimeout(() => this.next(), slide.durationSeconds * 1000);
      onCleanup(() => clearTimeout(timer));
    });

    afterNextRender(() => this.closeButton()?.nativeElement.focus(), { injector });

    inject(DestroyRef).onDestroy(() => {
      if (this.restoreTo?.isConnected) {
        this.restoreTo.focus();
      }
    });
  }

  /** The next slide; past the last one the story is complete. */
  protected next(): void {
    if (this.index() < this.story().slides.length - 1) {
      this.go(this.index() + 1);
      return;
    }
    if (!this.finished()) {
      const { id, revisionNo } = this.story();
      this.events.report(id, revisionNo, 'COMPLETION');
    }
    this.close();
  }

  protected previous(): void {
    this.go(Math.max(0, this.index() - 1));
  }

  protected close(): void {
    if (this.finished()) {
      return;
    }
    this.finished.set(true);
    this.closed.emit();
  }

  /** A slide whose picture cannot be loaded is skipped; the last one ends the story unwatched. */
  protected mediaFailed(): void {
    if (this.index() < this.story().slides.length - 1) {
      this.go(this.index() + 1);
    } else {
      this.close();
    }
  }

  protected followedLink(): void {
    const { id, revisionNo } = this.story();
    this.events.report(id, revisionNo, 'CLICK');
    this.close();
  }

  protected onKeydown(event: KeyboardEvent): void {
    switch (event.key) {
      case 'ArrowRight':
        event.preventDefault();
        this.next();
        break;
      case 'ArrowLeft':
        event.preventDefault();
        this.previous();
        break;
      case 'Escape':
        event.preventDefault();
        this.close();
        break;
      default:
        trapTab(event, this.dialog().nativeElement);
    }
  }

  private go(index: number): void {
    this.index.set(index);
    this.moves.update((count) => count + 1);
  }
}
