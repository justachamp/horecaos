import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';

import { ApiClient } from '../../core/api/api-client';
import { SessionContextService } from '../../core/auth/session-context.service';
import { APP_CONFIG, AppConfig } from '../../core/config/app-config';
import { ru } from '../../core/i18n/messages.ru';
import { GlobalLookup, LookupHit } from './global-lookup';

const CONFIG: AppConfig = { apiBaseUrl: 'https://api.test.horecaos.uz', displayTimeZone: 'Asia/Tashkent' };

describe('GlobalLookup', () => {
  let fixture: ComponentFixture<GlobalLookup>;
  let get: ReturnType<typeof vi.fn>;
  let post: ReturnType<typeof vi.fn>;

  async function create(query: string | null, hits: LookupHit[], mayReveal = false): Promise<void> {
    get = vi.fn().mockReturnValue(of(hits));
    post = vi.fn().mockReturnValue(
      of({ tenantsSearched: 3, matches: [{ tenantId: 'tenant-2', tenantName: 'Somsa', accountId: 'acc-9' }] }),
    );
    await TestBed.configureTestingModule({
      imports: [GlobalLookup],
      providers: [
        provideRouter([]),
        { provide: APP_CONFIG, useValue: CONFIG },
        { provide: ApiClient, useValue: { get, post } },
        { provide: SessionContextService, useValue: { has: () => mayReveal, current: () => ({ subject: 'me' }) } },
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { queryParamMap: convertToParamMap(query === null ? {} : { q: query }) } },
        },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(GlobalLookup);
    fixture.detectChanges();
    await fixture.whenStable();
    await new Promise((resolve) => setTimeout(resolve));
    fixture.detectChanges();
  }

  it('runs a lookup from a shared link and names each hit, linking to its tenant', async () => {
    await create('1042', [
      {
        type: 'ORDER',
        id: 'order-1',
        tenantId: 'tenant-1',
        tenantName: 'Oshxona',
        matchedOn: 'ORDER_NUMBER',
        label: '№ 1042 · Chilonzor · COMPLETED',
      },
      { type: 'COURIER', id: 'courier-1', tenantId: 'tenant-2', tenantName: 'Somsa', matchedOn: 'COURIER_REFERENCE', label: 'C-1042 · ACTIVE' },
    ]);

    expect(get).toHaveBeenCalledWith('/api/v1/control-plane/lookup', { query: { q: '1042' } });
    const rows = Array.from(fixture.nativeElement.querySelectorAll('.hits tbody tr')) as HTMLElement[];
    expect(rows[0].textContent).toContain(ru['globalLookup.type.ORDER']);
    expect(rows[0].textContent).toContain(ru['globalLookup.matched.ORDER_NUMBER']);
    expect((rows[0].querySelector('a') as HTMLAnchorElement).getAttribute('href')).toBe('/tenants/tenant-1');
    expect(rows[1].textContent).toContain(ru['globalLookup.type.COURIER']);
  });

  it('says plainly when nothing matches', async () => {
    await create('nothing-here', []);

    expect(fixture.nativeElement.textContent).toContain(ru['globalLookup.notFound']);
  });

  it('finds a customer by phone with the number in the body, never the address, and only for those who may', async () => {
    await create(null, [], true);
    const phone = fixture.nativeElement.querySelector('[name="phone"]') as HTMLInputElement;
    phone.value = '+998 90 123 45 67';
    phone.dispatchEvent(new Event('input'));
    const reason = fixture.nativeElement.querySelector('[name="phoneReason"]') as HTMLInputElement;
    reason.value = 'caller asked about a refund';
    reason.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    (fixture.nativeElement.querySelector('.phoneForm button[type="submit"]') as HTMLButtonElement).click();
    await fixture.whenStable();
    await new Promise((resolve) => setTimeout(resolve));
    fixture.detectChanges();

    expect(post).toHaveBeenCalledWith('/api/v1/control-plane/customer-lookups', {
      phone: '+998 90 123 45 67',
      reason: 'caller asked about a refund',
    });
    expect(get).not.toHaveBeenCalled();
    expect((fixture.nativeElement.querySelector('[data-account="acc-9"]') as HTMLElement).textContent).toContain('Somsa');

    TestBed.resetTestingModule();
    await create(null, [], false);
    expect(fixture.nativeElement.querySelector('.phoneLookup')).toBeNull();
  });
});
