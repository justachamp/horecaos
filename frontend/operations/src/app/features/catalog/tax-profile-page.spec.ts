import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';

import { BrandScope } from '../../core/api/catalog-paths';
import { CurrentBrand } from '../../core/auth/current-brand';
import { I18n } from '../../core/i18n/i18n';
import { PricingApi } from './pricing-api';
import { TaxProfilePage } from './tax-profile-page';

const SCOPE: BrandScope = { tenantId: 't1', brandId: 'b1' };

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

describe('TaxProfilePage', () => {
  let fixture: ComponentFixture<TaxProfilePage>;

  async function render(scope: BrandScope | null, api: Partial<PricingApi>): Promise<void> {
    const merged: Partial<PricingApi> = {
      taxProfiles: vi.fn().mockReturnValue(of([])),
      taxProfile: vi.fn().mockReturnValue(of(null)),
      ...api,
    };
    await TestBed.configureTestingModule({
      imports: [TaxProfilePage],
      providers: [
        {
          provide: CurrentBrand,
          useValue: {
            scope: signal<BrandScope | null>(scope),
            denied: signal(scope === null),
            ensureLoaded: () => Promise.resolve(),
          },
        },
        { provide: PricingApi, useValue: merged },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(TaxProfilePage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
  }

  it('submits the jurisdiction, mode and basis-point rate, and shows what was saved', async () => {
    const setTaxProfile = vi.fn().mockReturnValue(
      of({
        taxProfileId: 'tp-1',
        jurisdictionCode: 'UZ',
        mode: 'INCLUSIVE',
        rateBasisPoints: 1200,
        validFrom: new Date().toISOString(),
        version: 1,
      }),
    );
    await render(SCOPE, { setTaxProfile });

    const host = fixture.nativeElement as HTMLElement;
    (host.querySelector('[data-testid="tax-profile-submit"]') as HTMLButtonElement).click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(setTaxProfile).toHaveBeenCalledWith(SCOPE, 'UZ', {
      mode: 'INCLUSIVE',
      rateBasisPoints: 1200,
    });
    expect(host.querySelector('[data-testid="tax-profile-result"]')?.textContent).toContain('UZ');
  });

  it('switches to EXCLUSIVE mode before submitting', async () => {
    const setTaxProfile = vi.fn().mockReturnValue(
      of({
        taxProfileId: 'tp-1',
        jurisdictionCode: 'UZ',
        mode: 'EXCLUSIVE',
        rateBasisPoints: 1200,
        validFrom: new Date().toISOString(),
        version: 1,
      }),
    );
    await render(SCOPE, { setTaxProfile });

    const host = fixture.nativeElement as HTMLElement;
    (host.querySelector('[data-testid="tax-profile-mode-exclusive"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    (host.querySelector('[data-testid="tax-profile-submit"]') as HTMLButtonElement).click();
    await flushMicrotasks();

    expect(setTaxProfile).toHaveBeenCalledWith(
      SCOPE,
      'UZ',
      expect.objectContaining({ mode: 'EXCLUSIVE' }),
    );
  });

  it('shows the denied state with no brand scope', async () => {
    await render(null, { setTaxProfile: vi.fn() });
    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelector('[data-testid="tax-profile-denied"]')).not.toBeNull();
  });

  // Row 4.8a: the screen used to be write-only. These two prove the read
  // side actually renders before an operator can overwrite a rate blind.

  it('lists every jurisdiction already in force, and shows what is in force for the default jurisdiction', async () => {
    const uzProfile = {
      taxProfileId: 'tp-1',
      jurisdictionCode: 'UZ',
      mode: 'INCLUSIVE' as const,
      rateBasisPoints: 1200,
      validFrom: new Date().toISOString(),
      version: 3,
    };
    const taxProfiles = vi.fn().mockReturnValue(of([uzProfile]));
    const taxProfile = vi.fn().mockReturnValue(of(uzProfile));
    await render(SCOPE, { setTaxProfile: vi.fn(), taxProfiles, taxProfile });

    const host = fixture.nativeElement as HTMLElement;
    expect(taxProfiles).toHaveBeenCalledWith(SCOPE);
    expect(taxProfile).toHaveBeenCalledWith(SCOPE, 'UZ');
    expect(host.querySelector('[data-testid="tax-profile-existing-UZ"]')?.textContent).toContain('UZ');
    expect(host.querySelector('[data-testid="tax-profile-in-force"]')?.textContent).toContain('UZ');
    expect(host.querySelector('[data-testid="tax-profile-none-yet"]')).toBeNull();
  });

  it('shows no-profile-yet for a jurisdiction the brand has never set', async () => {
    const taxProfiles = vi.fn().mockReturnValue(of([]));
    const taxProfile = vi.fn().mockReturnValue(of(null));
    await render(SCOPE, { setTaxProfile: vi.fn(), taxProfiles, taxProfile });

    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelector('[data-testid="tax-profile-none-yet"]')).not.toBeNull();
    expect(host.querySelector('[data-testid="tax-profile-in-force-none"]')?.textContent).toContain('UZ');
  });

  it('loads a different jurisdiction’s in-force rate into the form when it is selected from the list', async () => {
    const uzProfile = {
      taxProfileId: 'tp-1',
      jurisdictionCode: 'RU',
      mode: 'EXCLUSIVE' as const,
      rateBasisPoints: 2000,
      validFrom: new Date().toISOString(),
      version: 1,
    };
    const taxProfiles = vi.fn().mockReturnValue(of([uzProfile]));
    const taxProfile = vi.fn().mockReturnValue(of(null)).mockReturnValueOnce(of(null)).mockReturnValueOnce(of(uzProfile));
    await render(SCOPE, { setTaxProfile: vi.fn(), taxProfiles, taxProfile });

    const host = fixture.nativeElement as HTMLElement;
    (host.querySelector('[data-testid="tax-profile-existing-RU"]') as HTMLButtonElement).click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(taxProfile).toHaveBeenLastCalledWith(SCOPE, 'RU');
    expect(host.querySelector('[data-testid="tax-profile-mode-exclusive"]')?.className).toContain(
      'tax-profile__mode-btn--active',
    );
  });
});
