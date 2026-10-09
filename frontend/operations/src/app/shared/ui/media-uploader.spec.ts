import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import {
  MEDIA_UPLOADER_VIDEO,
  MediaUploader,
  MediaUploaderVideo,
  VIDEO_LIMITS,
  VideoMetadata,
  VideoSelection,
  exportSizeFor,
  mediaUploaderRejectionMessageKey,
  videoRejectionReason,
} from './media-uploader';

function render(): ReturnType<typeof TestBed.createComponent<MediaUploader>> {
  const fixture = TestBed.createComponent(MediaUploader);
  fixture.detectChanges();
  return fixture;
}

function fileInput(
  fixture: ReturnType<typeof TestBed.createComponent<MediaUploader>>,
): HTMLInputElement {
  return (fixture.nativeElement as HTMLElement).querySelector<HTMLInputElement>(
    '[data-testid="uploader-file-input"]',
  )!;
}

function fileList(files: File[]): FileList {
  const list = {
    length: files.length,
    item: (index: number) => files[index] ?? null,
    [Symbol.iterator]: function* () {
      yield* files;
    },
  };
  files.forEach((file, index) => ((list as unknown as Record<number, File>)[index] = file));
  return list as unknown as FileList;
}

describe('MediaUploader', () => {
  beforeEach(() => TestBed.configureTestingModule({}));

  it('rejects a file over the size cap without entering crop mode', () => {
    const fixture = render();
    fixture.componentRef.setInput('maxSizeBytes', 10);
    fixture.detectChanges();
    let reason: string | undefined;
    fixture.componentInstance.rejected.subscribe((r) => (reason = r));
    const big = new File([new Uint8Array(20)], 'big.jpg', { type: 'image/jpeg' });

    fixture.componentInstance['onFileInput'](fileList([big]));
    fixture.detectChanges();

    expect(reason).toBe('tooLarge');
    expect(fixture.componentInstance['cropping']()).toBe(false);
  });

  it('rejects a type outside the accept list', () => {
    const fixture = render();
    fixture.componentRef.setInput('accept', 'image/jpeg');
    fixture.detectChanges();
    let reason: string | undefined;
    fixture.componentInstance.rejected.subscribe((r) => (reason = r));
    const gif = new File(['x'], 'a.gif', { type: 'image/gif' });

    fixture.componentInstance['onFileInput'](fileList([gif]));

    expect(reason).toBe('unsupportedType');
  });

  it('enters crop mode for an accepted image, offering every configured ratio', () => {
    const fixture = render();
    const jpeg = new File(['x'], 'a.jpg', { type: 'image/jpeg' });

    fixture.componentInstance['onFileInput'](fileList([jpeg]));
    fixture.detectChanges();

    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelector('[data-testid="uploader-crop-box"]')).toBeTruthy();
    expect(host.querySelectorAll('[data-testid^="uploader-ratio-"]').length).toBe(4);
  });

  it('refuses a video client-side rather than passing it through to an image-only pipeline (row X.12)', () => {
    const fixture = render();
    let reason: string | undefined;
    let selectedEmitted = false;
    fixture.componentInstance.rejected.subscribe((r) => (reason = r));
    fixture.componentInstance.selected.subscribe(() => (selectedEmitted = true));
    const video = new File(['x'], 'a.mp4', { type: 'video/mp4' });

    fixture.componentInstance['onFileInput'](fileList([video]));
    fixture.detectChanges();

    expect(reason).toBe('videoNotSupported');
    expect(selectedEmitted).toBe(false);
    expect(fixture.componentInstance['cropping']()).toBe(false);
    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[data-testid="uploader-crop-box"]'),
    ).toBeNull();
  });

  it("still lists video in the file picker's own accept attribute, so a video is selectable and this refusal is what an operator sees (row X.12)", () => {
    const fixture = render();

    expect(fileInput(fixture).accept).toContain('video/mp4');
  });

  it('refuses a webm video the same way as mp4', () => {
    const fixture = render();
    let reason: string | undefined;
    fixture.componentInstance.rejected.subscribe((r) => (reason = r));
    const video = new File(['x'], 'a.webm', { type: 'video/webm' });

    fixture.componentInstance['onFileInput'](fileList([video]));

    expect(reason).toBe('videoNotSupported');
  });

  it('still passes a non-image, non-video type through unmodified when a caller widens accept() beyond the default', () => {
    const fixture = render();
    fixture.componentRef.setInput('accept', 'image/jpeg,application/pdf');
    fixture.detectChanges();
    let emitted: File | undefined;
    fixture.componentInstance.selected.subscribe((f) => (emitted = f));
    const pdf = new File(['x'], 'a.pdf', { type: 'application/pdf' });

    fixture.componentInstance['onFileInput'](fileList([pdf]));

    expect(emitted?.name).toBe('a.pdf');
  });

  it('cancel leaves crop mode without emitting a cropped file', () => {
    const fixture = render();
    let emitted = false;
    fixture.componentInstance.cropped.subscribe(() => (emitted = true));
    const jpeg = new File(['x'], 'a.jpg', { type: 'image/jpeg' });
    fixture.componentInstance['onFileInput'](fileList([jpeg]));
    fixture.detectChanges();

    (
      (fixture.nativeElement as HTMLElement).querySelector(
        '[data-testid="uploader-crop-cancel"]',
      ) as HTMLButtonElement
    ).click();
    fixture.detectChanges();

    expect(emitted).toBe(false);
    expect(fixture.componentInstance['cropping']()).toBe(false);
  });

  it('selecting a ratio marks it active', () => {
    const fixture = render();
    const jpeg = new File(['x'], 'a.jpg', { type: 'image/jpeg' });
    fixture.componentInstance['onFileInput'](fileList([jpeg]));
    fixture.detectChanges();

    (
      (fixture.nativeElement as HTMLElement).querySelector(
        '[data-testid="uploader-ratio-9:16"]',
      ) as HTMLButtonElement
    ).click();
    fixture.detectChanges();

    expect(fixture.componentInstance['selectedRatio']()).toBe('9:16');
    expect(
      (fixture.nativeElement as HTMLElement)
        .querySelector('[data-testid="uploader-ratio-9:16"]')
        ?.className.includes('uploader__ratio--active'),
    ).toBe(true);
  });

  it('shows an indeterminate progress bar while uploading with no known progress', () => {
    const fixture = render();
    fixture.componentRef.setInput('uploading', true);
    fixture.detectChanges();

    const bar = (fixture.nativeElement as HTMLElement).querySelector('.uploader__progress-bar');
    expect(bar?.className).toContain('uploader__progress-bar--indeterminate');
  });
});

