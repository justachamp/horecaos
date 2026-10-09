import {
  ChangeDetectionStrategy,
  Component,
  booleanAttribute,
  computed,
  effect,
  inject,
  input,
  numberAttribute,
  output,
  signal,
} from '@angular/core';

import { ApiError } from '../../../core/api/problem-details';
import { CurrentBrand } from '../../../core/auth/current-brand';
import { I18n } from '../../../core/i18n/i18n';
import { TPipe } from '../../../core/i18n/t.pipe';
import {
  MediaUploader,
  VideoSelection,
  mediaUploaderRejectionMessageKey,
} from '../../../shared/ui/media-uploader';
import { describeApiError } from '../../orders/order-errors';
import { ContentMedia, MediaNotReadyError, MediaRefusedError } from './content-media';

/** What the field reports: the asset (and, for a video, its poster) the document should now name. */
export interface MediaChange {
  readonly assetId: string | null;
  readonly posterAssetId: string | null;
}

/**
 * One picture (or one video) of a content item: its thumbnail, a way to replace or remove it, and
 * the uploader (ADR 0157, Decision 8 and Decision 11).
 *
 * The uploader crops to the placement's ratio and exports at the placement's own edge, then
 * {@link ContentMedia} uploads it public, as the brand's, with the creative rendition profile -- so
 * the storefront can serve it. A video is offered only when {@link allowVideo} is on (the plan's
 * `content.video.enabled`); it is checked against the platform's caps before any request, uploaded
 * with the poster frame the operator chose, and, if the platform refuses it after reading it, the
 * sentence says how to export a compliant file (the media API names no code to say which rule).
 */
@Component({
  selector: 'q-content-media-field',
  imports: [MediaUploader, TPipe],
  templateUrl: './content-media-field.html',
  styleUrl: './content-media-field.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ContentMediaField {
  private readonly brand = inject(CurrentBrand);
  private readonly media = inject(ContentMedia);
  private readonly i18n = inject(I18n);

  readonly assetId = input<string | null>(null);
  /** Set for a video: the image customers see before it plays. */
  readonly posterId = input<string | null>(null);
  /** The crop ratio of the placement, `w:h`. */
  readonly ratio = input('3:2');
  readonly exportEdgePx = input(960, { transform: numberAttribute });
  /** The least width, in pixels, the placement accepts (a publication gate); shown as a hint. */
  readonly minWidthPx = input(0, { transform: numberAttribute });
  readonly allowVideo = input(false, { transform: booleanAttribute });
  readonly disabled = input(false, { transform: booleanAttribute });
  readonly label = input<string | null>(null);

  readonly changed = output<MediaChange>();

  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);

  protected readonly ratios = computed(() => [this.ratio()]);
  protected readonly accept = computed(
    () => 'image/jpeg,image/png,image/webp' + (this.allowVideo() ? ',video/mp4' : ''),
  );
  protected readonly isVideo = computed(() => this.posterId() !== null);
  /** The picture to draw: a video's poster, otherwise the asset itself. */
  protected readonly thumbnail = computed(() => {
    const source = this.posterId() ?? this.assetId();
    return source === null ? null : (this.media.thumbnails()[source] ?? null);
  });

  constructor() {
    effect(() => {
      const scope = this.brand.scope();
      const source = this.posterId() ?? this.assetId();
      if (scope !== null && source !== null) {
        this.media.ensureThumbnail(scope, source);
      }
    });
  }

  protected remove(): void {
    this.error.set(null);
    this.changed.emit({ assetId: null, posterAssetId: null });
  }

  protected onRejected(reason: string): void {
    this.error.set(this.i18n.t(mediaUploaderRejectionMessageKey(reason)));
  }

  protected async onCropped(file: File): Promise<void> {
    await this.run(async (scope) => {
      const assetId = await this.media.uploadImage(scope, file);
      this.changed.emit({ assetId, posterAssetId: null });
    });
  }

  protected async onVideo(selection: VideoSelection): Promise<void> {
    await this.run(async (scope) => {
      const uploaded = await this.media.uploadVideo(scope, selection.video, selection.poster);
      this.changed.emit({ assetId: uploaded.assetId, posterAssetId: uploaded.posterAssetId });
    });
  }

  private async run(
    upload: (scope: NonNullable<ReturnType<CurrentBrand['scope']>>) => Promise<void>,
  ): Promise<void> {
    const scope = this.brand.scope();
    if (scope === null || this.busy()) {
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    try {
      await upload(scope);
    } catch (failure) {
      this.error.set(this.describe(failure));
    } finally {
      this.busy.set(false);
    }
  }

  private describe(failure: unknown): string {
    if (failure instanceof MediaRefusedError) {
      return this.i18n.t(
        failure.kind === 'VIDEO'
          ? 'ui.mediaUploader.videoRejectedByServer'
          : 'marketing.content.media.imageRefused',
      );
    }
    if (failure instanceof MediaNotReadyError) {
      return this.i18n.t('marketing.content.media.notReady');
    }
    if (failure instanceof ApiError) {
      return describeApiError(failure, (key, values) => this.i18n.t(key, values));
    }
    return this.i18n.t('marketing.content.media.failed');
  }
}
