import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { beforeEach, describe, expect, it } from 'vitest';

import { CurrentBrand } from '../../../core/auth/current-brand';
import { I18n } from '../../../core/i18n/i18n';
import { MediaUploader, VideoSelection } from '../../../shared/ui/media-uploader';
import { ContentMediaField, MediaChange } from './content-media-field';
import { ContentMedia, MediaNotReadyError, MediaRefusedError } from './content-media';

const SCOPE = { tenantId: 't1', brandId: 'b1' };

class FakeContentMedia {
  readonly thumbnails = signal<Readonly<Record<string, string>>>({});
  readonly ensured: string[] = [];
  imageResult: Promise<string> = Promise.resolve('asset-1');
  videoResult: Promise<{ assetId: string; posterAssetId: string }> = Promise.resolve({
    assetId: 'video-1',
    posterAssetId: 'poster-1',
  });
  readonly images: File[] = [];
  readonly videos: { video: File; poster: File }[] = [];

  ensureThumbnail(_scope: unknown, assetId: string): void {
    this.ensured.push(assetId);
  }
  uploadImage(_scope: unknown, file: File): Promise<string> {
    this.images.push(file);
    return this.imageResult;
  }
  uploadVideo(
    _scope: unknown,
    video: File,
    poster: File,
  ): Promise<{ assetId: string; posterAssetId: string }> {
    this.videos.push({ video, poster });
    return this.videoResult;
  }
}

function setup(): {
  fixture: ReturnType<typeof TestBed.createComponent<ContentMediaField>>;
  media: FakeContentMedia;
  changes: MediaChange[];
  root: HTMLElement;
} {
  const media = new FakeContentMedia();
  TestBed.configureTestingModule({
    providers: [
      { provide: ContentMedia, useValue: media },
      { provide: CurrentBrand, useValue: { scope: signal(SCOPE) } },
    ],
  });
  TestBed.inject(I18n).setLocale('en');
  const fixture = TestBed.createComponent(ContentMediaField);
  const changes: MediaChange[] = [];
  fixture.componentInstance.changed.subscribe((c) => changes.push(c));
  fixture.detectChanges();
  return { fixture, media, changes, root: fixture.nativeElement as HTMLElement };
}

const settle = (): Promise<void> => new Promise((resolve) => setTimeout(resolve, 0));
const uploader = (
  fixture: ReturnType<typeof TestBed.createComponent<ContentMediaField>>,
): MediaUploader =>
  fixture.debugElement.query(By.directive(MediaUploader)).componentInstance as MediaUploader;

