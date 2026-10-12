import { ChangeDetectionStrategy, Component, inject, input, signal } from '@angular/core';

import { ContentEventsService } from '../../services/content-events.service';
import type {
  ContentCategoryRef,
  ContentMedia,
  ContentStoryGroup,
} from '../../types/content.types';
import { ContentImpressionDirective } from '../content-impression/content-impression.directive';
import { ContentMediaComponent } from '../content-media/content-media.component';
import { TranslatePipe } from '../translate/translate.pipe';
import { StoryViewerComponent } from './story-viewer.component';

/**
 * The stories rail (ADR 0157, Release 2): a row of round avatars under the top bar; tapping one
 * opens the full-screen player.
 *
 * Seeing an avatar is an impression and opening the story is a click; finishing it is reported by
 * the player. The player is behind `@defer`, so its code (and the slide logic in it) arrives only
 * when a home screen has stories and one is opened.
 *
 * An avatar that cannot load shows the first letter of the story's title instead, since the story
 * itself is still worth opening.
 */
@Component({
  selector: 'app-content-stories',
  standalone: true,
  imports: [ContentImpressionDirective, ContentMediaComponent, StoryViewerComponent, TranslatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'contents' },
  template: `
    @if (stories().length > 0) {
      <section [attr.aria-label]="'content.stories' | t">
        <ul class="no-scrollbar -mx-4 flex gap-3 overflow-x-auto px-4 py-1">
          @for (story of stories(); track story.id + '#' + story.revisionNo) {
            <li class="w-[68px] shrink-0">
              <button
                type="button"
                class="flex w-full flex-col items-center gap-1"
                [attr.aria-label]="'content.storyOpen' | t: { title: story.title }"
                [appContentImpression]="{ id: story.id, revisionNo: story.revisionNo }"
                (click)="open(story, $event)"
              >
                <span class="block h-16 w-16 rounded-full bg-brand-accent p-[3px]">
                  <span
                    class="block h-full w-full overflow-hidden rounded-full border-2 border-surface-base bg-surface-raised"
                  >
                    @if (!avatarFailed().has(story.id + '#' + story.revisionNo)) {
                      <app-content-media
                        [media]="avatarOf(story)"
                        [posterOnly]="true"
                        sizes="64px"
                        (failed)="markAvatarFailed(story)"
                      />
                    } @else {
                      <span
                        data-testid="story-initial"
                        class="flex h-full w-full items-center justify-center text-lg font-semibold"
                        >{{ initialOf(story) }}</span
                      >
                    }
                  </span>
                </span>
                <span
                  class="w-full truncate text-center text-[11px] text-text-primary"
                  [attr.lang]="story.textLocale || null"
                  >{{ story.title }}</span
                >
              </button>
            </li>
          }
        </ul>
      </section>
    }

    @defer (when active() !== null) {
      @if (active(); as story) {
        <app-story-viewer [story]="story" [categories]="categories()" (closed)="close()" />
      }
    }
  `,
})
export class ContentStoriesComponent {
  private readonly events = inject(ContentEventsService);

  readonly stories = input<readonly ContentStoryGroup[]>([]);
  readonly categories = input<readonly ContentCategoryRef[]>([]);

  protected readonly active = signal<ContentStoryGroup | null>(null);
  protected readonly avatarFailed = signal<ReadonlySet<string>>(new Set());
  private opener: HTMLElement | null = null;

  protected avatarOf(story: ContentStoryGroup): ContentMedia {
    return story.avatar ?? story.slides[0].media;
  }

  protected initialOf(story: ContentStoryGroup): string {
    return Array.from(story.title.trim())[0]?.toUpperCase() ?? '';
  }

  protected markAvatarFailed(story: ContentStoryGroup): void {
    this.avatarFailed.update((set) => new Set(set).add(`${story.id}#${story.revisionNo}`));
  }

  protected open(story: ContentStoryGroup, event: Event): void {
    this.opener = event.currentTarget instanceof HTMLElement ? event.currentTarget : null;
    this.events.click(story.id, story.revisionNo);
    this.active.set(story);
  }

  protected close(): void {
    this.active.set(null);
    // Safari does not focus a button on tap, so the player cannot always remember it; do it here.
    this.opener?.focus();
  }
}
