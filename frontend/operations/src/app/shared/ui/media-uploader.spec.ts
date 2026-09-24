import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { MediaUploader, mediaUploaderRejectionMessageKey } from './media-uploader';

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

  it('still lists video in the file picker\'s own accept attribute, so a video is selectable and this refusal is what an operator sees (row X.12)', () => {
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
