import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { describe, expect, it, vi } from 'vitest';

import { SessionContextService } from '../../core/auth/session-context.service';
import { APP_CONFIG, AppConfig } from '../../core/config/app-config';
import { ru } from '../../core/i18n/messages.ru';
import { NotificationProviders } from './notification-providers';
import { NotificationProvidersApi, TemplateReview } from './notification-providers-api';

const CONFIG: AppConfig = { apiBaseUrl: 'https://api.test.horecaos.uz', displayTimeZone: 'Asia/Tashkent' };

const PENDING: TemplateReview = {
  versionId: 'v-1', tenantId: 'tenant-1', tenantName: 'Non uyi', templateKey: 'ORDER_CONFIRMED', versionNumber: 2,
  locale: 'ru', status: 'ACTIVE', body: 'Заказ {number} принят', providerReview: 'PENDING', reference: null,
  providerNote: null, updatedBy: 'desk', updatedAt: '2026-09-11T08:00:00Z',
};

describe('NotificationProviders', () => {
  let fixture: ComponentFixture<NotificationProviders>;
  let api: { registry: ReturnType<typeof vi.fn>; reviews: ReturnType<typeof vi.fn>; record: ReturnType<typeof vi.fn> };

  async function create(): Promise<void> {
    api = {
      registry: vi.fn().mockResolvedValue({
        gateways: [{ code: 'smsgw-vas-production', providerType: 'SMSGW_VAS', production: true, notes: null }],
        senders: [{ installationId: 'i-1', tenantId: 'tenant-1', tenantName: 'Non uyi', providerType: 'SMSGW_VAS', environmentCode: 'smsgw-vas-production', status: 'ACTIVE', sender: 'NONUYI', brandSenders: 1 }],
      }),
      reviews: vi.fn().mockResolvedValue([PENDING]),
      record: vi.fn().mockResolvedValue(undefined),
    };
    await TestBed.configureTestingModule({
      imports: [NotificationProviders],
      providers: [
        provideRouter([]),
        { provide: APP_CONFIG, useValue: CONFIG },
        { provide: NotificationProvidersApi, useValue: api },
        { provide: SessionContextService, useValue: { has: () => true, current: () => ({ subject: 'me' }) } },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(NotificationProviders);
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

  it('lists the gateways, who sends as what, and the wordings waiting first', async () => {
    await create();

    expect(el('[data-gateway="smsgw-vas-production"]').textContent).toContain(ru['notificationProviders.live']);
    expect(el('[data-sender="i-1"]').textContent).toContain('NONUYI');
    expect(api.reviews).toHaveBeenCalledWith('PENDING');
    expect(el('[data-review="v-1"]').textContent).toContain(ru['notificationProviders.review.PENDING']);
  });

  it('records an approval only with the gateway’s reference', async () => {
    await create();
    el<HTMLButtonElement>('[data-review="v-1"] .recordToggle').click();
    await settle();
    await set('[name="reviewReason"]', 'gateway approved it');
    expect(el<HTMLButtonElement>('.confirmReview').disabled).toBe(true);
    await set('[name="reviewReference"]', 'VAS-9921');
    el<HTMLButtonElement>('.confirmReview').click();
    await settle();

    expect(api.record).toHaveBeenCalledWith('tenant-1', 'v-1', {
      state: 'APPROVED',
      reference: 'VAS-9921',
      providerNote: undefined,
      reason: 'gateway approved it',
    });
  });

  it('asks again for every wording when the filter is cleared', async () => {
    await create();
    el<HTMLButtonElement>('[data-filter="ALL"]').click();
    await settle();

    expect(api.reviews).toHaveBeenLastCalledWith(null);
  });
});
