import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { describe, expect, it, vi } from 'vitest';

import { SessionContextService } from '../../core/auth/session-context.service';
import { APP_CONFIG, AppConfig } from '../../core/config/app-config';
import { ru } from '../../core/i18n/messages.ru';
import { ResidencyApi } from '../compliance/residency-api';
import { BusinessTypes } from './business-types';

const CONFIG: AppConfig = { apiBaseUrl: 'https://api.test.horecaos.uz', displayTimeZone: 'Asia/Tashkent' };

const tenant = (id: string, businessType: string) => ({
  tenantId: id, slug: id, displayName: id.toUpperCase(), status: 'ACTIVE', countryCode: 'UZ', businessType,
  defaultCurrency: 'UZS', defaultTimezone: 'Asia/Tashkent',
});

describe('BusinessTypes', () => {
  let fixture: ComponentFixture<BusinessTypes>;
  let setBusinessType: ReturnType<typeof vi.fn>;

  async function create(): Promise<void> {
    setBusinessType = vi.fn().mockResolvedValue(undefined);
    localStorage.clear();
    await TestBed.configureTestingModule({
      imports: [BusinessTypes],
      providers: [
        provideRouter([]),
        { provide: APP_CONFIG, useValue: CONFIG },
        {
          provide: ResidencyApi,
          useValue: {
            businessTypes: vi.fn().mockResolvedValue([
              { code: 'RESTAURANT', handovers: ['DELIVERY', 'PICKUP', 'DINE_IN'], kitchenDisplay: true, tenants: 1 },
              { code: 'PHARMACY', handovers: ['DELIVERY', 'PICKUP'], kitchenDisplay: false, tenants: 1 },
            ]),
            residency: vi.fn().mockResolvedValue({ hostingCountry: 'UZ', markets: [], tenants: [tenant('a', 'RESTAURANT'), tenant('b', 'PHARMACY')] }),
            setBusinessType,
          },
        },
        { provide: SessionContextService, useValue: { has: () => true, current: () => ({ subject: 'me' }) } },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(BusinessTypes);
    await settle();
  }

  async function settle(): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
    await new Promise((resolve) => setTimeout(resolve));
    fixture.detectChanges();
  }

  function el<T extends HTMLElement>(selector: string): T {
    return fixture.nativeElement.querySelector(selector) as T;
  }

  it('describes each type and filters the tenants to one', async () => {
    await create();

    expect(el('[data-type="PHARMACY"]').textContent).toContain(ru['businessTypes.noKitchen']);
    expect(fixture.nativeElement.querySelectorAll('tbody [data-tenant]')).toHaveLength(2);
    el<HTMLButtonElement>('[data-type="PHARMACY"]').click();
    await settle();
    expect(fixture.nativeElement.querySelectorAll('tbody [data-tenant]')).toHaveLength(1);
  });

  it('records a tenant’s type with a reason', async () => {
    await create();
    el<HTMLButtonElement>('[data-tenant="a"] .editToggle').click();
    await settle();
    const select = el<HTMLSelectElement>('[name="businessType"]');
    select.value = 'PHARMACY';
    select.dispatchEvent(new Event('change'));
    const reason = el<HTMLInputElement>('[name="typeReason"]');
    reason.value = 'they sell medicine';
    reason.dispatchEvent(new Event('input'));
    await settle();
    el<HTMLButtonElement>('.confirmType').click();
    await settle();

    expect(setBusinessType).toHaveBeenCalledWith('a', 'PHARMACY', 'they sell medicine');
  });
});
