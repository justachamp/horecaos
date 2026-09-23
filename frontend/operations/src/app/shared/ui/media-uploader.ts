import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  booleanAttribute,
  computed,
  inject,
  input,
  numberAttribute,
  output,
  signal,
} from '@angular/core';

import { MessageKey } from '../../core/i18n/messages.en';
import { TPipe } from '../../core/i18n/t.pipe';

/**
 * Maps a {@link MediaUploader.rejected} reason to the sentence a caller
 * should show — one place for it, since every call site otherwise
 * duplicates the same three-way branch (`brand-profile-page.ts`'s own
 * `onMediaRejected` was the first to need a third arm once video gained its
 * own reason, gap-map row `X.12`).
 */
export const MEDIA_UPLOADER_REJECTION_MESSAGE_KEYS: Readonly<Record<string, MessageKey>> = {
  tooLarge: 'ui.mediaUploader.tooLarge',
  videoNotSupported: 'ui.mediaUploader.videoNotSupported',
};

/** Falls back to the generic "unsupported type" sentence for any reason not named above. */
export function mediaUploaderRejectionMessageKey(reason: string): MessageKey {
  return MEDIA_UPLOADER_REJECTION_MESSAGE_KEYS[reason] ?? 'ui.mediaUploader.unsupportedType';
}

/** `w:h`, e.g. `'1:1'`, `'3:2'`, `'3:1'`, `'9:16'`. */
export type AspectRatio = string;

const DEFAULT_RATIOS: readonly AspectRatio[] = ['1:1', '3:2', '3:1', '9:16'];
const DEFAULT_MAX_BYTES = 10 * 1024 * 1024;
const EXPORT_LONG_EDGE_PX = 960;
const JPEG_QUALITY = 0.9;
const MIN_ZOOM = 1;
const MAX_ZOOM = 3;

/**
 * Media upload with aspect-ratio crop and video pass-through (ADR 0101, row
 * `X.12`).
 *
 * The gap this closes: catalog product photos were the only media path with
 * any UI at all, and even there an operator could upload a photo and never
 * see it again — no preview, no crop, no way to fit an aggregator's required
 * ratio. This is the base primitive IA 4.2f, 10.5's banners, the kiosk idle
 * screen, story slides and the review-tag icon all need; this wave (`P22`)
 * builds it against the product-photo call site only.
 *
 * **Images** get a center-anchored zoom-and-pan crop against a chosen ratio,
 * exported as a re-encoded JPEG at up to {@link EXPORT_LONG_EDGE_PX} on the
 * long edge — small enough for `MediaController`'s 10MB cap regardless of the
 * original, and JPEG because it is the one format every derivative renderer
 * and every ADR 0010 upload path already accepts.
 *
 * **Video** is refused client-side (gap-map row `X.12`), never sent to
 * {@link selected}: `MediaAssetService`'s upload-request allowlist is images
 * only, and a build that let a video reach an upload call would surface the
 * server's own refusal as a confusing failure well after the operator picked
 * the file, rather than the clear, immediate reason {@link rejected} gives
 * here. Still {@link accept}ed by the file picker (the input's own `accept`
 * attribute still lists `video/mp4`/`video/webm`) so a video is at least
 * selectable and this component's own refusal — not a silent absence from an
 * OS file dialog — is what an operator sees. Widening this to actually accept
 * video needs a verification and processing path this component does not
 * have yet; see this row's own gap-map note.
 *
 * Fully controlled like every primitive in this directory: this component
 * never calls an upload API itself. A caller listens for {@link cropped} (an
 * image, already cropped and re-encoded) or {@link selected} (anything else,
 * unmodified) and drives its own upload, reflecting progress back through
 * {@link uploading}/{@link progress}.
 */
