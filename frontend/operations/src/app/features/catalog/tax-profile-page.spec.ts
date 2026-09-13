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
        { provide: PricingApi, useValue: api },
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
});
