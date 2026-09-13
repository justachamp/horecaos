import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { LocationScope } from '../../../core/api/operations-paths';
import { ApiError, ApiErrorCode } from '../../../core/api/problem-details';
import { CurrentLocation } from '../../../core/auth/current-location';
import { I18n } from '../../../core/i18n/i18n';
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

  async function render(
    overrides: Partial<typeof api> = {},
    location: FakeCurrentLocation = new FakeCurrentLocation(),
  ) {
    api = {
      getBrand: vi.fn().mockResolvedValue(BRAND),
      reviseBrand: vi.fn(),
      updateProfile: vi.fn(),
      ...overrides,
    };
    await TestBed.configureTestingModule({
      imports: [BrandProfilePage],
      providers: [
        { provide: BrandProfileApi, useValue: api },
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
});
