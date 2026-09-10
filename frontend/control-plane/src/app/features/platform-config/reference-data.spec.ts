import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';

import { ApiClient } from '../../core/api/api-client';
import { SessionContextService } from '../../core/auth/session-context.service';
import { APP_CONFIG, AppConfig } from '../../core/config/app-config';
import { ReferenceDataScreen } from './reference-data';

const CONFIG: AppConfig = { apiBaseUrl: 'https://api.test.horecaos.uz', displayTimeZone: 'Asia/Tashkent' };

const DATA = {
  countries: [{ code: 'UZ', name: 'Uzbekistan', defaultCurrency: 'UZS', defaultTimezone: 'Asia/Tashkent' }],
  locales: [{ code: 'ru', displayName: 'Русский' }],
  holidays: [
    { holidayId: 'h1', countryCode: 'UZ', name: 'Navruz', month: 3, day: 21, date: null },
    { holidayId: 'h2', countryCode: 'UZ', name: 'Ramazon Hayit', month: null, day: null, date: '2027-03-10' },
  ],
};
const SLA = {
  version: 1,
  buckets: [
    { code: 'UNDER_30', fromMinutes: 0, toMinutesExclusive: 30 },
    { code: 'OVER_60', fromMinutes: 60, toMinutesExclusive: null },
  ],
};

describe('ReferenceDataScreen', () => {
  let fixture: ComponentFixture<ReferenceDataScreen>;
  let post: ReturnType<typeof vi.fn>;
  let del: ReturnType<typeof vi.fn>;

  async function create(): Promise<void> {
    post = vi.fn().mockReturnValue(of({ holidayId: 'h3' }));
    del = vi.fn().mockReturnValue(of(undefined));
    await TestBed.configureTestingModule({
      imports: [ReferenceDataScreen],
      providers: [
        { provide: APP_CONFIG, useValue: CONFIG },
        {
          provide: ApiClient,
          useValue: {
            get: vi.fn((path: string) => of(path.endsWith('sla-buckets') ? SLA : DATA)),
            post,
            delete: del,
          },
        },
        { provide: SessionContextService, useValue: { has: () => true, current: () => ({ subject: 'me' }) } },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(ReferenceDataScreen);
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

  it('shows a recurring holiday by day and month and a dated one with its year', async () => {
    await create();

    expect(el('[data-holiday="Navruz"]').textContent).toContain('21.03');
    expect(el('[data-holiday="Ramazon Hayit"]').textContent).toContain('10.03.2027');
    expect(el('[data-bucket="OVER_60"]').textContent).toContain('60');
  });

  it('adds a recurring holiday typed as day and month', async () => {
    await create();
    el<HTMLButtonElement>('.addToggle').click();
    await settle();
    await set('[name="holidayName"]', 'Constitution Day');
    await set('[name="holidayMonthDay"]', '8.12');
    await set('[name="holidayReason"]', 'by law');
    el<HTMLButtonElement>('.holidayForm button[type="submit"]').click();
    await settle();

    expect(post).toHaveBeenCalledWith(
      '/api/v1/control-plane/reference-data/holidays',
      expect.objectContaining({ countryCode: 'UZ', name: 'Constitution Day', month: 12, day: 8 }),
    );
  });

  it('removes a holiday with a reason', async () => {
    await create();
    el<HTMLButtonElement>('[data-holiday="Ramazon Hayit"] .removeToggle').click();
    await settle();
    await set('[name="removeReason"]', 'moved a day');
    el<HTMLButtonElement>('.confirmRemove').click();
    await settle();

    expect(del).toHaveBeenCalledWith('/api/v1/control-plane/reference-data/holidays/h2', { reason: 'moved a day' });
  });
});
