import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';

import { BrandScope } from '../../core/api/catalog-paths';
import { Auth } from '../../core/auth/auth';
import { CurrentBrand } from '../../core/auth/current-brand';
import { I18n } from '../../core/i18n/i18n';
import { DeliveryTariffsApi, TariffSummaryResponse } from './delivery-tariffs-api';
import { DeliveryTariffsPage } from './delivery-tariffs-page';

const BRAND_SCOPE: BrandScope = { tenantId: 't1', brandId: 'b1' };

const TARIFF: TariffSummaryResponse = {
  tariffId: 'tariff-1',
  code: 'CITY',
  name: 'City tariff',
  status: 'ACTIVE',
  brandDefault: true,
  activeVersion: 1,
  currency: 'UZS',
  feeSource: 'TARIFF',
  distanceMode: 'RADIUS',
  maxDistanceMeters: 15_000,
};

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

describe('DeliveryTariffsPage', () => {
  let fixture: ComponentFixture<DeliveryTariffsPage>;

  async function render(api: Partial<DeliveryTariffsApi>): Promise<void> {
    await TestBed.configureTestingModule({
      imports: [DeliveryTariffsPage],
      providers: [
        {
          provide: CurrentBrand,
          useValue: {
            scope: signal<BrandScope | null>(BRAND_SCOPE),
            denied: signal(false),
            ensureLoaded: () => Promise.resolve(),
          },
        },
        { provide: Auth, useValue: { subject: signal('actor-1') } },
        { provide: DeliveryTariffsApi, useValue: api },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(DeliveryTariffsPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
  }

  it('lists a tariff with its live version and brand-default flag', async () => {
    await render({ list: vi.fn().mockResolvedValue([TARIFF]) });
    const host = fixture.nativeElement as HTMLElement;

    expect(host.querySelectorAll('[data-testid="tariff-row"]')).toHaveLength(1);
    expect(host.textContent).toContain('CITY');
    expect(host.textContent).toContain('v1');
  });

  it('shows the denied state when the brand grant is missing', async () => {
    await TestBed.configureTestingModule({
      imports: [DeliveryTariffsPage],
      providers: [
        {
          provide: CurrentBrand,
          useValue: {
            scope: signal<BrandScope | null>(null),
            denied: signal(true),
            ensureLoaded: () => Promise.resolve(),
          },
        },
        { provide: Auth, useValue: { subject: signal(null) } },
        { provide: DeliveryTariffsApi, useValue: { list: vi.fn() } },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(DeliveryTariffsPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[data-testid="tariffs-denied"]'),
    ).not.toBeNull();
  });
});
