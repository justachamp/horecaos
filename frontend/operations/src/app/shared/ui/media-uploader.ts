import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  InjectionToken,
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
  // Video mode (ADR 0157, Decision 8): the caps the server enforces, named before any upload.
  videoNotMp4: 'ui.mediaUploader.videoNotMp4',
  videoTooLarge: 'ui.mediaUploader.videoTooLarge',
  videoTooLong: 'ui.mediaUploader.videoTooLong',
  videoDimensions: 'ui.mediaUploader.videoDimensions',
  videoUnreadable: 'ui.mediaUploader.videoUnreadable',
  posterFailed: 'ui.mediaUploader.posterFailed',
};

/** Falls back to the generic "unsupported type" sentence for any reason not named above. */
export function mediaUploaderRejectionMessageKey(reason: string): MessageKey {
  return MEDIA_UPLOADER_REJECTION_MESSAGE_KEYS[reason] ?? 'ui.mediaUploader.unsupportedType';
}

/** `w:h`, e.g. `'1:1'`, `'3:2'`, `'3:1'`, `'9:16'`. */
export type AspectRatio = string;

const DEFAULT_RATIOS: readonly AspectRatio[] = ['1:1', '3:2', '3:1', '9:16'];
const DEFAULT_MAX_BYTES = 10 * 1024 * 1024;
/** The long edge a crop is exported at unless the caller names one ({@link MediaUploader.exportEdgePx}). */
const DEFAULT_EXPORT_LONG_EDGE_PX = 960;
const JPEG_QUALITY = 0.9;
const MIN_ZOOM = 1;
const MAX_ZOOM = 3;

/**
 * What a video must be for the platform to serve it (ADR 0157, Decision 8) -- the same figures
 * `VideoProbe` enforces on the server, checked here first so an operator hears "too long" before
 * a 12 MB upload and not after it. The server stays the authority: the codec (H.264) and the
 * `moov`-first layout can only be read from the bytes, so a file that passes here can still be
 * refused there, and the caller says so.
 */
export const VIDEO_LIMITS = {
  maxBytes: 12 * 1024 * 1024,
  maxDurationMs: 20_000,
  maxLongEdgePx: 1920,
  minShortEdgePx: 480,
} as const;

/** What the browser reports of a video once its header has loaded. */
export interface VideoMetadata {
  readonly durationMs: number;
  readonly widthPx: number;
  readonly heightPx: number;
}

/** A video the operator picked, with the poster frame they chose from it, ready to upload. */
export interface VideoSelection {
  readonly video: File;
  /** A JPEG cropped to the chosen ratio at the export edge: captured in the browser, so the server needs no frame extractor. */
  readonly poster: File;
  readonly metadata: VideoMetadata;
}

/**
 * The rejection reason for a video, or null when it is inside every cap. Pure, so the caps are
 * tested without a decoder.
 */
export function videoRejectionReason(
  file: { readonly type: string; readonly size: number },
  metadata: VideoMetadata | null,
): string | null {
  if (file.type !== 'video/mp4') {
    return 'videoNotMp4';
  }
  if (file.size > VIDEO_LIMITS.maxBytes) {
    return 'videoTooLarge';
  }
  if (metadata === null || metadata.widthPx <= 0 || metadata.heightPx <= 0) {
    return 'videoUnreadable';
  }
  if (metadata.durationMs > VIDEO_LIMITS.maxDurationMs) {
    return 'videoTooLong';
  }
  const longEdge = Math.max(metadata.widthPx, metadata.heightPx);
  const shortEdge = Math.min(metadata.widthPx, metadata.heightPx);
  if (longEdge > VIDEO_LIMITS.maxLongEdgePx || shortEdge < VIDEO_LIMITS.minShortEdgePx) {
    return 'videoDimensions';
  }
  return null;
}

/** The size a crop of `ratio` is exported at when its long edge is `longEdgePx`. */
export function exportSizeFor(
  ratio: AspectRatio,
  longEdgePx: number,
): { readonly width: number; readonly height: number } {
  const [wRatio, hRatio] = ratio.split(':').map(Number);
  return wRatio >= hRatio
    ? { width: longEdgePx, height: Math.round((longEdgePx * hRatio) / wRatio) }
    : { width: Math.round((longEdgePx * wRatio) / hRatio), height: longEdgePx };
}

/**
 * The browser-facing half of video mode, behind a token so a spec can stand in for a decoder:
 * reading a header and drawing a frame both need a real media pipeline that jsdom does not have.
 */
