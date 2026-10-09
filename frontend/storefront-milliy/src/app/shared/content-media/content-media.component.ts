import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';

import type { MediaPlan } from '../../utils/content-media';

/**
 * Draws a piece of content media from a {@link MediaPlan}.
 *
 * An image is an `<img>` with a `srcset` built from the server's renditions (never an original),
 * lazy unless it is the first on the page; a poster is the same `<img>`; a video is
 * `<video muted playsinline preload="metadata">` with its poster, autoplaying and looping because it
 * has no controls and would otherwise never move. Under `prefers-reduced-motion` the plan is a
 * poster, so no `<video>` exists to play. `failed` fires when the browser cannot load the file, so
 * the tile that holds it can go.
 */
@Component({
  selector: 'app-content-media',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { '[style.--media-aspect]': 'aspectRatio()' },
  styleUrl: './content-media.component.scss',
  template: `
    @if (image(); as picture) {
      <img
        class="media"
        [attr.srcset]="picture.srcset"
        [attr.sizes]="sizes()"
        [src]="picture.src"
        [alt]="alt()"
        [attr.loading]="eager() ? 'eager' : 'lazy'"
        decoding="async"
        (error)="failed.emit()"
      />
    } @else if (poster(); as still) {
      <img
        class="media"
        [src]="still.src"
        [alt]="alt()"
        [attr.loading]="eager() ? 'eager' : 'lazy'"
        decoding="async"
        (error)="failed.emit()"
      />
    } @else if (video(); as clip) {
      <video
        class="media"
        muted
        [muted]="true"
        autoplay
        loop
        playsinline
        preload="metadata"
        [attr.poster]="clip.poster"
        [src]="clip.src"
        [attr.aria-label]="alt() || null"
        (error)="failed.emit()"
      ></video>
    }
  `,
})
export class ContentMediaComponent {
  readonly plan = input.required<MediaPlan>();
  /** The brand's own description; empty marks the picture as decorative. */
  readonly alt = input('');
  /** True for the first picture on the page, which must not wait to be scrolled near. */
  readonly eager = input(false);
  /** The `sizes` attribute: how wide the picture is drawn, so the browser picks a rendition. */
  readonly sizes = input('100vw');
  /** A CSS `aspect-ratio` value, so the page does not jump when the picture arrives. */
  readonly aspectRatio = input<string | null>(null);
  readonly failed = output<void>();

  protected readonly image = computed(() => {
    const plan = this.plan();
    return plan.kind === 'image' ? plan : null;
  });
  protected readonly poster = computed(() => {
    const plan = this.plan();
    return plan.kind === 'poster' ? plan : null;
  });
  protected readonly video = computed(() => {
    const plan = this.plan();
    return plan.kind === 'video' ? plan : null;
  });
}