describe('mediaUploaderRejectionMessageKey (row X.12)', () => {
  it('maps videoNotSupported to its own clear sentence, not the generic unsupported-type one', () => {
    expect(mediaUploaderRejectionMessageKey('videoNotSupported')).toBe(
      'ui.mediaUploader.videoNotSupported',
    );
  });

  it('maps tooLarge and falls back to unsupportedType for anything else', () => {
    expect(mediaUploaderRejectionMessageKey('tooLarge')).toBe('ui.mediaUploader.tooLarge');
    expect(mediaUploaderRejectionMessageKey('unsupportedType')).toBe(
      'ui.mediaUploader.unsupportedType',
    );
    expect(mediaUploaderRejectionMessageKey('something-unrecognized')).toBe(
      'ui.mediaUploader.unsupportedType',
    );
  });
});

describe('exportSizeFor (ADR 0157, Decision 11: a per-use export edge)', () => {
  it('exports a 3:1 banner at 1600 px on the long edge, and the old 960 for a product photo', () => {
    expect(exportSizeFor('3:1', 1600)).toEqual({ width: 1600, height: 533 });
    expect(exportSizeFor('3:2', 960)).toEqual({ width: 960, height: 640 });
    expect(exportSizeFor('1:1', 960)).toEqual({ width: 960, height: 960 });
  });

  it('puts the long edge on the height for a portrait ratio', () => {
    expect(exportSizeFor('9:16', 960)).toEqual({ width: 540, height: 960 });
  });
});

describe('MediaUploader export edge', () => {
  beforeEach(() => TestBed.configureTestingModule({}));

  it('defaults to 960 px so every existing caller exports as before', () => {
    const fixture = render();
    expect(fixture.componentInstance.exportEdgePx()).toBe(960);
  });

  it('takes the long edge the caller names, and exports the chosen ratio on it', () => {
    const fixture = render();
    fixture.componentRef.setInput('exportEdgePx', 1600);
    fixture.componentRef.setInput('aspectRatios', ['3:1']);
    fixture.componentInstance['selectRatio']('3:1');

    expect(fixture.componentInstance.exportEdgePx()).toBe(1600);
    expect(fixture.componentInstance['exportDimensions']()).toEqual({ width: 1600, height: 533 });
  });

  it('exports at 960 for a caller that names nothing', () => {
    const fixture = render();
    fixture.componentInstance['selectRatio']('3:2');

    expect(fixture.componentInstance['exportDimensions']()).toEqual({ width: 960, height: 640 });
  });
});

