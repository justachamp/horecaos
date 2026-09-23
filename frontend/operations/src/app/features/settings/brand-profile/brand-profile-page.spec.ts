import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { of, throwError } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { LocationScope } from '../../../core/api/operations-paths';
import { ApiError, ApiErrorCode } from '../../../core/api/problem-details';
import { CurrentLocation } from '../../../core/auth/current-location';
import { I18n } from '../../../core/i18n/i18n';
import { MediaUploader } from '../../../shared/ui/media-uploader';
import { MediaApi, MediaAssetView } from '../../catalog/media-api';
import { BrandProfileApi, BrandView } from './brand-profile-api';
import { BrandProfilePage } from './brand-profile-page';

const SCOPE: LocationScope = { tenantId: 'tenant-1', brandId: 'brand-1', locationId: 'location-1' };

const BRAND: BrandView = {
  id: 'brand-1',
  tenantId: 'tenant-1',
  code: 'RAYHON',
  slug: 'rayhon',
  displayName: 'Rayhon',
  status: 'ACTIVE',
  contactPhone: '+998712000000',
  telegramHandle: 'rayhon_bot',
  logoAssetId: null,
  bannerAssetId: null,
  locales: [{ locale: 'ru', description: 'Ресторан', isDefault: true }],
  version: 3,
};

const BRAND_WITH_MEDIA: BrandView = {
  ...BRAND,
  logoAssetId: 'asset-logo-1',
  bannerAssetId: 'asset-banner-1',
};

class FakeCurrentLocation {
  readonly scope = signal<LocationScope | null>(SCOPE);
  readonly denied = signal(false);
  ensureLoaded = vi.fn().mockResolvedValue(undefined);
}

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

function findButton(root: HTMLElement, text: string): HTMLButtonElement {
  const found = Array.from(root.querySelectorAll('.form__actions button')).find((button) =>
    button.textContent?.includes(text),
  );
  if (!found) {
    throw new Error(`No .form__actions button contains "${text}"`);
  }
  return found as HTMLButtonElement;
}