describe('ContentMediaField (ADR 0157, media)', () => {
  beforeEach(() => TestBed.resetTestingModule());

  it('says there is no image yet, and the shape the placement wants', () => {
    const { fixture, root } = setup();
    fixture.componentRef.setInput('ratio', '3:1');
    fixture.componentRef.setInput('minWidthPx', 960);
    fixture.detectChanges();

    expect(root.textContent).toContain('No image yet.');
    expect(root.textContent).toContain('Shape 3:1, at least 960 px wide.');
    expect(root.querySelector('[data-testid="content-media-current"]')).toBeNull();
  });

  it('asks for the thumbnail of a saved asset and draws it when it arrives', () => {
    const { fixture, media, root } = setup();
    fixture.componentRef.setInput('assetId', 'saved-1');
    fixture.detectChanges();
    expect(media.ensured).toContain('saved-1');

    media.thumbnails.set({ 'saved-1': 'https://cdn.example/t.jpg' });
    fixture.detectChanges();
    expect(root.querySelector('img')?.getAttribute('src')).toBe('https://cdn.example/t.jpg');
  });

  it('draws a video by its poster and labels it a video', () => {
    const { fixture, media, root } = setup();
    media.thumbnails.set({ 'poster-1': 'blob:poster' });
    fixture.componentRef.setInput('assetId', 'video-1');
    fixture.componentRef.setInput('posterId', 'poster-1');
    fixture.detectChanges();

    expect(root.querySelector('img')?.getAttribute('src')).toBe('blob:poster');
    expect(root.textContent).toContain('Video');
  });

  it('crops, uploads through ContentMedia and reports the new asset', async () => {
    const { fixture, media, changes } = setup();
    const file = new File(['x'], 'b.jpg', { type: 'image/jpeg' });

    uploader(fixture).cropped.emit(file);
    await settle();

    expect(media.images).toEqual([file]);
    expect(changes).toEqual([{ assetId: 'asset-1', posterAssetId: null }]);
  });

  it('takes a video with its poster only when the plan allows video', async () => {
    const { fixture, media, changes } = setup();
    expect(uploader(fixture).accept()).not.toContain('video/mp4');
    expect(uploader(fixture).allowVideo()).toBe(false);

    fixture.componentRef.setInput('allowVideo', true);
    fixture.detectChanges();
    expect(uploader(fixture).accept()).toContain('video/mp4');

    const selection: VideoSelection = {
      video: new File(['v'], 'c.mp4', { type: 'video/mp4' }),
      poster: new File(['p'], 'c.jpg', { type: 'image/jpeg' }),
      metadata: { durationMs: 5000, widthPx: 1280, heightPx: 720 },
    };
    uploader(fixture).videoReady.emit(selection);
    await settle();

    expect(media.videos).toEqual([{ video: selection.video, poster: selection.poster }]);
    expect(changes).toEqual([{ assetId: 'video-1', posterAssetId: 'poster-1' }]);
  });

  it('tells the operator how to export a video the platform refused', async () => {
    const { fixture, media, changes, root } = setup();
    fixture.componentRef.setInput('allowVideo', true);
    media.videoResult = Promise.reject(new MediaRefusedError('VIDEO'));
    fixture.detectChanges();

    uploader(fixture).videoReady.emit({
      video: new File(['v'], 'c.mp4', { type: 'video/mp4' }),
      poster: new File(['p'], 'c.jpg', { type: 'image/jpeg' }),
      metadata: { durationMs: 5000, widthPx: 1280, heightPx: 720 },
    });
    await settle();
    fixture.detectChanges();

    expect(changes).toEqual([]);
    const error = root.querySelector('[data-testid="content-media-error"]')?.textContent ?? '';
    expect(error).toContain('H.264');
    expect(error).toContain('12 MB');
    expect(error).toContain('fast start');
  });

  it('names a refused image and an unfinished verification differently', async () => {
    const { fixture, media, root } = setup();
    media.imageResult = Promise.reject(new MediaRefusedError('IMAGE'));
    uploader(fixture).cropped.emit(new File(['x'], 'b.jpg', { type: 'image/jpeg' }));
    await settle();
    fixture.detectChanges();
    expect(root.querySelector('[data-testid="content-media-error"]')?.textContent).toContain(
      'JPEG, PNG or WebP',
    );

    media.imageResult = Promise.reject(new MediaNotReadyError());
    uploader(fixture).cropped.emit(new File(['x'], 'b.jpg', { type: 'image/jpeg' }));
    await settle();
    fixture.detectChanges();
    expect(root.querySelector('[data-testid="content-media-error"]')?.textContent).toContain(
      'has not been verified yet',
    );
  });

  it('shows the uploader’s own client-side refusal as a sentence', () => {
    const { fixture, root } = setup();

    uploader(fixture).rejected.emit('videoTooLong');
    fixture.detectChanges();

    expect(root.querySelector('[data-testid="content-media-error"]')?.textContent).toContain(
      'longer than 20 seconds',
    );
  });

  it('removing the image reports nothing at all', () => {
    const { fixture, changes, root } = setup();
    fixture.componentRef.setInput('assetId', 'saved-1');
    fixture.detectChanges();

    (root.querySelector('[data-testid="content-media-remove"]') as HTMLButtonElement).click();

    expect(changes).toEqual([{ assetId: null, posterAssetId: null }]);
  });

  it('hides the uploader while disabled', () => {
    const { fixture, root } = setup();
    fixture.componentRef.setInput('disabled', true);
    fixture.detectChanges();

    expect(root.querySelector('q-media-uploader')).toBeNull();
  });
});
