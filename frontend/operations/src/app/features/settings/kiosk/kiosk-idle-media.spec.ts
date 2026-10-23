import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';

import { LocationScope } from '../../../core/api/operations-paths';
import { ApiError, ApiErrorCode } from '../../../core/api/problem-details';
import { I18n } from '../../../core/i18n/i18n';
import { MediaUploader } from '../../../shared/ui/media-uploader';
import {
  IdleMediaItem,
  IdleMediaList,
  IdleUploadRejected,
  IdleUploadTimedOut,
  KioskApi,
  KioskChannelChoice,
} from './kiosk-api';
import { KioskIdleMedia } from './kiosk-idle-media';

const SCOPE: LocationScope = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };

const CHANNELS: readonly KioskChannelChoice[] = [
  { channelId: 'c1', code: 'LOBBY', displayName: 'Lobby channel' },
  { channelId: 'c2', code: 'TERRACE', displayName: 'Terrace channel' },
];

function item(n: number): IdleMediaItem {
  return {
    id: `i${n}`,
    assetId: `a${n}`,
    position: n - 1,
    url: `/api/v1/storefront/tenants/t1/media/a${n}`,
  };
}

function list(count: number, version = count): IdleMediaList {
  return {
    channelId: 'c1',
    version,
    items: Array.from({ length: count }, (_, index) => item(index + 1)),
    maximumCount: 8,
    maximumBytes: 1_048_576,
    ratio: '9:16',
  };
}

