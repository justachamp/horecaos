import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';

import { ApiClient } from '../../core/api/api-client';
import { APP_CONFIG, AppConfig } from '../../core/config/app-config';
import { ru } from '../../core/i18n/messages.ru';
import { GlobalLookup, LookupHit } from './global-lookup';

const CONFIG: AppConfig = { apiBaseUrl: 'https://api.test.horecaos.uz', displayTimeZone: 'Asia/Tashkent' };

describe('GlobalLookup', () => {
  let fixture: ComponentFixture<GlobalLookup>;
  let get: ReturnType<typeof vi.fn>;

  async function create(query: string | null, hits: LookupHit[]): Promise<void> {
    get = vi.fn().mockReturnValue(of(hits));
    await TestBed.configureTestingModule({
      imports: [GlobalLookup],
      providers: [
        provideRouter([]),
        { provide: APP_CONFIG, useValue: CONFIG },
        { provide: ApiClient, useValue: { get } },
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
});