@Component({
  selector: 'q-media-uploader',
  imports: [TPipe],
  templateUrl: './media-uploader.html',
  styleUrl: './media-uploader.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class MediaUploader {
  private readonly destroyRef = inject(DestroyRef);

  readonly aspectRatios = input<readonly AspectRatio[]>(DEFAULT_RATIOS);
  readonly accept = input<string>('image/jpeg,image/png,image/webp,video/mp4,video/webm');
  readonly maxSizeBytes = input(DEFAULT_MAX_BYTES, { transform: numberAttribute });
  readonly uploading = input(false, { transform: booleanAttribute });
  /** 0-100, or null for an indeterminate bar while {@link uploading} is true. */
  readonly progress = input<number | null>(null);

  /** An image, cropped to the chosen ratio and re-encoded as JPEG. */
  readonly cropped = output<File>();
  /** A video, or anything else this component does not crop — unmodified. */
  readonly selected = output<File>();
  readonly rejected = output<string>();

  protected readonly dragOver = signal(false);
  protected readonly previewUrl = signal<string | null>(null);
  protected readonly previewIsVideo = signal(false);
  protected readonly pendingFile = signal<File | null>(null);
  protected readonly selectedRatio = signal<AspectRatio>(DEFAULT_RATIOS[0]);
  protected readonly zoom = signal(1);
  protected readonly panX = signal(0);
  protected readonly panY = signal(0);
  protected readonly cropping = signal(false);

  protected readonly aspectCss = computed(() => this.selectedRatio().replace(':', '/'));

  private naturalWidth = 0;
  private naturalHeight = 0;
  private dragOrigin: { x: number; y: number; panX: number; panY: number } | null = null;

  constructor() {
    this.destroyRef.onDestroy(() => this.revokePreview());
  }

  protected onDropZoneDragOver(event: DragEvent): void {
    event.preventDefault();
    this.dragOver.set(true);
  }

  protected onDropZoneDragLeave(): void {
    this.dragOver.set(false);
  }

  protected onDrop(event: DragEvent): void {
    event.preventDefault();
    this.dragOver.set(false);
    const file = event.dataTransfer?.files?.[0];
    if (file) {
      this.handleFile(file);
    }
  }

  protected onFileInput(files: FileList | null): void {
    const file = files?.[0];
    if (file) {
      this.handleFile(file);
    }
  }

  private handleFile(file: File): void {
    if (file.size > this.maxSizeBytes()) {
      this.rejected.emit('tooLarge');
      return;
    }
    const accepted = this.accept()
      .split(',')
      .map((entry) => entry.trim());
    if (accepted.length > 0 && !accepted.includes(file.type)) {
      this.rejected.emit('unsupportedType');
      return;
    }

    if (file.type.startsWith('video/')) {
      // Gap-map row X.12: MediaAssetService's own pipeline is image-only
      // today. Refused here, with a reason a caller can render as a clear
      // sentence, rather than let a video reach an upload call the server
      // was always going to refuse for a reason this screen never explains.
      this.rejected.emit('videoNotSupported');
      return;
    }

    if (!file.type.startsWith('image/')) {
      // Some other non-image, non-video type the accept list still allowed
      // through (a caller can widen accept() beyond the default) — no
      // client-side crop for it, pass it straight through as before.
      this.selected.emit(file);
      return;
    }

    this.revokePreview();
    this.pendingFile.set(file);
    this.previewIsVideo.set(false);
    this.previewUrl.set(URL.createObjectURL(file));
    this.zoom.set(1);
    this.panX.set(0);
    this.panY.set(0);
    this.cropping.set(true);
  }

  protected onPreviewLoad(image: HTMLImageElement): void {
    this.naturalWidth = image.naturalWidth;
    this.naturalHeight = image.naturalHeight;
  }

  protected selectRatio(ratio: AspectRatio): void {
    this.selectedRatio.set(ratio);
    this.panX.set(0);
    this.panY.set(0);
  }

  protected onZoomInput(value: string): void {
    const parsed = Number.parseFloat(value);
    if (Number.isFinite(parsed)) {
      this.zoom.set(Math.min(MAX_ZOOM, Math.max(MIN_ZOOM, parsed)));
    }
  }

  protected onPanPointerDown(event: PointerEvent): void {
    (event.currentTarget as HTMLElement).setPointerCapture(event.pointerId);
    this.dragOrigin = { x: event.clientX, y: event.clientY, panX: this.panX(), panY: this.panY() };
  }

  protected onPanPointerMove(event: PointerEvent, box: HTMLElement): void {
    const origin = this.dragOrigin;
    if (!origin) {
      return;
    }
    const rect = box.getBoundingClientRect();
    const geometry = this.geometryFor(rect.width, rect.height);
    const dx = event.clientX - origin.x;
    const dy = event.clientY - origin.y;
    this.panX.set(clamp(origin.panX + dx, geometry.minPanX, 0));
    this.panY.set(clamp(origin.panY + dy, geometry.minPanY, 0));
  }

  protected onPanPointerUp(event: PointerEvent): void {
    (event.currentTarget as HTMLElement).releasePointerCapture(event.pointerId);
    this.dragOrigin = null;
  }

  /**
   * How the natural image maps onto the crop box at the current zoom: the
   * scale that makes the image cover the box (object-fit: cover) times the
   * zoom factor, and the furthest the image may pan before its edge would
   * show inside the box.
   */
  private geometryFor(boxWidthPx: number, boxHeightPx: number) {
    const coverScale =
      this.naturalWidth > 0 && this.naturalHeight > 0
        ? Math.max(boxWidthPx / this.naturalWidth, boxHeightPx / this.naturalHeight)
        : 1;
    const scale = coverScale * this.zoom();
    const displayedWidth = this.naturalWidth * scale;
    const displayedHeight = this.naturalHeight * scale;
    return {
      scale,
      displayedWidth,
      displayedHeight,
      minPanX: Math.min(0, boxWidthPx - displayedWidth),
      minPanY: Math.min(0, boxHeightPx - displayedHeight),
    };
  }

  protected imageTransform(boxWidthPx: number, boxHeightPx: number): string {
    const geometry = this.geometryFor(boxWidthPx, boxHeightPx);
    const centeredX = (boxWidthPx - geometry.displayedWidth) / 2;
    const centeredY = (boxHeightPx - geometry.displayedHeight) / 2;
    // Pan is clamped against the corner origin, not the centered one; once
    // panned, centering no longer applies to that axis.
    const x = this.panX() === 0 && this.zoom() === 1 ? centeredX : this.panX();
    const y = this.panY() === 0 && this.zoom() === 1 ? centeredY : this.panY();
    return `translate(${x}px, ${y}px) scale(${geometry.scale})`;
  }

  protected cancelCrop(): void {
    this.revokePreview();
    this.pendingFile.set(null);
    this.cropping.set(false);
  }

  protected async confirmCrop(box: HTMLElement): Promise<void> {
    const file = this.pendingFile();
    const url = this.previewUrl();
    if (!file || !url || this.naturalWidth === 0) {
      return;
    }
    const rect = box.getBoundingClientRect();
    const geometry = this.geometryFor(rect.width, rect.height);
    const centeredX = (rect.width - geometry.displayedWidth) / 2;
    const centeredY = (rect.height - geometry.displayedHeight) / 2;
    const panX = this.panX() === 0 && this.zoom() === 1 ? centeredX : this.panX();
    const panY = this.panY() === 0 && this.zoom() === 1 ? centeredY : this.panY();

    // The visible box, mapped back into the natural image's own pixels.
    const srcX = -panX / geometry.scale;
    const srcY = -panY / geometry.scale;
    const srcW = rect.width / geometry.scale;
    const srcH = rect.height / geometry.scale;

    const [wRatio, hRatio] = this.selectedRatio().split(':').map(Number);
    const exportWidth =
      wRatio >= hRatio ? EXPORT_LONG_EDGE_PX : Math.round((EXPORT_LONG_EDGE_PX * wRatio) / hRatio);
    const exportHeight =
      wRatio >= hRatio ? Math.round((EXPORT_LONG_EDGE_PX * hRatio) / wRatio) : EXPORT_LONG_EDGE_PX;

    const image = new Image();
    image.src = url;
    await image.decode();

    const canvas = document.createElement('canvas');
    canvas.width = exportWidth;
    canvas.height = exportHeight;
    const ctx = canvas.getContext('2d');
    if (!ctx) {
      return;
    }
    ctx.drawImage(image, srcX, srcY, srcW, srcH, 0, 0, exportWidth, exportHeight);

    const blob = await new Promise<Blob | null>((resolve) =>
      canvas.toBlob(resolve, 'image/jpeg', JPEG_QUALITY),
    );
    if (!blob) {
      return;
    }
    const croppedFile = new File([blob], renamedForCrop(file.name), { type: 'image/jpeg' });
    this.revokePreview();
    this.pendingFile.set(null);
    this.cropping.set(false);
    this.cropped.emit(croppedFile);
  }

  private revokePreview(): void {
    const url = this.previewUrl();
    if (url) {
      URL.revokeObjectURL(url);
    }
    this.previewUrl.set(null);
  }
}

function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value));
}

function renamedForCrop(original: string): string {
  const withoutExtension = original.replace(/\.[^./\\]+$/, '');
  return `${withoutExtension || 'photo'}.jpg`;
}
