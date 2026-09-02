import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { LocationScope } from '../../../core/api/operations-paths';
import { CurrentLocation } from '../../../core/auth/current-location';
import { I18n } from '../../../core/i18n/i18n';
import { ReasonResponse, ReferenceDataApi } from './reference-data-api';
import { ReferenceDataPage } from './reference-data-page';

const SCOPE: LocationScope = { tenantId: 'tenant-1', brandId: 'brand-1', locationId: 'location-1' };

const CANCELLATION_REASON: ReasonResponse = {
  id: 'reason-1',
  kind: 'CANCELLATION',
  systemCategory: 'OUT_OF_STOCK',
  internalName: 'Не дозвонились',
  stockDisposition: 'RELEASE',
  liabilityParty: 'CUSTOMER',
  customerRefund: 'NONE',
  allowedFulfillmentModes: null,
  customerTexts: { ru: 'Не удалось связаться с вами', 'uz-Latn': '...', en: '...' },
  status: 'ACTIVE',
  version: 1,
  updatedAt: '2026-08-01T00:00:00Z',
};

class FakeCurrentLocation {
  readonly scope = signal<LocationScope | null>(SCOPE);
  readonly denied = signal(false);
  ensureLoaded = vi.fn().mockResolvedValue(undefined);
}

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

describe('ReferenceDataPage', () => {
  let fixture: ComponentFixture<ReferenceDataPage>;
  let api: {
    list: ReturnType<typeof vi.fn>;
    categories: ReturnType<typeof vi.fn>;
    create: ReturnType<typeof vi.fn>;
    archive: ReturnType<typeof vi.fn>;
  };

  beforeEach(async () => {
    api = {
      list: vi
        .fn()
        .mockImplementation((_scope: LocationScope, kind: string) =>
          Promise.resolve(kind === 'CANCELLATION' ? [CANCELLATION_REASON] : []),
        ),
      categories: vi.fn().mockResolvedValue(['OUT_OF_STOCK', 'CUSTOMER_UNREACHABLE']),
      create: vi.fn().mockResolvedValue('reason-2'),
      archive: vi.fn().mockResolvedValue(undefined),
    };

    await TestBed.configureTestingModule({
      imports: [ReferenceDataPage],
      providers: [
        { provide: ReferenceDataApi, useValue: api },
        { provide: CurrentLocation, useValue: new FakeCurrentLocation() },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(ReferenceDataPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
  });

  it('lists cancellation reasons with both the internal name and the customer text side by side', () => {
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Не дозвонились');
    expect(text).toContain('Не удалось связаться с вами');
  });

  it('shows honest not-built cards for the business calendar, SLA buckets and branch tags', () => {
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Business calendar');
    expect(text).toContain('SLA boundaries');
    expect(text).toContain('Branch tags');
  });

  it('creates a cancellation reason with the stock/liability/refund posture set once, up front', async () => {
    const addButtons = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('.link'),
    ).filter((el) => el.textContent?.includes('Add')) as HTMLButtonElement[];
    addButtons[0].click();
    fixture.detectChanges();

    const setValue = (id: string, value: string) => {
      const el = fixture.nativeElement.querySelector(id) as HTMLInputElement;
      el.value = value;
      el.dispatchEvent(new Event('input'));
    };
    setValue('#reason-internal-name', 'Ресторан закрыт');
    setValue('#reason-text-ru', 'Ресторан временно не принимает заказы');
    setValue('#reason-text-uz', '...');
    setValue('#reason-text-en', '...');
    fixture.detectChanges();

    const submit = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('.dialog__actions button'),
    ).find((button) => button.textContent?.includes('Create')) as HTMLButtonElement;
    expect(submit.disabled).toBe(false);
    submit.click();
    await flushMicrotasks();

    expect(api.create).toHaveBeenCalledWith(
      SCOPE,
      expect.objectContaining({
        kind: 'CANCELLATION',
        internalName: 'Ресторан закрыт',
        stockDisposition: 'RELEASE',
        liabilityParty: 'TENANT',
      }),
    );
  });

  it('never offers delete, only disable, and confirms before disabling', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    const disableButton = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('.link'),
    ).find((el) => el.textContent?.includes('Disable')) as HTMLButtonElement;
    disableButton.click();
    await flushMicrotasks();

    expect(api.archive).toHaveBeenCalledWith(SCOPE, 'reason-1', 1);
    expect((fixture.nativeElement as HTMLElement).textContent).not.toContain('Delete');
  });
});