describe('videoRejectionReason (ADR 0157, Decision 8: the platform caps, checked before any upload)', () => {
  const ok: VideoMetadata = { durationMs: 8_000, widthPx: 1280, heightPx: 720 };
  const mp4 = { type: 'video/mp4', size: 5 * 1024 * 1024 };

  it('takes an MP4 inside every cap', () => {
    expect(videoRejectionReason(mp4, ok)).toBeNull();
    expect(
      videoRejectionReason(
        { type: 'video/mp4', size: VIDEO_LIMITS.maxBytes },
        { durationMs: VIDEO_LIMITS.maxDurationMs, widthPx: 1920, heightPx: 480 },
      ),
    ).toBeNull();
  });

  it.each([
    ['not an MP4', { type: 'video/webm', size: 1 }, ok, 'videoNotMp4'],
    ['over 12 MiB', { type: 'video/mp4', size: VIDEO_LIMITS.maxBytes + 1 }, ok, 'videoTooLarge'],
    ['longer than 20 s', mp4, { ...ok, durationMs: 20_001 }, 'videoTooLong'],
    ['wider than 1920 px', mp4, { ...ok, widthPx: 1921 }, 'videoDimensions'],
    ['a short edge under 480 px', mp4, { ...ok, heightPx: 479 }, 'videoDimensions'],
    ['unreadable', mp4, null, 'videoUnreadable'],
    ['zero-sized', mp4, { ...ok, widthPx: 0 }, 'videoUnreadable'],
  ])('refuses a video that is %s with its own reason', (_label, file, metadata, reason) => {
    expect(videoRejectionReason(file, metadata)).toBe(reason);
  });

  it('maps every video reason to its own sentence, and the poster failure too', () => {
    for (const reason of [
      'videoNotMp4',
      'videoTooLarge',
      'videoTooLong',
      'videoDimensions',
      'videoUnreadable',
      'posterFailed',
    ]) {
      expect(mediaUploaderRejectionMessageKey(reason)).toBe(`ui.mediaUploader.${reason}`);
    }
  });
});

/** Lets the uploader's own unmanaged promise chain (probe, then the poster step) run to its end. */
function settle(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0));
}

