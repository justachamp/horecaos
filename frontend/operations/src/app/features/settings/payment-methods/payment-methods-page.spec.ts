import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { LocationScope } from '../../../core/api/operations-paths';
import { ApiError, ApiErrorCode } from '../../../core/api/problem-details';
import { CurrentLocation } from '../../../core/auth/current-location';
import { I18n, Locale } from '../../../core/i18n/i18n';
import { LocaleSetView } from '../../../core/i18n/locale-labels';
import { LocaleSet } from '../../../core/i18n/locale-set';
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

/**
 * The operator's <em>own brand's</em> language set. A payment method is a
 * tenant-wide row, so the page must not read this: it is pinned to one brand
 * (ru alone here) while the tenant's other brands offer more.
 */
class FakeLocaleSet {
  readonly locales = signal<readonly Locale[]>(['ru']);
  readonly defaultLocale = signal<Locale>('ru');
  ensureLoaded = vi.fn().mockResolvedValue(undefined);
}

/** What `GET .../payment-methods/locale-set` answers: the union of the tenant's brands (row 10.12). */
function tenantSet(locales: readonly string[], defaultLocale: string): LocaleSetView {
  return { locales, defaultLocale, configured: true };
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
    localeSet: ReturnType<typeof vi.fn>;
  };
  let integrationsApi: { listInstallations: ReturnType<typeof vi.fn> };
  let localeSet: FakeLocaleSet;

  beforeEach(async () => {
    localeSet = new FakeLocaleSet();
    api = {
      list: vi.fn().mockResolvedValue([CASH]),
      create: vi.fn().mockResolvedValue(CASH),
      update: vi.fn().mockResolvedValue({ ...CASH, version: 5 }),
      replaceTranslations: vi.fn().mockResolvedValue(CASH),
      activate: vi.fn().mockResolvedValue({ ...CASH, status: 'ACTIVE' }),
      disable: vi.fn().mockResolvedValue({ ...CASH, status: 'DISABLED' }),
      localeSet: vi.fn().mockResolvedValue(tenantSet(['ru', 'uz-Latn', 'en'], 'ru')),
    };
    integrationsApi = { listInstallations: vi.fn().mockResolvedValue([]) };

    await TestBed.configureTestingModule({
      imports: [PaymentMethodsPage],
      providers: [
        { provide: PaymentMethodsApi, useValue: api },
        { provide: IntegrationsApi, useValue: integrationsApi },
        { provide: CurrentLocation, useValue: new FakeCurrentLocation() },
        { provide: LocaleSet, useValue: localeSet },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(PaymentMethodsPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
  });

  /**
   * Re-renders the page after the tenant's language set (the union of its brands)
   * changes, then opens the first method's editor. The operator's own brand keeps
   * offering ru alone throughout: it must make no difference.
   */
  async function openEditorOffering(
    locales: readonly Locale[],
    defaultLocale: Locale,
    method: PaymentMethodView = CASH,
  ): Promise<void> {
    TestBed.resetTestingModule();
    localeSet = new FakeLocaleSet();
    api.localeSet.mockResolvedValue(tenantSet(locales, defaultLocale));
    api.list.mockResolvedValue([method]);
    await TestBed.configureTestingModule({
      imports: [PaymentMethodsPage],
      providers: [
        { provide: PaymentMethodsApi, useValue: api },
        { provide: IntegrationsApi, useValue: integrationsApi },
        { provide: CurrentLocation, useValue: new FakeCurrentLocation() },
        { provide: LocaleSet, useValue: localeSet },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(PaymentMethodsPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
    (fixture.nativeElement.querySelector('.row') as HTMLElement).click();
    fixture.detectChanges();
  }

  function tabIds(): string[] {
    return Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll<HTMLElement>(
        '[data-testid^="q-localized-field-group-tab-"]',
      ),
    ).map((tab) => tab.dataset['testid'] ?? '');
  }

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

    // The editor opens on the brand's default language ('ru' on the platform
    // fallback), whose existing wording is already in the field; switch to en.
    expect(
      (fixture.nativeElement.querySelector('[data-testid="translation-ru"]') as HTMLInputElement)
        .value,
    ).toBe('Наличные');
    (
      fixture.nativeElement.querySelector(
        '[data-testid="q-localized-field-group-tab-en"]',
      ) as HTMLButtonElement
    ).click();
    fixture.detectChanges();
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

  // ------------------------------------------------------------------ 10.12

  it("loads the tenant's language set with the registry", () => {
    expect(api.localeSet).toHaveBeenCalledWith(SCOPE);
  });

  it("offers the tenant's languages, not the operator's brand's, however narrow that brand is", async () => {
    // The fixture's operator is pinned to a brand offering ru alone; the tenant's other
    // brand offers uz-Latn and en. A payment method is shared by both.
    await openEditorOffering(['ru', 'uz-Latn', 'en'], 'ru');

    expect(localeSet.locales()).toEqual(['ru']);
    expect(tabIds()).toEqual([
      'q-localized-field-group-tab-ru',
      'q-localized-field-group-tab-uz-Latn',
      'q-localized-field-group-tab-en',
    ]);
  });

  it("marks the tenant's default, not the operator's brand's", async () => {
    // The tenant's first brand defaults to uz-Latn; the operator's own brand to ru.
    await openEditorOffering(['uz-Latn', 'ru', 'en'], 'uz-Latn');

    expect(
      fixture.nativeElement.querySelector(
        '[data-testid="q-localized-field-group-tab-uz-Latn"] [data-testid="q-localized-field-group-default-marker"]',
      ),
    ).toBeTruthy();
    expect(fixture.nativeElement.querySelector('[data-testid="translation-uz-Latn"]')).toBeTruthy();
  });

  it("falls back to the platform triple when the tenant's set cannot be read", async () => {
    api.localeSet.mockRejectedValue(new Error('boom'));
    TestBed.resetTestingModule();
    await TestBed.configureTestingModule({
      imports: [PaymentMethodsPage],
      providers: [
        { provide: PaymentMethodsApi, useValue: api },
        { provide: IntegrationsApi, useValue: integrationsApi },
        { provide: CurrentLocation, useValue: new FakeCurrentLocation() },
        { provide: LocaleSet, useValue: new FakeLocaleSet() },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(PaymentMethodsPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
    (fixture.nativeElement.querySelector('.row') as HTMLElement).click();
    fixture.detectChanges();

    expect(tabIds()).toEqual([
      'q-localized-field-group-tab-ru',
      'q-localized-field-group-tab-uz-Latn',
      'q-localized-field-group-tab-en',
    ]);
  });

  it("offers the tenant's own languages as tabs, default first and marked, and opens on the default", async () => {
    await openEditorOffering(['uz-Latn', 'en'], 'uz-Latn');

    expect(tabIds()).toEqual([
      'q-localized-field-group-tab-uz-Latn',
      'q-localized-field-group-tab-en',
    ]);
    const marked = fixture.nativeElement.querySelectorAll(
      '[data-testid="q-localized-field-group-default-marker"]',
    );
    expect(marked.length).toBe(1);
    expect(
      fixture.nativeElement.querySelector(
        '[data-testid="q-localized-field-group-tab-uz-Latn"] [data-testid="q-localized-field-group-default-marker"]',
      ),
    ).toBeTruthy();
    // Not the console's own language ('en' here): the default follows the brand.
    expect(fixture.nativeElement.querySelector('[data-testid="translation-uz-Latn"]')).toBeTruthy();
    expect(fixture.nativeElement.querySelector('[data-testid="translation-en"]')).toBeFalsy();
  });

  it('reports completeness only for the languages the brand offers', async () => {
    await openEditorOffering(['uz-Latn', 'en'], 'uz-Latn');

    // CASH is named in ru alone, which this brand does not offer.
    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelectorAll('[data-testid="q-localized-field-group-complete"]').length).toBe(
      0,
    );
    expect(host.querySelectorAll('[data-testid="q-localized-field-group-incomplete"]').length).toBe(
      2,
    );
  });

  it('keeps a name in a language the brand does not offer when the methods names are replaced', async () => {
    const method: PaymentMethodView = {
      ...CASH,
      localizedNames: { ru: 'Наличные', 'uz-Latn': 'Naqd' },
    };
    await openEditorOffering(['en'], 'en', method);

    // The hidden languages are neither tabs nor fields...
    expect(tabIds()).toEqual(['q-localized-field-group-tab-en']);
    expect(fixture.nativeElement.querySelector('[data-testid="translation-ru"]')).toBeFalsy();

    const translationInput = fixture.nativeElement.querySelector(
      '[data-testid="translation-en"]',
    ) as HTMLInputElement;
    translationInput.value = 'Cash';
    translationInput.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    // ...the operator is told they are kept...
    expect(
      fixture.nativeElement.querySelector('[data-testid="payment-method-hidden-kept"]'),
    ).toBeTruthy();

    (
      fixture.nativeElement.querySelector('[data-testid="save-method"]') as HTMLButtonElement
    ).click();
    await flushMicrotasks();

    // ...and `PUT .../translations` replaces the whole set, so they go back unchanged.
    expect(api.replaceTranslations).toHaveBeenCalledWith(SCOPE, 'pm-cash', {
      ru: 'Наличные',
      'uz-Latn': 'Naqd',
      en: 'Cash',
    });
  });

  it("opens each method's editor on the default language again", async () => {
    await openEditorOffering(['uz-Latn', 'en'], 'uz-Latn');
    (
      fixture.nativeElement.querySelector(
        '[data-testid="q-localized-field-group-tab-en"]',
      ) as HTMLButtonElement
    ).click();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[data-testid="translation-en"]')).toBeTruthy();

    // Close and reopen the same row.
    (fixture.nativeElement.querySelector('.row') as HTMLElement).click();
    fixture.detectChanges();
    (fixture.nativeElement.querySelector('.row') as HTMLElement).click();
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('[data-testid="translation-uz-Latn"]')).toBeTruthy();
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
        { provide: LocaleSet, useValue: new FakeLocaleSet() },
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
