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

describe('BrandProfilePage', () => {
  async function render(api: Partial<BrandProfileApi>, location = new FakeCurrentLocation()) {
    await TestBed.configureTestingModule({
      imports: [BrandProfilePage],
      providers: [
        { provide: BrandProfileApi, useValue: api },
        { provide: CurrentLocation, useValue: location },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    const fixture: ComponentFixture<BrandProfilePage> = TestBed.createComponent(BrandProfilePage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
    return fixture;
  }

  it('renders the brand fields the operations surface actually returns', async () => {
    const fixture = await render({ getBrand: vi.fn().mockResolvedValue(BRAND) });
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Rayhon');
    expect(text).toContain('RAYHON');
    expect(text).toContain('rayhon');
  });

  it('names the fields that have no backend yet rather than rendering dead inputs', async () => {
    const fixture = await render({ getBrand: vi.fn().mockResolvedValue(BRAND) });
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('operations-spec/settings.md');
  });

  it('shows the denied state without a location in scope', async () => {
    const location = new FakeCurrentLocation();
    location.scope.set(null);
    location.denied.set(true);
    const getBrand = vi.fn();
    const fixture = await render({ getBrand }, location);

    expect((fixture.nativeElement as HTMLElement).textContent).toContain('No location in scope');
    expect(getBrand).not.toHaveBeenCalled();
  });

  it('shows a load error for a non-403 failure', async () => {
    const fixture = await render({
      getBrand: vi
        .fn()
        .mockRejectedValue(new ApiError(ApiErrorCode.INTERNAL_ERROR, 500, null, null)),
    });
    expect(fixture.nativeElement.querySelector('[role="alert"]')).toBeTruthy();
  });
});