export interface MediaUploaderVideo {
  /** Loads the file's header. Rejects (or resolves null) when the browser cannot decode it. */
  probe(file: File): Promise<VideoMetadata | null>;
  /** Draws the video's current frame, covered into `ratio` at `longEdgePx`, as a JPEG; null when it cannot. */
  capture(video: HTMLVideoElement, ratio: AspectRatio, longEdgePx: number): Promise<Blob | null>;
}

export const MEDIA_UPLOADER_VIDEO = new InjectionToken<MediaUploaderVideo>('MEDIA_UPLOADER_VIDEO', {
  providedIn: 'root',
  factory: () => ({
    probe: probeVideoInBrowser,
    capture: captureVideoFrame,
  }),
});

async function probeVideoInBrowser(file: File): Promise<VideoMetadata | null> {
  const url = URL.createObjectURL(file);
  try {
    const video = document.createElement('video');
    video.preload = 'metadata';
    video.muted = true;
    const loaded = new Promise<VideoMetadata | null>((resolve) => {
      video.onloadedmetadata = () =>
        resolve({
          durationMs: Math.round(video.duration * 1000),
          widthPx: video.videoWidth,
          heightPx: video.videoHeight,
        });
      video.onerror = () => resolve(null);
      // A codec the browser cannot decode may never fire either event.
      setTimeout(() => resolve(null), 8000);
    });
    video.src = url;
    return await loaded;
  } finally {
    URL.revokeObjectURL(url);
  }
}