describe('BrandProfilePage', () => {
  let fixture: ComponentFixture<BrandProfilePage>;
  let api: {
    getBrand: ReturnType<typeof vi.fn>;
    reviseBrand: ReturnType<typeof vi.fn>;
    updateProfile: ReturnType<typeof vi.fn>;
  };
  let mediaApi: {
    upload: ReturnType<typeof vi.fn>;
    downloadUrl: ReturnType<typeof vi.fn>;
  };

  async function render(
    overrides: Partial<typeof api> = {},
    location: FakeCurrentLocation = new FakeCurrentLocation(),
    mediaOverrides: Partial<typeof mediaApi> = {},
  ) {
    api = {
      getBrand: vi.fn().mockResolvedValue(BRAND),
      reviseBrand: vi.fn(),
      updateProfile: vi.fn(),
      ...overrides,
    };
    mediaApi = {
      // No asset by default; individual tests set a downloadUrl per id.
      downloadUrl: vi.fn().mockReturnValue(throwError(() => new Error('no such asset'))),
      upload: vi.fn(),
      ...mediaOverrides,
    };
    await TestBed.configureTestingModule({
      imports: [BrandProfilePage],
      providers: [
        { provide: BrandProfileApi, useValue: api },
        { provide: MediaApi, useValue: mediaApi },
        { provide: CurrentLocation, useValue: location },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(BrandProfilePage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
    return fixture;
  }

  function emitFromUploader(testId: string, event: 'cropped' | 'selected', file: File): void {
    const uploaders = fixture.debugElement.queryAll(By.directive(MediaUploader));
    const match = uploaders.find(
      (debugEl) => (debugEl.nativeElement as HTMLElement).getAttribute('data-testid') === testId,
    );
    if (!match) {
      throw new Error(`No q-media-uploader with data-testid="${testId}"`);
    }
    (match.componentInstance as MediaUploader)[event].emit(file);
  }

  function asset(assetId: string): MediaAssetView {
    return { assetId, status: 'AVAILABLE' };
  }

  it('renders the brand fields the operations surface returns', async () => {
    await render();
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Rayhon');
    expect(text).toContain('RAYHON');
    expect(text).toContain('rayhon');
    expect(text).toContain('+998712000000');
    expect(text).toContain('rayhon_bot');
  });

  it('shows the denied state without a location in scope', async () => {
    const location = new FakeCurrentLocation();
    location.scope.set(null);
    location.denied.set(true);
    await render({}, location);

    expect((fixture.nativeElement as HTMLElement).textContent).toContain('No location in scope');
    expect(api.getBrand).not.toHaveBeenCalled();
  });

  it('shows a load error for a non-403 failure', async () => {
    await render({
      getBrand: vi
        .fn()
        .mockRejectedValue(new ApiError(ApiErrorCode.INTERNAL_ERROR, 500, null, null)),
    });
    expect(fixture.nativeElement.querySelector('[role="alert"]')).toBeTruthy();
  });

  it('renames the brand through the control-plane revision endpoint, with the version as If-Match', async () => {
    const renamed: BrandView = { ...BRAND, displayName: 'Rayhon 2', version: 4 };
    await render({ reviseBrand: vi.fn().mockResolvedValue(renamed) });

    const identityBlock = fixture.nativeElement.querySelectorAll('.block')[0] as HTMLElement;
    (identityBlock.querySelector('.primary') as HTMLButtonElement).click();
    fixture.detectChanges();

    const nameInput = fixture.nativeElement.querySelector('#brand-name') as HTMLInputElement;
    nameInput.value = 'Rayhon 2';
    nameInput.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    findButton(identityBlock, 'Save').click();
    await flushMicrotasks();

    expect(api.reviseBrand).toHaveBeenCalledWith(
      SCOPE,
      { code: 'RAYHON', slug: 'rayhon', displayName: 'Rayhon 2' },
      3,
    );
  });

  it('corrects the profile: contact, media and the supported-locale set together', async () => {
    const updated: BrandView = {
      ...BRAND,
      contactPhone: '+998712009999',
      locales: [
        { locale: 'ru', description: 'Ресторан', isDefault: true },
        { locale: 'en', description: null, isDefault: false },
      ],
    };
    await render({ updateProfile: vi.fn().mockResolvedValue(updated) });

    const profileBlock = fixture.nativeElement.querySelectorAll('.block')[1] as HTMLElement;
    (profileBlock.querySelector('.primary') as HTMLButtonElement).click();
    fixture.detectChanges();

    const phoneInput = fixture.nativeElement.querySelector('#brand-phone') as HTMLInputElement;
    phoneInput.value = '+998712009999';
    phoneInput.dispatchEvent(new Event('input'));

    // KNOWN_LOCALES is [ru, uz-Latn, en], so English is the grid's third row.
    const rows = fixture.nativeElement.querySelectorAll('.locale-grid tbody tr');
    (rows[2].querySelector('input[type="checkbox"]') as HTMLInputElement).click();
    fixture.detectChanges();

    findButton(profileBlock, 'Save').click();
    await flushMicrotasks();

    expect(api.updateProfile).toHaveBeenCalledWith(SCOPE, {
      contactPhone: '+998712009999',
      telegramHandle: 'rayhon_bot',
      logoAssetId: undefined,
      bannerAssetId: undefined,
      locales: [
        { locale: 'ru', description: 'Ресторан', isDefault: true },
        { locale: 'en', description: undefined, isDefault: false },
      ],
    });
  });

  it('defaults to the first included locale when none is marked default, rather than blocking the save', async () => {
    const brandWithNoLocales: BrandView = { ...BRAND, locales: [] };
    await render({
      getBrand: vi.fn().mockResolvedValue(brandWithNoLocales),
      updateProfile: vi.fn().mockResolvedValue(brandWithNoLocales),
    });

    const profileBlock = fixture.nativeElement.querySelectorAll('.block')[1] as HTMLElement;
    (profileBlock.querySelector('.primary') as HTMLButtonElement).click();
    fixture.detectChanges();

    // uz-Latn is the grid's second row.
    const rows = fixture.nativeElement.querySelectorAll('.locale-grid tbody tr');
    (rows[1].querySelector('input[type="checkbox"]') as HTMLInputElement).click();
    fixture.detectChanges();

    findButton(profileBlock, 'Save').click();
    await flushMicrotasks();

    expect(api.updateProfile).toHaveBeenCalledWith(
      SCOPE,
      expect.objectContaining({
        locales: [{ locale: 'uz-Latn', description: undefined, isDefault: true }],
      }),
    );
  });

  // -------------------------------------------------------- 10.1 / X.12 media

  it('shows the current logo and banner as thumbnails, not a raw asset id (row 10.1)', async () => {
    const downloadUrl = vi.fn().mockImplementation((_tenantId: string, assetId: string) =>
      of(`https://cdn.example/${assetId}.jpg`),
    );
    await render({ getBrand: vi.fn().mockResolvedValue(BRAND_WITH_MEDIA) }, new FakeCurrentLocation(), {
      downloadUrl,
    });

    expect(downloadUrl).toHaveBeenCalledWith('tenant-1', 'asset-logo-1', 'THUMBNAIL');
    expect(downloadUrl).toHaveBeenCalledWith('tenant-1', 'asset-banner-1', 'THUMBNAIL');

    const logo = fixture.nativeElement.querySelector(
      '[data-testid="brand-logo-preview"]',
    ) as HTMLImageElement;
    const banner = fixture.nativeElement.querySelector(
      '[data-testid="brand-banner-preview"]',
    ) as HTMLImageElement;
    expect(logo.src).toBe('https://cdn.example/asset-logo-1.jpg');
    expect(banner.src).toBe('https://cdn.example/asset-banner-1.jpg');

    // No raw UUID text anywhere on the page -- the gap row 10.1/X.12 close.
    expect((fixture.nativeElement as HTMLElement).textContent).not.toContain('asset-logo-1');
  });

  it('renders no thumbnail, not a broken image, when no logo/banner is set yet', async () => {
    await render();
    expect(fixture.nativeElement.querySelector('[data-testid="brand-logo-preview"]')).toBeNull();
    expect(fixture.nativeElement.querySelector('[data-testid="brand-banner-preview"]')).toBeNull();
  });

  it('uploads a cropped logo through the real q-media-uploader and writes the returned asset id on save (row 10.1/X.12)', async () => {
    const upload = vi.fn().mockReturnValue(of(asset('asset-new-logo')));
    await render(
      { updateProfile: vi.fn().mockResolvedValue({ ...BRAND, logoAssetId: 'asset-new-logo' }) },
      new FakeCurrentLocation(),
      { upload },
    );

    const profileBlock = fixture.nativeElement.querySelectorAll('.block')[1] as HTMLElement;
    (profileBlock.querySelector('.primary') as HTMLButtonElement).click();
    fixture.detectChanges();

    const file = new File(['x'], 'logo.jpg', { type: 'image/jpeg' });
    emitFromUploader('brand-logo-uploader', 'cropped', file);
    await flushMicrotasks();
    fixture.detectChanges();

    expect(upload).toHaveBeenCalledWith('tenant-1', 'BRAND', 'brand-1', 'PUBLIC', file);
    // The upload writes the draft immediately -- visible as a preview -- and
    // the draft is what saveProfile actually sends.
    expect(
      (fixture.nativeElement.querySelector('[data-testid="brand-logo-preview"]') as HTMLImageElement)
        .src,
    ).toMatch(/^blob:/);

    findButton(profileBlock, 'Save').click();
    await flushMicrotasks();

    expect(api.updateProfile).toHaveBeenCalledWith(
      SCOPE,
      expect.objectContaining({ logoAssetId: 'asset-new-logo' }),
    );
  });

  it('uploads a banner through its own uploader independently of the logo', async () => {
    const upload = vi.fn().mockReturnValue(of(asset('asset-new-banner')));
    await render(
      { updateProfile: vi.fn().mockResolvedValue({ ...BRAND, bannerAssetId: 'asset-new-banner' }) },
      new FakeCurrentLocation(),
      { upload },
    );

    const profileBlock = fixture.nativeElement.querySelectorAll('.block')[1] as HTMLElement;
    (profileBlock.querySelector('.primary') as HTMLButtonElement).click();
    fixture.detectChanges();

    const file = new File(['x'], 'banner.jpg', { type: 'image/jpeg' });
    emitFromUploader('brand-banner-uploader', 'cropped', file);
    await flushMicrotasks();

    expect(upload).toHaveBeenCalledWith('tenant-1', 'BRAND', 'brand-1', 'PUBLIC', file);

    findButton(profileBlock, 'Save').click();
    await flushMicrotasks();

    expect(api.updateProfile).toHaveBeenCalledWith(
      SCOPE,
      expect.objectContaining({ bannerAssetId: 'asset-new-banner' }),
    );
  });

  it('surfaces a legible error when the uploader rejects a file client-side, never a network call', async () => {
    const upload = vi.fn();
    await render({}, new FakeCurrentLocation(), { upload });

    const profileBlock = fixture.nativeElement.querySelectorAll('.block')[1] as HTMLElement;
    (profileBlock.querySelector('.primary') as HTMLButtonElement).click();
    fixture.detectChanges();

    const uploaders = fixture.debugElement.queryAll(By.directive(MediaUploader));
    const logoUploader = uploaders.find(
      (debugEl) =>
        (debugEl.nativeElement as HTMLElement).getAttribute('data-testid') === 'brand-logo-uploader',
    )!;
    (logoUploader.componentInstance as MediaUploader).rejected.emit('tooLarge');
    fixture.detectChanges();

    expect(upload).not.toHaveBeenCalled();
    expect(profileBlock.querySelector('[role="alert"]')?.textContent).toContain('larger');
  });
});
