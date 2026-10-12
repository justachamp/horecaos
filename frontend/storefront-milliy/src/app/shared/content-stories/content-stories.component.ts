import { ChangeDetectionStrategy, Component, computed, input, signal } from '@angular/core';

import type { ContentStoryGroup } from '../../types/content.types';
import { mediaPlan } from '../../utils/content-media';
import { initialOf, languageTag } from '../../utils/content-text';
import { ContentImpressionDirective } from '../content-impression/content-impression.directive';
import { ContentMediaComponent } from '../content-media/content-media.component';
import { ContentStoryViewerComponent } from '../content-story-viewer/content-story-viewer.component';
import { TranslatePipe } from '../translate/translate.pipe';

/**
 * The home stories rail (ADR 0157, Release 2): a row of round avatars, one per story group. Tapping
 * one opens the full-screen viewer on that group.
 *
 * The avatar is the group's own picture, else the first slide's; a video is never played in the
 * rail (only its poster is drawn), and a picture that cannot be loaded gives way to the first
 * letter of the title, so the story stays reachable.
 */
@Component({
  selector: 'app-content-stories',
  standalone: true,
  imports: [
    ContentImpressionDirective,
    ContentMediaComponent,
    ContentStoryViewerComponent,
    TranslatePipe,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './content-stories.component.html',
  styleUrl: './content-stories.component.scss',
})
export class ContentStoriesComponent {
  readonly stories = input.required<readonly ContentStoryGroup[]>();

  private readonly brokenAvatars = signal<ReadonlySet<string>>(new Set());
  private readonly openId = signal<string | null>(null);

  protected readonly rail = computed(() =>
    this.stories().map((story) => {
      const media = story.avatar ?? story.slides[0]?.media;
      return {
        story,
        // A rail never plays video: a poster, or nothing.
        plan: media && !this.brokenAvatars().has(story.id) ? mediaPlan(media, true) : null,
        alt: media?.alt ?? '',
        initial: initialOf(story.title),
        lang: languageTag(story.textLocale),
      };
    }),
  );

  /** The group in the viewer; null when none is open or the open one is no longer published. */
  protected readonly open = computed(
    () => this.stories().find((story) => story.id === this.openId()) ?? null,
  );

  protected show(story: ContentStoryGroup, button: HTMLElement): void {
    // Safari does not focus a button on tap; the viewer gives focus back to whatever had it.
    button.focus();
    this.openId.set(story.id);
  }

  protected close(): void {
    this.openId.set(null);
  }

  protected avatarFailed(id: string): void {
    this.brokenAvatars.update((ids) => new Set(ids).add(id));
  }
}