async function flush(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

function jpeg(size: number): File {
  return new File([new Uint8Array(size)], 'idle.jpg', { type: 'image/jpeg' });
}

describe('KioskIdleMedia (ADR 0187, D25)', () => {
  let fixture: ComponentFixture<KioskIdleMedia>;

  const byId = (id: string): HTMLElement | null =>
    (fixture.nativeElement as HTMLElement).querySelector(`[data-testid="${id}"]`);

  function click(id: string): void {
    (byId(id) as HTMLElement).click();
    fixture.detectChanges();
  }

  async function render(
    api: Partial<KioskApi>,
    options: { manage?: boolean; channels?: readonly KioskChannelChoice[] } = {},
  ): Promise<void> {
    await TestBed.configureTestingModule({
      imports: [KioskIdleMedia],
      providers: [{ provide: KioskApi, useValue: api }],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(KioskIdleMedia);
    fixture.componentRef.setInput('scope', SCOPE);
    fixture.componentRef.setInput('channels', options.channels ?? CHANNELS);
    fixture.componentRef.setInput('canManage', options.manage !== false);
    fixture.detectChanges();
    await flush();
    fixture.detectChanges();
  }

  function uploader(): MediaUploader {
    return fixture.debugElement.query(By.directive(MediaUploader))
      .componentInstance as MediaUploader;
  }

  it('reads the first channel list and shows the pictures in play order with a kiosk-frame preview', async () => {
    const idleMedia = vi.fn().mockResolvedValue(list(3));
    await render({ idleMedia });

    expect(idleMedia).toHaveBeenCalledWith(SCOPE, 'c1');
    const rows = (byId('kiosk-idle-list') as HTMLElement).querySelectorAll('li');
    expect(rows.length).toBe(3);
    expect(byId('kiosk-idle-count')?.textContent).toContain('3 of 8');
    const preview = byId('kiosk-idle-preview') as HTMLImageElement;
    expect(preview.getAttribute('src')).toContain('/api/v1/storefront/tenants/t1/media/a1');
    expect(byId('kiosk-idle-shared')).not.toBeNull();
  });

  it('says there is nothing to set up when the branch has no active kiosk channel, and asks for nothing', async () => {
    const idleMedia = vi.fn();
    await render({ idleMedia }, { channels: [] });

    expect(byId('kiosk-idle-no-channels')).not.toBeNull();
    expect(idleMedia).not.toHaveBeenCalled();
  });

  it('offers a channel choice only when there is more than one, and reads the chosen list', async () => {
    const idleMedia = vi.fn().mockResolvedValueOnce(list(1)).mockResolvedValueOnce(list(2));
    await render({ idleMedia });

    const select = byId('kiosk-idle-channel') as HTMLSelectElement;
    select.value = 'c2';
    select.dispatchEvent(new Event('change'));
    await flush();
    fixture.detectChanges();

    expect(idleMedia).toHaveBeenLastCalledWith(SCOPE, 'c2');
    expect((byId('kiosk-idle-list') as HTMLElement).querySelectorAll('li').length).toBe(2);
  });

  it('shows the first picture empty-state, and the uploader, for an empty list', async () => {
    await render({ idleMedia: () => Promise.resolve(list(0, 0)) });

    expect(byId('kiosk-idle-empty')).not.toBeNull();
    expect(byId('kiosk-idle-preview-empty')).not.toBeNull();
    expect(byId('kiosk-idle-uploader')).not.toBeNull();
  });

  it('configures the uploader for 9:16 stills only, capped at the platform limit', async () => {
    await render({ idleMedia: () => Promise.resolve(list(1)) });

    const component = uploader();
    expect(component.aspectRatios()).toEqual(['9:16']);
    expect(component.accept()).toBe('image/jpeg,image/png,image/webp');
    expect(component.accept()).not.toContain('video');
    // The 1 MB cap is on the cropped picture, checked in onPicture; the uploader sees the original, which is far larger.
    expect(component.maxSizeBytes()).toBeGreaterThan(1_048_576);
    expect(component.allowVideo()).toBe(false);
  });

  it('takes an ordinary phone photo of several megabytes into the crop step, because the 1 MB cap is on the cropped picture', async () => {
    URL.createObjectURL = () => 'blob:fake';
    const uploadIdlePicture = vi.fn();
    await render({ idleMedia: () => Promise.resolve(list(1)), uploadIdlePicture });
    const rejections: string[] = [];
    uploader().rejected.subscribe((reason) => rejections.push(reason));

    const input = byId('kiosk-idle-uploader')!.querySelector(
      '[data-testid="uploader-file-input"]',
    ) as HTMLInputElement;
    const photo = new File([new Uint8Array(3 * 1_048_576)], 'photo.jpg', { type: 'image/jpeg' });
    Object.defineProperty(input, 'files', { value: [photo], configurable: true });
    input.dispatchEvent(new Event('change'));
    fixture.detectChanges();

    expect(rejections).toEqual([]);
    expect(byId('kiosk-idle-problem')).toBeNull();
    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[data-testid="uploader-crop-box"]'),
    ).not.toBeNull();
    // Nothing is uploaded until the crop is confirmed.
    expect(uploadIdlePicture).not.toHaveBeenCalled();
  });

  it('uploads a cropped picture, waits for the platform to verify it, then puts it at the end', async () => {
    const after = list(2, 2);
    const uploadIdlePicture = vi.fn().mockResolvedValue('a2');
    const attachIdle = vi.fn().mockReturnValue(of(after));
    await render({ idleMedia: () => Promise.resolve(list(1, 1)), uploadIdlePicture, attachIdle });

    const file = jpeg(200_000);
    uploader().cropped.emit(file);
    await flush();
    fixture.detectChanges();

    expect(uploadIdlePicture).toHaveBeenCalledWith(SCOPE, 'c1', file);
    expect(attachIdle).toHaveBeenCalledWith(SCOPE, 'c1', 'a2', 1);
    expect((byId('kiosk-idle-list') as HTMLElement).querySelectorAll('li').length).toBe(2);
    expect(byId('kiosk-idle-notice')).not.toBeNull();
    expect(byId('kiosk-idle-problem')).toBeNull();
  });

  it('refuses a picture still over 1 MB after the crop before any request is made', async () => {
    const uploadIdlePicture = vi.fn();
    await render({ idleMedia: () => Promise.resolve(list(0, 0)), uploadIdlePicture });

    uploader().cropped.emit(jpeg(1_048_577));
    await flush();
    fixture.detectChanges();

    expect(uploadIdlePicture).not.toHaveBeenCalled();
    expect(byId('kiosk-idle-problem')?.textContent).toContain('1 MB');
  });

  it('says the picture is not 9:16 when the platform rejected it for its shape', async () => {
    const uploadIdlePicture = vi
      .fn()
      .mockRejectedValue(new IdleUploadRejected('PURPOSE_ASPECT_RATIO'));
    await render({ idleMedia: () => Promise.resolve(list(0, 0)), uploadIdlePicture });

    uploader().cropped.emit(jpeg(1000));
    await flush();
    fixture.detectChanges();

    expect(byId('kiosk-idle-problem')?.textContent).toContain('9:16');
  });

  it('says so when the platform has not finished checking the picture', async () => {
    const uploadIdlePicture = vi.fn().mockRejectedValue(new IdleUploadTimedOut());
    await render({ idleMedia: () => Promise.resolve(list(0, 0)), uploadIdlePicture });

    uploader().cropped.emit(jpeg(1000));
    await flush();
    fixture.detectChanges();

    expect(byId('kiosk-idle-problem')).not.toBeNull();
    expect(byId('kiosk-idle-notice')).toBeNull();
  });

  it("names the platform's reason when an attach is refused", async () => {
    const attachIdle = vi
      .fn()
      .mockReturnValue(
        throwError(
          () => new ApiError('IDLE_MEDIA_REFUSED', 422, { status: 422, reason: 'LIMIT' }, 'c'),
        ),
      );
    await render({
      idleMedia: () => Promise.resolve(list(1)),
      uploadIdlePicture: () => Promise.resolve('a9'),
      attachIdle,
    });

    uploader().cropped.emit(jpeg(1000));
    await flush();
    fixture.detectChanges();

    expect(byId('kiosk-idle-problem')?.textContent).toContain('8 pictures');
  });

  it('moves a picture up by sending the whole list in its new order against the version it read', async () => {
    const reorderIdle = vi.fn().mockReturnValue(of(list(3, 4)));
    await render({ idleMedia: () => Promise.resolve(list(3, 3)), reorderIdle });

    click('kiosk-idle-up-i2');
    await flush();

    expect(reorderIdle).toHaveBeenCalledWith(SCOPE, 'c1', ['i2', 'i1', 'i3'], 3);
  });

  it('cannot move the first picture up or the last one down', async () => {
    await render({ idleMedia: () => Promise.resolve(list(3)) });

    expect((byId('kiosk-idle-up-i1') as HTMLButtonElement).disabled).toBe(true);
    expect((byId('kiosk-idle-down-i3') as HTMLButtonElement).disabled).toBe(true);
    expect((byId('kiosk-idle-up-i3') as HTMLButtonElement).disabled).toBe(false);
  });

  it('removes a picture against the version it read and shows the list the platform answers with', async () => {
    const removeIdle = vi.fn().mockReturnValue(of(list(1, 4)));
    await render({ idleMedia: () => Promise.resolve(list(2, 3)), removeIdle });

    click('kiosk-idle-remove-i2');
    await flush();
    fixture.detectChanges();

    expect(removeIdle).toHaveBeenCalledWith(SCOPE, 'c1', 'i2', 3);
    expect((byId('kiosk-idle-list') as HTMLElement).querySelectorAll('li').length).toBe(1);
  });

  it("reloads the list and says so when a second manager's change got there first", async () => {
    const idleMedia = vi.fn().mockResolvedValueOnce(list(2, 2)).mockResolvedValueOnce(list(3, 5));
    const removeIdle = vi
      .fn()
      .mockReturnValue(
        throwError(() => new ApiError(ApiErrorCode.STALE_VERSION, 409, { status: 409 }, 'c')),
      );
    await render({ idleMedia, removeIdle });

    click('kiosk-idle-remove-i1');
    await flush();
    fixture.detectChanges();

    expect(idleMedia).toHaveBeenCalledTimes(2);
    expect((byId('kiosk-idle-list') as HTMLElement).querySelectorAll('li').length).toBe(3);
    expect(byId('kiosk-idle-problem')?.textContent).toContain('Someone else');
  });

  it('shows the list and no controls to a reader who cannot manage kiosks', async () => {
    await render({ idleMedia: () => Promise.resolve(list(2)) }, { manage: false });

    expect(byId('kiosk-idle-list')).not.toBeNull();
    expect(byId('kiosk-idle-uploader')).toBeNull();
    expect(byId('kiosk-idle-up-i2')).toBeNull();
    expect(byId('kiosk-idle-remove-i1')).toBeNull();
  });

  it('hides the uploader and says so when the loop already holds eight pictures', async () => {
    await render({ idleMedia: () => Promise.resolve(list(8)) });

    expect(byId('kiosk-idle-full')).not.toBeNull();
    expect(byId('kiosk-idle-uploader')).toBeNull();
  });

  it('says the channel is out of reach when the platform refuses the read', async () => {
    await render({
      idleMedia: () =>
        Promise.reject(new ApiError(ApiErrorCode.INSUFFICIENT_CAPABILITY, 403, null, 'c')),
    });

    expect(byId('kiosk-idle-denied')).not.toBeNull();
    expect(byId('kiosk-idle-list')).toBeNull();
  });

  it('names a rejected file type or size from the uploader in the operator’s words', async () => {
    await render({ idleMedia: () => Promise.resolve(list(0, 0)) });

    uploader().rejected.emit('tooLarge');
    fixture.detectChanges();
    expect(byId('kiosk-idle-problem')).not.toBeNull();

    uploader().rejected.emit('videoNotSupported');
    fixture.detectChanges();
    expect(byId('kiosk-idle-problem')?.textContent).toMatch(/video/i);
  });
});
