import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { LocationScope } from '../../../core/api/operations-paths';
import { ApiError, ApiErrorCode } from '../../../core/api/problem-details';
import { I18n } from '../../../core/i18n/i18n';
import { MediaApi } from '../../catalog/media-api';
import { PushWordingFields, pushTargetIsValid } from './push-wording-fields';

const SCOPE: LocationScope = { tenantId: 'tenant-1', brandId: 'brand-1', locationId: 'location-1' };

describe('pushTargetIsValid', () => {
  it('accepts the front page, a path, and a path with a query and a fragment', () => {
    for (const ok of ['', '  ', '/', '/menu', '/category/pizza?promo=PIZZA20#top']) {
      expect(pushTargetIsValid(ok), ok).toBe(true);
    }
  });

  it('refuses everything that is not a path on the storefront', () => {
    for (const bad of [
      'menu',
      '//evil.example',
      'https://evil.example',
      '/a b',
      '/a\\b',
      '/' + 'x'.repeat(199),
    ]) {
      expect(pushTargetIsValid(bad), bad).toBe(false);
    }
  });
});

describe('PushWordingFields', () => {
  let fixture: ComponentFixture<PushWordingFields>;
  let media: { upload: ReturnType<typeof vi.fn> };
  const cover = vi.fn();
  const target = vi.fn();

  beforeEach(async () => {
    cover.mockReset();
    target.mockReset();
    media = { upload: vi.fn().mockReturnValue(of({ assetId: 'asset-9' })) };
    await TestBed.configureTestingModule({
      imports: [PushWordingFields],
      providers: [{ provide: MediaApi, useValue: media }],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(PushWordingFields);
    fixture.componentRef.setInput('scope', SCOPE);
    fixture.componentInstance.coverChange.subscribe(cover);
    fixture.componentInstance.targetPathChange.subscribe(target);
    fixture.detectChanges();
  });

  const host = (): HTMLElement => fixture.nativeElement as HTMLElement;
  const part = (testId: string): HTMLElement | null =>
    host().querySelector(`[data-testid="${testId}"]`);
  const component = () =>
    fixture.componentInstance as unknown as {
      upload(file: File): Promise<void>;
      onRejected(reason: string): void;
    };

  it('reports what the author types as the target', () => {
    const input = part('push-target') as HTMLInputElement;
    input.value = '/menu';
    input.dispatchEvent(new Event('input'));

    expect(target).toHaveBeenCalledWith('/menu');
  });

  it('uploads a cover as the tenant’s own public image and reports only its id', async () => {
    const file = new File(['x'], 'cover.jpg', { type: 'image/jpeg' });
    URL.createObjectURL = vi.fn().mockReturnValue('blob:cover');
    URL.revokeObjectURL = vi.fn();

    await component().upload(file);

    expect(media.upload).toHaveBeenCalledWith('tenant-1', 'TENANT', 'tenant-1', 'PUBLIC', file);
    expect(cover).toHaveBeenCalledWith('asset-9');
    fixture.detectChanges();
    expect((part('push-cover-preview') as HTMLImageElement).src).toContain('blob:cover');
  });

  it('says what went wrong when the upload fails, and reports no cover', async () => {
    media.upload.mockReturnValue(
      throwError(() => new ApiError(ApiErrorCode.VALIDATION_FAILED, 400, null, null)),
    );

    await component().upload(new File(['x'], 'cover.jpg', { type: 'image/jpeg' }));
    fixture.detectChanges();

    expect(cover).not.toHaveBeenCalled();
    expect(part('push-cover-error')).not.toBeNull();
  });

  it('names a picture the uploader refused before any upload', () => {
    component().onRejected('tooLarge');
    fixture.detectChanges();

    expect(part('push-cover-error')?.textContent).toContain('larger than this upload allows');
    expect(media.upload).not.toHaveBeenCalled();
  });

  it('offers to remove a cover that is set, and reports its removal', () => {
    fixture.componentRef.setInput('coverAssetId', 'asset-3');
    fixture.detectChanges();

    (part('push-cover-remove') as HTMLButtonElement).click();

    expect(cover).toHaveBeenCalledWith(null);
  });
});
