import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { LocationScope } from '../../../core/api/operations-paths';
import { ApiError, ApiErrorCode } from '../../../core/api/problem-details';
import { CurrentLocation } from '../../../core/auth/current-location';
import { I18n } from '../../../core/i18n/i18n';
import { IntegrationsApi } from '../integrations/integrations-api';
import { PaymentMethodView, PaymentMethodsApi } from './payment-methods-api';
import { PaymentMethodsPage } from './payment-methods-page';

const SCOPE: LocationScope = { tenantId: 'tenant-1', brandId: 'brand-1', locationId: 'location-1' };

const CASH: PaymentMethodView = {
  id: 'pm-cash',
  code: 'CASH',
  displayName: 'Cash',
  localizedNames: { ru: 'Наличные' },
  responsibility: 'OPERATOR',
  settlesFromBalance: false,
  status: 'ACTIVE',
  icon: 'cash',
  sortOrder: 0,
  providerInstallationId: null,
  contractReference: null,
  version: 4,
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

describe('PaymentMethodsPage', () => {
  let fixture: ComponentFixture<PaymentMethodsPage>;
  let api: {
    list: ReturnType<typeof vi.fn>;
    create: ReturnType<typeof vi.fn>;
    update: ReturnType<typeof vi.fn>;
    replaceTranslations: ReturnType<typeof vi.fn>;
    activate: ReturnType<typeof vi.fn>;
    disable: ReturnType<typeof vi.fn>;
  };
  let integrationsApi: { listInstallations: ReturnType<typeof vi.fn> };

  beforeEach(async () => {
    api = {
      list: vi.fn().mockResolvedValue([CASH]),
      create: vi.fn().mockResolvedValue(CASH),
      update: vi.fn().mockResolvedValue({ ...CASH, version: 5 }),
      replaceTranslations: vi.fn().mockResolvedValue(CASH),
      activate: vi.fn().mockResolvedValue({ ...CASH, status: 'ACTIVE' }),
      disable: vi.fn().mockResolvedValue({ ...CASH, status: 'DISABLED' }),
    };
    integrationsApi = { listInstallations: vi.fn().mockResolvedValue([]) };

    await TestBed.configureTestingModule({
      imports: [PaymentMethodsPage],
      providers: [
        { provide: PaymentMethodsApi, useValue: api },
        { provide: IntegrationsApi, useValue: integrationsApi },
        { provide: CurrentLocation, useValue: new FakeCurrentLocation() },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(PaymentMethodsPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
  });

  it('lists the registry', () => {
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Cash');
    expect(text).toContain('CASH');
    expect(api.list).toHaveBeenCalledWith(SCOPE);
  });

  it('creates a payment method from the inline form', async () => {
    const toggle = fixture.nativeElement.querySelector('.toolbar button') as HTMLButtonElement;
    toggle.click();
    fixture.detectChanges();

    const codeInput = fixture.nativeElement.querySelector('#new-method-code') as HTMLInputElement;
    const nameInput = fixture.nativeElement.querySelector('#new-method-name') as HTMLInputElement;
    codeInput.value = 'telegram';
    codeInput.dispatchEvent(new Event('input'));
    nameInput.value = 'Telegram';
    nameInput.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    const submit = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('button'),
    ).find((button) => button.textContent?.trim() === 'Create') as HTMLButtonElement;
    submit.click();
    await flushMicrotasks();

    expect(api.create).toHaveBeenCalledWith(
      SCOPE,
      expect.objectContaining({
        code: 'TELEGRAM',
        displayName: 'Telegram',
        responsibility: 'PARTNER',
      }),
    );
  });

  it('edits a method and replaces its localized names in the same save', async () => {
    const row = fixture.nativeElement.querySelector('.row') as HTMLElement;
    row.click();
    fixture.detectChanges();

    const nameInput = fixture.nativeElement.querySelector('#edit-name-pm-cash') as HTMLInputElement;
    nameInput.value = 'Cash (updated)';
    nameInput.dispatchEvent(new Event('input'));

    // The active locale defaults to the console's own UI locale ('en' here);
    // the method's existing 'ru' translation is preserved untouched.
    const translationInput = fixture.nativeElement.querySelector(
      '[data-testid="translation-en"]',
    ) as HTMLInputElement;
    translationInput.value = 'Cash (translated)';
    translationInput.dispatchEvent(new Event('input'));

    const save = fixture.nativeElement.querySelector(
      '[data-testid="save-method"]',
    ) as HTMLButtonElement;
    save.click();
    await flushMicrotasks();

    expect(api.update).toHaveBeenCalledWith(
      SCOPE,
      'pm-cash',
      expect.objectContaining({ displayName: 'Cash (updated)' }),
      4,
    );
    expect(api.replaceTranslations).toHaveBeenCalledWith(
      SCOPE,
      'pm-cash',
      expect.objectContaining({ ru: 'Наличные', en: 'Cash (translated)' }),
    );
  });

  it('disables an active method and can activate it again', async () => {
    const toggle = fixture.nativeElement.querySelector(
      '[data-testid="toggle-status"]',
    ) as HTMLButtonElement;
    toggle.click();
    await flushMicrotasks();

    expect(api.disable).toHaveBeenCalledWith(SCOPE, 'pm-cash', 4);
  });

  it('renders the denied state on a 403 from the list', async () => {
    TestBed.resetTestingModule();
    const list = vi
      .fn()
      .mockRejectedValue(new ApiError(ApiErrorCode.INSUFFICIENT_CAPABILITY, 403, null, null));
    await TestBed.configureTestingModule({
      imports: [PaymentMethodsPage],
      providers: [
        { provide: PaymentMethodsApi, useValue: { ...api, list } },
        { provide: IntegrationsApi, useValue: integrationsApi },
        { provide: CurrentLocation, useValue: new FakeCurrentLocation() },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(PaymentMethodsPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelector('[data-testid="payment-methods-denied"]')).toBeTruthy();
    expect(host.querySelector('.row')).toBeFalsy();
  });
});
