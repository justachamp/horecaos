import { ComponentFixture, TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';

import { APP_CONFIG, AppConfig } from '../../core/config/app-config';
import { ru } from '../../core/i18n/messages.ru';
import { TenantsApi } from '../tenants/tenants-api';
import { ConfigurationApi, FeatureFlagView } from './configuration-api';
import { FeatureFlags } from './feature-flags';

const CONFIG: AppConfig = { apiBaseUrl: 'https://api.test.horecaos.uz', displayTimeZone: 'Asia/Tashkent' };

const flag = (overrides: Partial<FeatureFlagView>): FeatureFlagView => ({
  code: 'feature.support_visits',
  description: 'server words',
  defaultValue: false,
  platformValue: null,
  platformVersion: null,
  tenants: [],
  ...overrides,
});

describe('FeatureFlags', () => {
  let fixture: ComponentFixture<FeatureFlags>;
  let api: { featureFlags: ReturnType<typeof vi.fn>; setValue: ReturnType<typeof vi.fn> };

  async function create(flags: FeatureFlagView[]): Promise<void> {
    api = { featureFlags: vi.fn().mockResolvedValue(flags), setValue: vi.fn().mockResolvedValue({}) };
    await TestBed.configureTestingModule({
      imports: [FeatureFlags],
      providers: [
        { provide: APP_CONFIG, useValue: CONFIG },
        { provide: ConfigurationApi, useValue: api },
        {
          provide: TenantsApi,
          useValue: {
            listTenants: vi.fn().mockResolvedValue({
              items: [{ id: 'tenant-1', slug: 'oshxona', displayName: 'Oshxona' }],
              nextCursor: null,
            }),
          },
        },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(FeatureFlags);
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

  async function confirmWith(reason: string): Promise<void> {
    const input = el<HTMLInputElement>('input[name="flagReason"]');
    input.value = reason;
    input.dispatchEvent(new Event('input'));
    await settle();
    el<HTMLButtonElement>('.confirmChange').click();
    await settle();
  }

  it('names a known flag in the operator’s words and says where it is on', async () => {
    await create([flag({ tenants: [{ tenantId: 'tenant-1', tenantName: 'Oshxona', value: true, version: 0 }] })]);

    const text = el('.flag').textContent ?? '';
    expect(text).toContain(ru['featureFlags.flag.supportVisits']);
    expect(text).toContain(ru['featureFlags.summary.some'].replace('{count}', '1'));
    expect(text).toContain('Oshxona');
  });

  it('turns a flag on for everyone through the ordinary setting write, with the version it read', async () => {
    await create([flag({ platformValue: false, platformVersion: 3 })]);

    el<HTMLButtonElement>('.platformToggle').click();
    await settle();
    await confirmWith('general availability');

    expect(api.setValue).toHaveBeenCalledWith('feature.support_visits', {
      scopeType: 'PLATFORM',
      explicitNull: false,
      booleanValue: true,
      expectedVersion: 3,
      reason: 'general availability',
    });
  });

  it('hands a tenant back to the setting for everyone by clearing its own', async () => {
    await create([flag({ tenants: [{ tenantId: 'tenant-1', tenantName: 'Oshxona', value: true, version: 2 }] })]);

    el<HTMLButtonElement>('.handBack').click();
    await settle();
    await confirmWith('pilot over');

    expect(api.setValue).toHaveBeenCalledWith('feature.support_visits', {
      scopeType: 'TENANT',
      tenantId: 'tenant-1',
      explicitNull: true,
      booleanValue: undefined,
      expectedVersion: 2,
      reason: 'pilot over',
    });
  });
});
