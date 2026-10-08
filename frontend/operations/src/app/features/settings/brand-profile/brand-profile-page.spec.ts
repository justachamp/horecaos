import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { of, throwError } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { LocationScope } from '../../../core/api/operations-paths';
import { ApiError, ApiErrorCode } from '../../../core/api/problem-details';
import { CurrentLocation } from '../../../core/auth/current-location';
import { applyRegionalFormats, resetRegionalFormats } from '../../../core/format/regional-format';
import { RegionalFormatSync } from '../../../core/format/regional-format-sync';
import { I18n } from '../../../core/i18n/i18n';
import { seedPlatformLocalesForTesting } from '../../../core/i18n/platform-locales';
import { REGISTRY_FIXTURE } from '../../../../testing/platform-locales.fixture';
import { MediaUploader } from '../../../shared/ui/media-uploader';
import { MediaApi, MediaAssetView } from '../../catalog/media-api';
import { LocationsApi } from '../locations/locations-api';
import { BrandProfileApi, BrandView, TenantMarketView } from './brand-profile-api';
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

const TENANT_MARKET: TenantMarketView = {
  countryCode: 'UZ',
  defaultCurrency: 'UZS',
  defaultTimezone: 'Asia/Tashkent',
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
    tenantProfile: ReturnType<typeof vi.fn>;
    reviseRegionalFormats: ReturnType<typeof vi.fn>;
  };
  let locationsApi: { list: ReturnType<typeof vi.fn> };
  let regionalSync: { applySaved: ReturnType<typeof vi.fn> };
  /** The branches `LocationsApi.list` answers with; a test sets it before `render()`. */
  let branches: readonly { displayName: string; timezone: string }[] = [];

  afterEach(() => {
    branches = [];
    resetRegionalFormats();
  });
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
      tenantProfile: vi.fn().mockResolvedValue(TENANT_MARKET),
      reviseRegionalFormats: vi.fn(),
      ...overrides,
    };
    locationsApi = { list: vi.fn().mockResolvedValue(branches) };
    regionalSync = { applySaved: vi.fn() };
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
        { provide: LocationsApi, useValue: locationsApi },
        { provide: RegionalFormatSync, useValue: regionalSync },
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

  // Row 10.1: the tenant's own country/currency/timezone, read-only.

  it('shows the tenant’s country, currency and timezone read-only, sourced from the tenant not the brand', async () => {
    await render();

    expect(api.tenantProfile).toHaveBeenCalledWith('tenant-1');
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('UZ');
    expect(text).toContain('UZS');
    expect(text).toContain('Asia/Tashkent');
  });

  it('renders the rest of the profile even when the tenant-market read fails', async () => {
    await render({ tenantProfile: vi.fn().mockRejectedValue(new Error('refused')) });

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Rayhon');
    expect(fixture.nativeElement.querySelector('[data-testid="brand-profile-country"]')).toBeNull();
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

  it('offers exactly the languages the registry has live in the content tier, and none that is only declared', async () => {
    await render();
    const profileBlock = fixture.nativeElement.querySelectorAll('.block')[1] as HTMLElement;
    (profileBlock.querySelector('.primary') as HTMLButtonElement).click();
    fixture.detectChanges();

    const offered = Array.from(
      fixture.nativeElement.querySelectorAll('.locale-grid tbody tr') as NodeListOf<HTMLElement>,
    ).map((row) => row.querySelector('td:nth-child(2)')?.textContent?.trim());

    // Kazakh and Georgian are declared by the registry and live in no tier: no row, whatever the build holds.
    expect(offered).toEqual(['Russian', 'Uzbek (Latin)', 'English']);
  });

  it('gains a row for a language the registry makes live, with no change to this screen', async () => {
    seedPlatformLocalesForTesting({
      ...REGISTRY_FIXTURE,
      locales: REGISTRY_FIXTURE.locales.map((entry) =>
        entry.tag === 'kk' ? { ...entry, tiers: ['CONTENT' as const] } : entry,
      ),
    });
    try {
      await render();
      const profileBlock = fixture.nativeElement.querySelectorAll('.block')[1] as HTMLElement;
      (profileBlock.querySelector('.primary') as HTMLButtonElement).click();
      fixture.detectChanges();

      const rows = Array.from(
        fixture.nativeElement.querySelectorAll('.locale-grid tbody tr') as NodeListOf<HTMLElement>,
      ).map((row) => row.querySelector('td:nth-child(2)')?.textContent?.trim());

      // The console has no key of its own for Kazakh, so the registry's name for it, in the console's language.
      expect(rows).toEqual(['Russian', 'Uzbek (Latin)', 'English', 'Kazakh']);
    } finally {
      seedPlatformLocalesForTesting(REGISTRY_FIXTURE);
    }
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

    // The registry's content tier is [ru, uz-Latn, en], so English is the grid's third row.
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
    const downloadUrl = vi
      .fn()
      .mockImplementation((_tenantId: string, assetId: string) =>
        of(`https://cdn.example/${assetId}.jpg`),
      );
    await render(
      { getBrand: vi.fn().mockResolvedValue(BRAND_WITH_MEDIA) },
      new FakeCurrentLocation(),
      {
        downloadUrl,
      },
    );

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
      (
        fixture.nativeElement.querySelector(
          '[data-testid="brand-logo-preview"]',
        ) as HTMLImageElement
      ).src,
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
        (debugEl.nativeElement as HTMLElement).getAttribute('data-testid') ===
        'brand-logo-uploader',
    )!;
    (logoUploader.componentInstance as MediaUploader).rejected.emit('tooLarge');
    fixture.detectChanges();

    expect(upload).not.toHaveBeenCalled();
    expect(profileBlock.querySelector('[role="alert"]')?.textContent).toContain('larger');
  });
  // ------------------------------------------------ 10.12: the Formats card

  const NBSP = '\u00a0';

  const BRAND_WITH_FORMATS: BrandView = {
    ...BRAND,
    regionalFormats: {
      moneySymbolPlacement: 'BEFORE',
      moneyGrouping: 'DOT',
      phoneDisplayPattern: '+### (##) ###-##-##',
    },
  };

  function formatsBlock(): HTMLElement {
    return fixture.nativeElement.querySelector(
      '[data-testid="brand-profile-formats"]',
    ) as HTMLElement;
  }

  function pick(selector: string, value: string): void {
    const select = formatsBlock().querySelector(selector) as HTMLSelectElement;
    select.value = value;
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();
  }

  function testId(id: string): string {
    return (formatsBlock().querySelector(`[data-testid="${id}"]`)?.textContent ?? '').trim();
  }

  it('shows the defaults on the Formats card for a brand the platform sends no formats for', async () => {
    await render();

    expect(testId('formats-placement')).toContain('After the amount');
    expect(testId('formats-grouping')).toContain('Space');
    expect(testId('formats-phone')).toContain('As stored');
    expect(testId('formats-preview-total')).toBe(`146${NBSP}000${NBSP}UZS`);
    expect(testId('formats-preview-phone')).toBe('+998901234567');
  });

  it('shows the brand’s stored formats, and a preview written by the same formatters the console uses', async () => {
    await render({ getBrand: vi.fn().mockResolvedValue(BRAND_WITH_FORMATS) });

    expect(testId('formats-placement')).toContain('Before the amount');
    expect(testId('formats-grouping')).toContain('Dot');
    expect(testId('formats-phone')).toContain('+### (##) ###-##-##');
    expect(testId('formats-preview-total')).toBe(`UZS${NBSP}146.000`);
    expect(testId('formats-preview-phone')).toBe('+998 (90) 123-45-67');
  });

  it('writes the brand’s own contact phone in its pattern once one is set', async () => {
    applyRegionalFormats({ phoneDisplayPattern: '+### (##) ###-##-##' });
    await render();

    expect((fixture.nativeElement as HTMLElement).textContent).toContain('+998 (71) 200-00-00');
  });

  it('shows the timezone read-only: the tenant’s default and each branch’s own', async () => {
    branches = [
      { displayName: 'Chilonzor', timezone: 'Asia/Tashkent' },
      { displayName: 'Samarkand', timezone: 'Asia/Samarkand' },
    ];
    await render();

    const zone = testId('formats-timezone');
    expect(zone).toContain('Asia/Tashkent');
    expect(zone).toContain('Chilonzor');
    expect(zone).toContain('Asia/Samarkand');
    expect(
      formatsBlock().querySelector('select#formats-timezone, input#formats-timezone'),
    ).toBeNull();
  });

  it('previews a draft before it is saved, without touching what is stored', async () => {
    await render();
    (formatsBlock().querySelector('[data-testid="formats-edit"]') as HTMLButtonElement).click();
    fixture.detectChanges();

    pick('[data-testid="formats-placement-select"]', 'BEFORE');
    pick('[data-testid="formats-grouping-select"]', 'COMMA');
    pick('[data-testid="formats-phone-select"]', '+### (##) ###-##-##');

    expect(testId('formats-draft-preview-total')).toBe(`UZS${NBSP}146,000`);
    expect(testId('formats-draft-preview-phone')).toBe('+998 (90) 123-45-67');
    expect(api.reviseRegionalFormats).not.toHaveBeenCalled();
  });

  it('saves the formats through their own endpoint and applies them to every formatter at once', async () => {
    await render({ reviseRegionalFormats: vi.fn().mockResolvedValue(BRAND_WITH_FORMATS) });
    (formatsBlock().querySelector('[data-testid="formats-edit"]') as HTMLButtonElement).click();
    fixture.detectChanges();

    pick('[data-testid="formats-placement-select"]', 'BEFORE');
    pick('[data-testid="formats-grouping-select"]', 'DOT');
    pick('[data-testid="formats-phone-select"]', '+### (##) ###-##-##');
    (formatsBlock().querySelector('[data-testid="formats-save"]') as HTMLButtonElement).click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(api.reviseRegionalFormats).toHaveBeenCalledWith(SCOPE, {
      moneySymbolPlacement: 'BEFORE',
      moneyGrouping: 'DOT',
      phoneDisplayPattern: '+### (##) ###-##-##',
    });
    expect(regionalSync.applySaved).toHaveBeenCalledWith(SCOPE, BRAND_WITH_FORMATS.regionalFormats);
    // Back on the read-only card, now showing what was saved.
    expect(formatsBlock().querySelector('[data-testid="formats-save"]')).toBeNull();
    expect(testId('formats-preview-total')).toBe(`UZS${NBSP}146.000`);
  });

  it('leaves the pattern out of the request to clear it, and takes a custom pattern as typed', async () => {
    await render({ reviseRegionalFormats: vi.fn().mockResolvedValue(BRAND) });
    (formatsBlock().querySelector('[data-testid="formats-edit"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    (formatsBlock().querySelector('[data-testid="formats-save"]') as HTMLButtonElement).click();
    await flushMicrotasks();

    expect(api.reviseRegionalFormats).toHaveBeenLastCalledWith(SCOPE, {
      moneySymbolPlacement: 'AFTER',
      moneyGrouping: 'SPACE',
      phoneDisplayPattern: undefined,
    });

    (formatsBlock().querySelector('[data-testid="formats-edit"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    pick('[data-testid="formats-phone-select"]', '__custom__');
    const input = formatsBlock().querySelector('#formats-phone-pattern') as HTMLInputElement;
    input.value = '###-##-###-##-##';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    (formatsBlock().querySelector('[data-testid="formats-save"]') as HTMLButtonElement).click();
    await flushMicrotasks();

    expect(api.reviseRegionalFormats).toHaveBeenLastCalledWith(
      SCOPE,
      expect.objectContaining({ phoneDisplayPattern: '###-##-###-##-##' }),
    );
  });

  it('refuses a custom pattern the platform would refuse, without a request', async () => {
    await render();
    (formatsBlock().querySelector('[data-testid="formats-edit"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    pick('[data-testid="formats-phone-select"]', '__custom__');
    const input = formatsBlock().querySelector('#formats-phone-pattern') as HTMLInputElement;
    input.value = '+## ##';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    (formatsBlock().querySelector('[data-testid="formats-save"]') as HTMLButtonElement).click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(api.reviseRegionalFormats).not.toHaveBeenCalled();
    expect(testId('formats-error')).toContain('between 7 and 15');
    expect(formatsBlock().querySelector('[data-testid="formats-save"]')).not.toBeNull();
  });

  it('stays editable, with the reason, when the platform refuses the save', async () => {
    await render({
      reviseRegionalFormats: vi
        .fn()
        .mockRejectedValue(new ApiError(ApiErrorCode.VALIDATION_FAILED, 400, null, null)),
    });
    (formatsBlock().querySelector('[data-testid="formats-edit"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    (formatsBlock().querySelector('[data-testid="formats-save"]') as HTMLButtonElement).click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(formatsBlock().querySelector('[data-testid="formats-error"]')).not.toBeNull();
    expect(regionalSync.applySaved).not.toHaveBeenCalled();
    expect(formatsBlock().querySelector('[data-testid="formats-save"]')).not.toBeNull();
  });
});
