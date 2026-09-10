import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { describe, expect, it, vi } from 'vitest';

import { SessionContextService } from '../../core/auth/session-context.service';
import { APP_CONFIG, AppConfig } from '../../core/config/app-config';
import { ru } from '../../core/i18n/messages.ru';
import { ResidencyApi, ResidencyView } from './residency-api';
import { ResidencyHosting } from './residency-hosting';

const CONFIG: AppConfig = { apiBaseUrl: 'https://api.test.horecaos.uz', displayTimeZone: 'Asia/Tashkent' };

const VIEW: ResidencyView = {
  hostingCountry: 'UZ',
  markets: [
    { code: 'UZ', name: 'Uzbekistan', defaultCurrency: 'UZS', defaultTimezone: 'Asia/Tashkent' },
    { code: 'KZ', name: 'Kazakhstan', defaultCurrency: 'KZT', defaultTimezone: 'Asia/Almaty' },
  ],
  tenants: [
    { tenantId: 'tenant-1', slug: 'non', displayName: 'Non uyi', status: 'ACTIVE', countryCode: 'UZ', businessType: 'BAKERY', defaultCurrency: 'UZS', defaultTimezone: 'Asia/Tashkent' },
  ],
};

describe('ResidencyHosting', () => {
  let fixture: ComponentFixture<ResidencyHosting>;
  let api: { residency: ReturnType<typeof vi.fn>; changeCountry: ReturnType<typeof vi.fn> };

  async function create(status = 'AWAITING_APPROVAL'): Promise<void> {
    api = {
      residency: vi.fn().mockResolvedValue(VIEW),
      changeCountry: vi.fn().mockResolvedValue({ status, approvalRequestId: 'req-1' }),
    };
    localStorage.clear();
    await TestBed.configureTestingModule({
      imports: [ResidencyHosting],
      providers: [
        provideRouter([]),
        { provide: APP_CONFIG, useValue: CONFIG },
        { provide: ResidencyApi, useValue: api },
        { provide: SessionContextService, useValue: { has: () => true, current: () => ({ subject: 'me' }) } },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(ResidencyHosting);
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

  async function set(selector: string, value: string, event = 'input'): Promise<void> {
    const input = el<HTMLInputElement>(selector);
    input.value = value;
    input.dispatchEvent(new Event(event));
    await settle();
  }

  it('says where all data is hosted and counts tenants per market', async () => {
    await create();

    expect(el('.hosting').textContent).toContain('Uzbekistan');
    expect(el('[data-market="UZ"]').textContent).toContain('1');
    expect(el('[data-tenant="tenant-1"]').textContent).toContain(ru['businessTypes.type.BAKERY']);
  });

  it('asks for a second signature to move a tenant and says it is waiting', async () => {
    await create();
    el<HTMLButtonElement>('.changeToggle').click();
    await settle();

    expect(el('[name="country"]').textContent).not.toContain('Uzbekistan');
    await set('[name="country"]', 'KZ', 'change');
    await set('[name="countryReason"]', 'opening in Almaty');
    el<HTMLButtonElement>('.confirmChange').click();
    await settle();

    expect(api.changeCountry).toHaveBeenCalledWith('tenant-1', 'KZ', 'opening in Almaty');
    expect(fixture.nativeElement.textContent).toContain('Kazakhstan');
    expect(api.residency).toHaveBeenCalledTimes(1);
  });

  it('reloads once the change is made', async () => {
    await create('CHANGED');
    el<HTMLButtonElement>('.changeToggle').click();
    await settle();
    await set('[name="country"]', 'KZ', 'change');
    await set('[name="countryReason"]', 'approved');
    el<HTMLButtonElement>('.confirmChange').click();
    await settle();

    expect(api.residency).toHaveBeenCalledTimes(2);
  });
});
