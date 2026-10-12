import {
  ChangeDetectionStrategy,
  Component,
  booleanAttribute,
  computed,
  inject,
  input,
  output,
  signal,
} from '@angular/core';

import { I18n } from '../../../core/i18n/i18n';
import { TPipe } from '../../../core/i18n/t.pipe';
import { NumberStepper } from '../../../shared/ui/number-stepper';
import { RuleList, RuleListItem, RuleReorder } from '../../../shared/ui/rule-list';
import { ContentDocument } from './content-api';
import {
  LinkValue,
  NEW_SLIDE,
  SlideValue,
  avatarAssetId,
  slidesOf,
  textOf,
  withAvatarAsset,
  withSlides,
  withText,
} from './content-document';
import { ContentLinkPicker } from './content-link-picker';
import { ContentMediaField, MediaChange } from './content-media-field';

/** A story group holds at most ten slides (the grammar's own limit). */
export const MAX_SLIDES = 10;

/**
 * A story group (ADR 0157, Decision 10): a round avatar and up to ten slides, each an image or a
 * short video, with a duration of 3 to 15 seconds, a caption per language and its own link.
 *
 * The slides are ordered on `q-rule-list` (the flat sortable list, switch off), and the chosen
 * slide is edited beside it. View counts are anonymous and reported by the visitor's own device; the
 * stats panel says so.
 */
@Component({
  selector: 'q-content-story-fields',
  imports: [ContentLinkPicker, ContentMediaField, NumberStepper, RuleList, TPipe],
  templateUrl: './content-story-fields.html',
  styleUrl: './content-story-fields.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ContentStoryFields {
  private readonly i18n = inject(I18n);

  readonly document = input.required<ContentDocument>();
  readonly locale = input.required<string>();
  readonly allowVideo = input(false, { transform: booleanAttribute });
  readonly disabled = input(false, { transform: booleanAttribute });

  readonly documentChange = output<ContentDocument>();

  protected readonly selected = signal(0);
  protected readonly maxSlides = MAX_SLIDES;

  protected readonly title = computed(() => textOf(this.document(), this.locale(), 'title'));
  protected readonly avatar = computed(() => avatarAssetId(this.document()));
  protected readonly slides = computed(() => slidesOf(this.document()));
  /** The slide being edited, clamped so a removal never leaves the selection past the end. */
  protected readonly current = computed(() => {
    const slides = this.slides();
    return slides.length === 0 ? null : Math.min(this.selected(), slides.length - 1);
  });
  protected readonly slide = computed(() => {
    const index = this.current();
    return index === null ? null : this.slides()[index];
  });
  protected readonly caption = computed(() => this.slide()?.captions[this.locale()] ?? '');

  protected readonly items = computed<readonly RuleListItem[]>(() => {
    this.i18n.locale();
    return this.slides().map((slide, index) => ({
      id: String(index),
      label: this.i18n.t('marketing.content.story.slideLabel', { n: index + 1 }),
      description: slide.captions[this.locale()] ?? null,
      enabled: true,
    }));
  });

  protected setTitle(value: string): void {
    this.documentChange.emit(withText(this.document(), this.locale(), 'title', value));
  }

  protected setAvatar(change: MediaChange): void {
    this.documentChange.emit(withAvatarAsset(this.document(), change.assetId));
  }

  protected select(id: string): void {
    this.selected.set(Number(id));
  }

  protected addSlide(): void {
    if (this.slides().length >= MAX_SLIDES) {
      return;
    }
    this.selected.set(this.slides().length);
    this.write([...this.slides(), NEW_SLIDE]);
  }

  protected removeSlide(): void {
    const index = this.current();
    if (index === null) {
      return;
    }
    this.write(this.slides().filter((_, i) => i !== index));
  }

  protected reorder(order: RuleReorder): void {
    const slides = this.slides();
    const chosen = this.current();
    const next = order.map((id) => slides[Number(id)]);
    this.selected.set(chosen === null ? 0 : order.indexOf(String(chosen)));
    this.write(next);
  }

  protected setMedia(change: MediaChange): void {
    this.patch({
      assetId: change.assetId,
      posterAssetId: change.posterAssetId,
      kind: change.posterAssetId !== null ? 'VIDEO' : 'IMAGE',
    });
  }

  protected setDuration(seconds: number): void {
    this.patch({ durationSeconds: seconds });
  }

  protected setCaption(value: string): void {
    const slide = this.slide();
    if (slide === null) {
      return;
    }
    const captions = { ...slide.captions };
    if (value === '') {
      delete captions[this.locale()];
    } else {
      captions[this.locale()] = value;
    }
    this.patch({ captions });
  }

  protected setLink(link: LinkValue): void {
    this.patch({ link });
  }

  private patch(change: Partial<SlideValue>): void {
    const index = this.current();
    if (index === null) {
      return;
    }
    this.write(this.slides().map((slide, i) => (i === index ? { ...slide, ...change } : slide)));
  }

  private write(slides: readonly SlideValue[]): void {
    this.documentChange.emit(withSlides(this.document(), slides));
  }
}
