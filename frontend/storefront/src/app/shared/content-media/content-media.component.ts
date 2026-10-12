import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  output,
  untracked,
} from '@angular/core';

import { ReducedMotionService } from '../../services/reduced-motion.service';
import type { ContentImageMedia, ContentMedia, ContentVideoMedia } from '../../types/content.types';

/**
 * One piece of content media: an image from its renditions, or a video.
 *
 * <h2>Images</h2>
 * `srcset` is built from the renditions the server says exist, each with its width descriptor, and
 * the plain `src` is the largest of them, for a browser that ignores `srcset`. The original is
 * never offered: it can be ten megabytes, and the read model does not even carry its URL (ADR 0157,
 * Storefront API). `sizes` is the caller's hint for how wide the box is on screen.
 *
 * <h2>Video</h2>
 * Muted, inline, metadata only, with the poster, and looping unless the caller says otherwise. When
 * the visitor prefers reduced motion (or the caller asks for `posterOnly`, as a tiny avatar does)
 * there is no `<video>` at all, just the poster as an image. A video with no poster in that case
 * has nothing to show, which is reported the same way as a media file that fails to load:
 * {@link failed}, so the owner of the box can take it away rather than leave a hole.
 */
@Component({
  selector: 'app-content-media',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'block h-full w-full' },
  template: `
    @if (image(); as img) {
      <img
        class="h-full w-full object-cover"
        [attr.src]="fallbackSrc()"
        [attr.srcset]="srcset()"
        [attr.sizes]="sizes()"
        [attr.alt]="img.alt"
        [attr.width]="img.widthPx ?? null"
        [attr.height]="img.heightPx ?? null"
        [attr.loading]="eager() ? 'eager' : 'lazy'"
        decoding="async"
        (error)="failed.emit()"
      />
    } @else if (video(); as clip) {
      @if (playing()) {
        <video
          class="h-full w-full object-cover"
          muted
          [muted]="true"
          playsinline
          autoplay
          preload="metadata"
          [attr.loop]="loop() ? '' : null"
          [attr.poster]="clip.posterUrl ?? null"
          [attr.aria-label]="clip.alt"
          [src]="clip.url"
          (error)="failed.emit()"
        ></video>
      } @else if (clip.posterUrl) {
        <img
          class="h-full w-full object-cover"
          [attr.src]="clip.posterUrl"
          [attr.alt]="clip.alt"
          [attr.loading]="eager() ? 'eager' : 'lazy'"
          decoding="async"
          (error)="failed.emit()"
        />
      }
    }
  `,
})
export class ContentMediaComponent {
  private readonly motion = inject(ReducedMotionService);

  readonly media = input.required<ContentMedia>();
  /** True for the first image on the page; every other one is lazy. */
  readonly eager = input(false);
  /** The `sizes` attribute: how wide the box is at each viewport width. */
  readonly sizes = input('100vw');
  /** Never play, even without a motion preference: a poster in a tiny avatar, for instance. */
  readonly posterOnly = input(false);
  readonly loop = input(true);
  /** The file did not load, or there is nothing to show; the owner of the box should drop it. */
  readonly failed = output<void>();

  protected readonly image = computed<ContentImageMedia | null>(() => {
    const media = this.media();
    return media.kind === 'IMAGE' ? media : null;
  });
  protected readonly video = computed<ContentVideoMedia | null>(() => {
    const media = this.media();
    return media.kind === 'VIDEO' ? media : null;
  });
  protected readonly srcset = computed(() =>
    (this.image()?.renditions ?? []).map((r) => `${r.url} ${r.widthPx}w`).join(', '),
  );
  protected readonly fallbackSrc = computed(() => this.image()?.renditions.at(-1)?.url ?? null);
  protected readonly playing = computed(
    () => this.video() !== null && !this.posterOnly() && !this.motion.reduced(),
  );

  constructor() {
    effect(() => {
      const clip = this.video();
      if (clip && !this.playing() && !clip.posterUrl) {
        untracked(() => this.failed.emit());
      }
    });
  }
}