async function captureVideoFrame(
  video: HTMLVideoElement,
  ratio: AspectRatio,
  longEdgePx: number,
): Promise<Blob | null> {
  if (video.videoWidth === 0 || video.videoHeight === 0) {
    return null;
  }
  const { width, height } = exportSizeFor(ratio, longEdgePx);
  const canvas = document.createElement('canvas');
  canvas.width = width;
  canvas.height = height;
  const ctx = canvas.getContext('2d');
  if (!ctx) {
    return null;
  }
  // object-fit: cover, centred -- the same framing the crop box shows an image.
  const scale = Math.max(width / video.videoWidth, height / video.videoHeight);
  const srcW = width / scale;
  const srcH = height / scale;
  ctx.drawImage(
    video,
    (video.videoWidth - srcW) / 2,
    (video.videoHeight - srcH) / 2,
    srcW,
    srcH,
    0,
    0,
    width,
    height,
  );
  return new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, 'image/jpeg', JPEG_QUALITY));
}

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
 * exported as a re-encoded JPEG at up to {@link exportEdgePx} on the
 * long edge — small enough for `MediaController`'s 10MB cap regardless of the
 * original, and JPEG because it is the one format every derivative renderer
 * and every ADR 0010 upload path already accepts.
 *
 * **Video** is refused client-side unless the caller turns {@link allowVideo} on (ADR 0157, Decision 8):
 * the server's pipeline is image-only for every other caller, and a video that reached an upload
 * call would surface the server's own refusal well after the operator picked the file. With video
 * on, an MP4 is checked against the platform's caps before any request ({@link VIDEO_LIMITS}: 12 MiB,
 * 20 s, long edge 1920, short edge 480), then the operator scrubs to the frame that will be its
 * poster and the component hands back {@link VideoSelection} -- the video untouched and a poster
 * JPEG cropped in the operator's own browser to the chosen ratio, so the server needs no frame
 * extractor. The codec (H.264) and the `moov`-first layout can only be read from the bytes, so they
 * stay the server's to refuse; the caller says so.
 *
 *  * Fully controlled like every primitive in this directory: this component
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
  private readonly video = inject(MEDIA_UPLOADER_VIDEO);

  readonly aspectRatios = input<readonly AspectRatio[]>(DEFAULT_RATIOS);
  readonly accept = input<string>('image/jpeg,image/png,image/webp,video/mp4,video/webm');
  readonly maxSizeBytes = input(DEFAULT_MAX_BYTES, { transform: numberAttribute });
  readonly uploading = input(false, { transform: booleanAttribute });
  /** 0-100, or null for an indeterminate bar while {@link uploading} is true. */
  readonly progress = input<number | null>(null);
  /**
   * The long edge, in pixels, a crop is exported at (ADR 0157, Decision 11). 960 unless the caller
   * says otherwise: a 3:1 home banner is exported at 1600 so the `HERO` rendition has something to
   * render from, while a product photo keeps the small export that fits the 10 MB cap whatever the
   * original.
   */
  readonly exportEdgePx = input(DEFAULT_EXPORT_LONG_EDGE_PX, { transform: numberAttribute });
  /**
   * Whether an MP4 is taken (with a poster frame) instead of refused. Off by default: only the
   * content editor's upload has a server path for video.
   */
  readonly allowVideo = input(false, { transform: booleanAttribute });

  /** An image, cropped to the chosen ratio and re-encoded as JPEG. */
  readonly cropped = output<File>();
  /** A video, or anything else this component does not crop — unmodified. */
  readonly selected = output<File>();
  /** {@link allowVideo} only: an MP4 inside the caps, with the poster frame the operator chose. */
  readonly videoReady = output<VideoSelection>();
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
  /** Choosing the poster frame of a video that passed {@link videoRejectionReason}. */
  protected readonly posterStep = signal(false);
  protected readonly videoDurationSeconds = signal(0);
  protected readonly posterSecond = signal(0);
  protected readonly capturing = signal(false);
  private pendingMetadata: VideoMetadata | null = null;

  protected readonly aspectCss = computed(() => this.selectedRatio().replace(':', '/'));
  /** What a confirmed crop (and a poster frame) is exported at: the chosen ratio on the caller's export edge. */
  protected readonly exportDimensions = computed(() =>
    exportSizeFor(this.selectedRatio(), this.exportEdgePx()),
  );

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
    const isVideo = file.type.startsWith('video/');
    // A video taken in video mode answers to the platform's own caps (VIDEO_LIMITS), not the
    // image cap: the two differ, and the sentence for each names its own limit.
    if (!(isVideo && this.allowVideo()) && file.size > this.maxSizeBytes()) {
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

    if (isVideo) {
      if (this.allowVideo()) {
        void this.beginVideo(file);
        return;
      }
      // Gap-map row X.12: MediaAssetService's own pipeline is image-only for every caller that
      // has not asked for video. Refused here, with a reason a caller can render as a clear
      // sentence, rather than let a video reach an upload call the server was always going to
      // refuse for a reason this screen never explains.
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

  private async beginVideo(file: File): Promise<void> {
    // Cheap refusals first, before the browser is asked to read anything.
    const early = videoRejectionReason(file, { durationMs: 1, widthPx: 1920, heightPx: 1080 });
    if (early === 'videoNotMp4' || early === 'videoTooLarge') {
      this.rejected.emit(early);
      return;
    }
    const metadata = await this.video.probe(file).catch(() => null);
    const reason = videoRejectionReason(file, metadata);
    if (reason !== null || metadata === null) {
      this.rejected.emit(reason ?? 'videoUnreadable');
      return;
    }
    this.revokePreview();
    this.pendingFile.set(file);
    this.pendingMetadata = metadata;
    this.previewIsVideo.set(true);
    this.previewUrl.set(URL.createObjectURL(file));
    this.videoDurationSeconds.set(metadata.durationMs / 1000);
    this.posterSecond.set(0);
    this.posterStep.set(true);
  }

  protected onPosterScrub(value: string, video: HTMLVideoElement): void {
    const parsed = Number.parseFloat(value);
    if (!Number.isFinite(parsed)) {
      return;
    }
    const second = Math.min(this.videoDurationSeconds(), Math.max(0, parsed));
    this.posterSecond.set(second);
    video.currentTime = second;
  }

  protected cancelPoster(): void {
    this.revokePreview();
    this.pendingFile.set(null);
    this.pendingMetadata = null;
    this.previewIsVideo.set(false);
    this.posterStep.set(false);
  }

  protected async confirmPoster(video: HTMLVideoElement): Promise<void> {
    const file = this.pendingFile();
    const metadata = this.pendingMetadata;
    if (!file || !metadata || this.capturing()) {
      return;
    }
    this.capturing.set(true);
    try {
      const blob = await this.video
        .capture(video, this.selectedRatio(), this.exportEdgePx())
        .catch(() => null);
      if (!blob) {
        this.rejected.emit('posterFailed');
        return;
      }
      const poster = new File([blob], renamedForCrop(file.name), { type: 'image/jpeg' });
      this.cancelPoster();
      this.videoReady.emit({ video: file, poster, metadata });
    } finally {
      this.capturing.set(false);
    }
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

    const { width: exportWidth, height: exportHeight } = this.exportDimensions();

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
