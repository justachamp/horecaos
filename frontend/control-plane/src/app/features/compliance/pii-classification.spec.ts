import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { describe, expect, it, vi } from 'vitest';

import { APP_CONFIG, AppConfig } from '../../core/config/app-config';
import { ru } from '../../core/i18n/messages.ru';
import { TenantsApi } from '../tenants/tenants-api';
import { DataProtection, DataProtectionApi } from './data-protection-api';
import { PiiClassification } from './pii-classification';

const CONFIG: AppConfig = { apiBaseUrl: 'https://api.test.horecaos.uz', displayTimeZone: 'Asia/Tashkent' };

const DATA: DataProtection = {
  classes: [
    { code: 'INTERNAL', requiresEncryption: false, mayLeaveTheDatabase: true },
    { code: 'PERSONAL', requiresEncryption: true, mayLeaveTheDatabase: false },
  ],
  encryptedColumns: [
    { schema: 'customer', table: 'addresses', column: 'delivery_instructions_encrypted' },
    { schema: 'ordering', table: 'orders', column: 'customer_note_encrypted' },
  ],
  retention: [
    { code: 'COURIER_TRACKS', keptFor: '30 days', enforcedBy: 'uz.horecaos.platform.telemetry.infrastructure.persistence.TrackRetentionSweeper' },
  ],
  erasure: {
    pending: 1, completed: 4, cancelled: 0,
    waiting: [{ requestId: 'er-1', tenantId: 'tenant-1', requestedVia: 'STOREFRONT', requestedAt: '2026-08-01T00:00:00Z', daysWaiting: 41 }],
  },
  egressLast30Days: [{ actionCode: 'customer.contact.revealed', count: 7 }],
};

describe('PiiClassification', () => {
  let fixture: ComponentFixture<PiiClassification>;

  async function create(): Promise<void> {
    localStorage.clear();
    await TestBed.configureTestingModule({
      imports: [PiiClassification],
      providers: [
        provideRouter([]),
        { provide: APP_CONFIG, useValue: CONFIG },
        { provide: DataProtectionApi, useValue: { overview: vi.fn().mockResolvedValue(DATA) } },
        {
          provide: TenantsApi,
          useValue: { listTenants: vi.fn().mockResolvedValue({ items: [{ id: 'tenant-1', displayName: 'Non uyi', slug: 'non' }], nextCursor: null }) },
        },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(PiiClassification);
    fixture.detectChanges();
    await fixture.whenStable();
    await new Promise((resolve) => setTimeout(resolve));
    fixture.detectChanges();
  }

  function el(selector: string): HTMLElement {
    return fixture.nativeElement.querySelector(selector) as HTMLElement;
  }

  it('says what each class requires and which job deletes each kind of data', async () => {
    await create();

    expect(el('[data-class="PERSONAL"]').textContent).toContain(ru['piiClassification.staysIn']);
    expect(el('[data-rule="COURIER_TRACKS"]').textContent).toContain('TrackRetentionSweeper');
    expect(el('[data-rule="COURIER_TRACKS"]').textContent).not.toContain('uz.horecaos');
  });

  it('shows erasure requests by tenant, a late one marked, and reveals by kind', async () => {
    await create();

    expect(el('[data-erasure="er-1"]').textContent).toContain('Non uyi');
    expect(el('[data-erasure="er-1"] .late')).not.toBeNull();
    expect(el('[data-egress="customer.contact.revealed"]').textContent).toContain('7');
    expect(fixture.nativeElement.textContent).toContain('customer.addresses');
  });
});