describe('MediaUploader video mode (ADR 0157, Release 3)', () => {
  let metadata: VideoMetadata | null;
  let posterBlob: Blob | null;
  const fakeVideo: MediaUploaderVideo = {
    probe: () => Promise.resolve(metadata),
    capture: () => Promise.resolve(posterBlob),
  };

  function renderVideo(): ReturnType<typeof TestBed.createComponent<MediaUploader>> {
    TestBed.configureTestingModule({
      providers: [{ provide: MEDIA_UPLOADER_VIDEO, useValue: fakeVideo }],
    });
    const fixture = TestBed.createComponent(MediaUploader);
    fixture.componentRef.setInput('allowVideo', true);
    fixture.componentRef.setInput('accept', 'image/jpeg,image/png,video/mp4');
    fixture.detectChanges();
    return fixture;
  }

  beforeEach(() => {
    metadata = { durationMs: 8_000, widthPx: 1280, heightPx: 720 };
    posterBlob = new Blob(['jpeg'], { type: 'image/jpeg' });
    URL.createObjectURL = () => 'blob:fake';
    URL.revokeObjectURL = () => undefined;
  });

  it('takes an MP4 inside the caps to the poster step instead of refusing it', async () => {
    const fixture = renderVideo();
    const reasons: string[] = [];
    fixture.componentInstance.rejected.subscribe((r) => reasons.push(r));
    const video = new File(['x'], 'clip.mp4', { type: 'video/mp4' });

    fixture.componentInstance['onFileInput'](fileList([video]));
    await settle();
    fixture.detectChanges();

    expect(reasons).toEqual([]);
    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[data-testid="uploader-poster-step"]'),
    ).toBeTruthy();
    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[data-testid="uploader-poster-scrub"]'),
    ).toBeTruthy();
  });

  it.each([
    [
      'a clip longer than 20 s',
      { durationMs: 21_000, widthPx: 1280, heightPx: 720 },
      'videoTooLong',
    ],
    [
      'a clip wider than 1920 px',
      { durationMs: 5_000, widthPx: 3840, heightPx: 2160 },
      'videoDimensions',
    ],
    ['a clip the browser cannot read', null, 'videoUnreadable'],
  ])('refuses %s before any request, naming the cap', async (_label, probed, reason) => {
    metadata = probed;
    const fixture = renderVideo();
    const reasons: string[] = [];
    const selections: VideoSelection[] = [];
    fixture.componentInstance.rejected.subscribe((r) => reasons.push(r));
    fixture.componentInstance.videoReady.subscribe((v) => selections.push(v));

    fixture.componentInstance['onFileInput'](
      fileList([new File(['x'], 'clip.mp4', { type: 'video/mp4' })]),
    );
    await settle();
    fixture.detectChanges();

    expect(reasons).toEqual([reason]);
    expect(selections).toEqual([]);
    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[data-testid="uploader-poster-step"]'),
    ).toBeNull();
  });

  it('refuses a WebM and a 13 MB file by their own reasons without reading either', async () => {
    const fixture = renderVideo();
    fixture.componentRef.setInput('accept', 'image/jpeg,video/mp4,video/webm');
    const reasons: string[] = [];
    fixture.componentInstance.rejected.subscribe((r) => reasons.push(r));

    fixture.componentInstance['onFileInput'](
      fileList([new File(['x'], 'a.webm', { type: 'video/webm' })]),
    );
    await settle();
    const big = new File([new Uint8Array(VIDEO_LIMITS.maxBytes + 1)], 'big.mp4', {
      type: 'video/mp4',
    });
    fixture.componentInstance['onFileInput'](fileList([big]));
    await settle();

    expect(reasons).toEqual(['videoNotMp4', 'videoTooLarge']);
  });

  it('hands back the untouched video and the poster the operator confirmed', async () => {
    const fixture = renderVideo();
    const selections: VideoSelection[] = [];
    fixture.componentInstance.videoReady.subscribe((v) => selections.push(v));
    const video = new File(['x'], 'clip.mp4', { type: 'video/mp4' });
    fixture.componentInstance['onFileInput'](fileList([video]));
    await settle();
    fixture.detectChanges();

    (
      (fixture.nativeElement as HTMLElement).querySelector(
        '[data-testid="uploader-poster-confirm"]',
      ) as HTMLButtonElement
    ).click();
    await settle();
    fixture.detectChanges();

    expect(selections).toHaveLength(1);
    expect(selections[0].video).toBe(video);
    expect(selections[0].poster.type).toBe('image/jpeg');
    expect(selections[0].poster.name).toBe('clip.jpg');
    expect(selections[0].metadata).toEqual(metadata);
    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[data-testid="uploader-poster-step"]'),
    ).toBeNull();
  });

  it('says so, and keeps the video out of the upload, when no poster frame can be captured', async () => {
    posterBlob = null;
    const fixture = renderVideo();
    const reasons: string[] = [];
    const selections: VideoSelection[] = [];
    fixture.componentInstance.rejected.subscribe((r) => reasons.push(r));
    fixture.componentInstance.videoReady.subscribe((v) => selections.push(v));
    fixture.componentInstance['onFileInput'](
      fileList([new File(['x'], 'clip.mp4', { type: 'video/mp4' })]),
    );
    await settle();
    fixture.detectChanges();

    (
      (fixture.nativeElement as HTMLElement).querySelector(
        '[data-testid="uploader-poster-confirm"]',
      ) as HTMLButtonElement
    ).click();
    await settle();

    expect(reasons).toEqual(['posterFailed']);
    expect(selections).toEqual([]);
  });

  it('cancel leaves the poster step without emitting anything', async () => {
    const fixture = renderVideo();
    const selections: VideoSelection[] = [];
    fixture.componentInstance.videoReady.subscribe((v) => selections.push(v));
    fixture.componentInstance['onFileInput'](
      fileList([new File(['x'], 'clip.mp4', { type: 'video/mp4' })]),
    );
    await settle();
    fixture.detectChanges();

    (
      (fixture.nativeElement as HTMLElement).querySelector(
        '[data-testid="uploader-poster-cancel"]',
      ) as HTMLButtonElement
    ).click();
    fixture.detectChanges();

    expect(selections).toEqual([]);
    expect(fixture.componentInstance['posterStep']()).toBe(false);
  });

  it('keeps refusing a video for a caller that has not asked for video mode', () => {
    TestBed.configureTestingModule({
      providers: [{ provide: MEDIA_UPLOADER_VIDEO, useValue: fakeVideo }],
    });
    const fixture = TestBed.createComponent(MediaUploader);
    fixture.detectChanges();
    const reasons: string[] = [];
    fixture.componentInstance.rejected.subscribe((r) => reasons.push(r));

    fixture.componentInstance['onFileInput'](
      fileList([new File(['x'], 'clip.mp4', { type: 'video/mp4' })]),
    );

    expect(reasons).toEqual(['videoNotSupported']);
  });
});
