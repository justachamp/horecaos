import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { describe, expect, it, vi } from 'vitest';

import { APP_CONFIG, AppConfig } from '../../core/config/app-config';
import { ru } from '../../core/i18n/messages.ru';
import { IntegrationOpsApi, WebhookDelivery } from './integration-ops-api';
import { WebhookDeliveries } from './webhook-deliveries';

const CONFIG: AppConfig = { apiBaseUrl: 'https://api.test.horecaos.uz', displayTimeZone: 'Asia/Tashkent' };

const GOOD: WebhookDelivery = {
  id: 'cb-1', tenantId: 'tenant-1', tenantName: 'Oshxona', provider: 'CLICK', kind: 'COMPLETE',
  providerReference: 'click-778', signatureValid: true, responseCode: '0', receivedAt: '2026-09-10T08:00:00Z', matchedPayment: true,
};
const BAD: WebhookDelivery = { ...GOOD, id: 'cb-2', providerReference: 'click-779', signatureValid: false, responseCode: '-1', matchedPayment: false };

describe('WebhookDeliveries', () => {
  let fixture: ComponentFixture<WebhookDeliveries>;
  let webhooks: ReturnType<typeof vi.fn>;

  async function create(rows: WebhookDelivery[] = [GOOD, BAD]): Promise<void> {
    webhooks = vi.fn().mockResolvedValue(rows);
    localStorage.clear();
    sessionStorage.clear();
    await TestBed.configureTestingModule({
      imports: [WebhookDeliveries],
      providers: [
        provideRouter([]),
        { provide: APP_CONFIG, useValue: CONFIG },
        { provide: IntegrationOpsApi, useValue: { webhooks } },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(WebhookDeliveries);
    await settle();
  }

  async function settle(): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
    await new Promise((resolve) => setTimeout(resolve));
    fixture.detectChanges();
  }

  it('shows each call with its reference, our answer, and a bad signature marked', async () => {
    await create();
    const rows = Array.from(fixture.nativeElement.querySelectorAll('tbody tr')) as HTMLElement[];

    expect(rows).toHaveLength(2);
    expect(rows[0].textContent).toContain('click-778');
    expect(rows[0].classList.contains('invalid')).toBe(false);
    expect(rows[1].classList.contains('invalid')).toBe(true);
    expect(rows[1].textContent).toContain(ru['webhookDeliveries.signature.invalid']);
    expect(rows[1].textContent).toContain(ru['webhookDeliveries.unmatched']);
    expect(fixture.nativeElement.querySelector('.warning')).not.toBeNull();
  });

  it('asks again for one provider and for bad signatures only', async () => {
    await create();
    const select = fixture.nativeElement.querySelector('[name="provider"]') as HTMLSelectElement;
    select.value = 'PAYME';
    select.dispatchEvent(new Event('change'));
    await settle();
    expect(webhooks).toHaveBeenLastCalledWith('PAYME', false);

    const box = fixture.nativeElement.querySelector('[name="invalidOnly"]') as HTMLInputElement;
    box.checked = true;
    box.dispatchEvent(new Event('change'));
    await settle();
    expect(webhooks).toHaveBeenLastCalledWith('PAYME', true);
  });

  it('asks for every provider by default', async () => {
    await create([]);

    expect(webhooks).toHaveBeenCalledWith(null, false);
    expect(fixture.nativeElement.textContent).toContain(ru['webhookDeliveries.empty']);
  });
});
