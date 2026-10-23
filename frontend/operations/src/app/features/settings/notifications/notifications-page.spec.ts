import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { LocationScope } from '../../../core/api/operations-paths';
import { CurrentLocation } from '../../../core/auth/current-location';
import { I18n } from '../../../core/i18n/i18n';
import { Toasts } from '../../../shared/ui/toast';
import { ConfigurationApi } from '../configuration-api';
import {
  NotificationsApi,
  OtpWording,
  PlatformDefault,
  RoutingBinding,
  TemplateResponse,
  VersionGroup,
} from './notifications-api';
import { NotificationsPage } from './notifications-page';

const SCOPE: LocationScope = { tenantId: 'tenant-1', brandId: 'brand-1', locationId: 'location-1' };

const TEMPLATE: TemplateResponse = {
  id: 'template-1',
  brandId: 'brand-1',
  templateKey: 'CONFIRMED',
  notificationClass: 'TRANSACTIONAL_REQUIRED',
  channel: 'SMS',
  consentPurpose: null,
  status: 'ACTIVE',
  activeVersion: 1,
  version: 1,
  fulfillmentMode: null,
  channelSource: null,
  origin: 'BRAND',
  shownVersion: 1,
  versionLocales: ['ru', 'uz-Latn', 'en'],
  requiredLocales: ['ru', 'uz-Latn', 'en'],
  providerReview: 'NOT_REQUIRED',
};

const PLATFORM_CONFIRMED: PlatformDefault = {
  templateKey: 'ORDER_CONFIRMED',
  notificationClass: 'TRANSACTIONAL_REQUIRED',
  consentPurpose: null,
  channels: ['SMS', 'TELEGRAM'],
  version: 1,
  bodies: {
    ru: 'Заказ {{orderNumber}} принят.',
    'uz-Latn': '{{orderNumber}}-buyurtma qabul qilindi.',
    en: 'Order {{orderNumber}} is confirmed.',
  },
  variables: ['orderNumber'],
};

const PLATFORM_PREPARING: PlatformDefault = {
  templateKey: 'ORDER_PREPARING',
  notificationClass: 'TRANSACTIONAL_OPTIONAL',
  consentPurpose: 'ORDER_PROGRESS_UPDATES',
  channels: ['SMS', 'TELEGRAM'],
  version: 1,
  bodies: {
    ru: 'Готовим {{orderNumber}}.',
    'uz-Latn': 'Tayyorlanmoqda {{orderNumber}}.',
    en: 'Preparing {{orderNumber}}.',
  },
  variables: ['orderNumber'],
};

const OTP: OtpWording = {
  gatewayApproval: 'GATEWAY_MODERATES',
  sentences: [
    { locale: 'ru', text: 'HorecaOS: kod {{code}}. Deystvitelen {{minutes}} min.', characters: 49 },
    { locale: 'en', text: 'HorecaOS: your code is {{code}}.', characters: 32 },
  ],
};

const PENDING_VERSION: VersionGroup = {
  versionNumber: 2,
  status: 'DRAFT',
  approvedBy: null,
  locales: [
    {
      versionNumber: 2,
      locale: 'ru',
      subject: null,
      body: 'Ваш код {{code}}',
      contentHash: 'h',
      status: 'DRAFT',
      approvedBy: null,
      variablesSchema: { code: 'string' },
      providerReview: 'PENDING',
      providerReviewReference: null,
      providerReviewNote: 'The SMS gateway moderates wordings; awaiting its approval',
      providerReviewUpdatedAt: '2026-09-12T00:00:00Z',
      showItemPrices: false,
    },
  ],
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

describe('NotificationsPage', () => {
  let fixture: ComponentFixture<NotificationsPage>;
  let api: {
    list: ReturnType<typeof vi.fn>;
    platformDefaults: ReturnType<typeof vi.fn>;
    otpWording: ReturnType<typeof vi.fn>;
    create: ReturnType<typeof vi.fn>;
    versions: ReturnType<typeof vi.fn>;
    version: ReturnType<typeof vi.fn>;
    variableCatalogue: ReturnType<typeof vi.fn>;
    activate: ReturnType<typeof vi.fn>;
    addVersion: ReturnType<typeof vi.fn>;
    testSend: ReturnType<typeof vi.fn>;
    routingBindings: ReturnType<typeof vi.fn>;
    routingEventClasses: ReturnType<typeof vi.fn>;
    setRoutingSubscription: ReturnType<typeof vi.fn>;
    changeRoutingTopic: ReturnType<typeof vi.fn>;
    unbindRouting: ReturnType<typeof vi.fn>;
  };
  let configApi: {
    resolution: ReturnType<typeof vi.fn>;
    setValue: ReturnType<typeof vi.fn>;
  };

  beforeEach(async () => {
    api = {
      list: vi.fn().mockResolvedValue([TEMPLATE]),
      platformDefaults: vi.fn().mockResolvedValue([PLATFORM_CONFIRMED, PLATFORM_PREPARING]),
      otpWording: vi.fn().mockResolvedValue(OTP),
      create: vi.fn().mockResolvedValue('template-2'),
      versions: vi.fn().mockResolvedValue([]),
      version: vi.fn().mockResolvedValue([]),
      variableCatalogue: vi.fn().mockResolvedValue([]),
      activate: vi.fn().mockResolvedValue(undefined),
      addVersion: vi.fn().mockResolvedValue({
        templateId: 'template-2',
        versionNumber: 1,
        awaitsProviderReview: false,
      }),
      testSend: vi
        .fn()
        .mockResolvedValue({ status: 'ACCEPTED', providerStatus: null, errorCode: null }),
      routingBindings: vi.fn().mockResolvedValue([]),
      routingEventClasses: vi.fn().mockResolvedValue([]),
      setRoutingSubscription: vi.fn().mockResolvedValue(undefined),
      changeRoutingTopic: vi.fn().mockResolvedValue(undefined),
      unbindRouting: vi.fn().mockResolvedValue(undefined),
    };
    configApi = {
      resolution: vi.fn().mockResolvedValue({
        keyCode: 'notifications.payment_link_auto_send',
        value: false,
        cameFromDefault: true,
        source: 'CODE_DEFAULT',
        winningScope: null,
        inspectedLevels: [],
        describe: 'default',
        currentVersionAtScope: null,
      }),
      setValue: vi.fn().mockResolvedValue({
        id: 'v1',
        keyCode: 'notifications.payment_link_auto_send',
        scopeType: 'BRAND',
        value: true,
        explicitNull: false,
        version: 1,
      }),
    };

    await TestBed.configureTestingModule({
      imports: [NotificationsPage],
      providers: [
        { provide: NotificationsApi, useValue: api },
        { provide: ConfigurationApi, useValue: configApi },
        { provide: CurrentLocation, useValue: new FakeCurrentLocation() },
        provideRouter([]),
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(NotificationsPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
  });

  it('lists templates on Tab 1', () => {
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('CONFIRMED');
    expect(api.list).toHaveBeenCalledWith(SCOPE);
  });

  it('resolves both automation switches at BRAND scope on load (gap map row 10.9d)', () => {
    expect(configApi.resolution).toHaveBeenCalledWith(
      'tenant-1',
      'notifications.payment_link_auto_send',
      'BRAND',
      'brand-1',
      null,
    );
    expect(configApi.resolution).toHaveBeenCalledWith(
      'tenant-1',
      'notifications.aggregator_shift_notifications_enabled',
      'BRAND',
      'brand-1',
      null,
    );
    const checkboxes = (fixture.nativeElement as HTMLElement).querySelectorAll(
      '.card input[type="checkbox"]',
    );
    expect(checkboxes.length).toBe(2);
  });

  it('toggling the payment-link switch writes the choice at BRAND scope', async () => {
    const checkbox = (fixture.nativeElement as HTMLElement).querySelector(
      '.card input[type="checkbox"]',
    ) as HTMLInputElement;
    checkbox.checked = true;
    checkbox.dispatchEvent(new Event('change'));
    await flushMicrotasks();

    expect(configApi.setValue).toHaveBeenCalledWith(
      'tenant-1',
      'notifications.payment_link_auto_send',
      expect.objectContaining({ scopeType: 'BRAND', brandId: 'brand-1', booleanValue: true }),
    );
  });

  it('loads bindings and event classes when the routing tab is opened (gap map row 10.9b)', async () => {
    const tabs = (fixture.nativeElement as HTMLElement).querySelectorAll('.tab');
    (tabs[1] as HTMLButtonElement).click();
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(api.routingBindings).toHaveBeenCalledWith(SCOPE);
    expect(api.routingEventClasses).toHaveBeenCalledWith(SCOPE);
  });

  it('creating a template opens the editor for its first version instead of bundling one in', async () => {
    // api.create answers with the new id alone (matching the real
    // IdResponse); the component finds the created row by reloading the
    // list, so the reload must actually include it — a fixed mock that
    // never changes would make this pass for the wrong reason.
    const created: TemplateResponse = {
      ...TEMPLATE,
      id: 'template-2',
      templateKey: 'CANCELLED',
      activeVersion: null,
    };
    api.list.mockResolvedValueOnce([TEMPLATE, created]);

    const toggle = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('button'),
    ).find((button) => button.textContent?.includes('New template')) as HTMLButtonElement;
    toggle.click();
    fixture.detectChanges();

    const setValue = (id: string, value: string) => {
      const el = fixture.nativeElement.querySelector(id) as HTMLInputElement;
      el.value = value;
      el.dispatchEvent(new Event('input'));
    };
    setValue('#template-key', 'CANCELLED');
    fixture.detectChanges();

    const submit = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('.form__actions button'),
    ).find((button) => button.textContent?.includes('Create')) as HTMLButtonElement;
    submit.click();
    await flushMicrotasks();
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(api.create).toHaveBeenCalledWith(SCOPE, {
      templateKey: 'CANCELLED',
      notificationClass: 'TRANSACTIONAL_REQUIRED',
      channel: 'SMS',
      fulfillmentMode: null,
      channelSource: null,
    });
    // The old behaviour hard-coded variablesSchema: {} inside this same call;
    // now there is no addVersion call at all from the create step — authoring
    // moved entirely to `TemplateEditor`, which is what proves the fix.
    expect(api.addVersion).not.toHaveBeenCalled();
    expect(
      (fixture.nativeElement as HTMLElement).querySelector('q-notification-template-editor'),
    ).toBeTruthy();
  });

  it('creating a template variant sends the chosen fulfilment mode and channel source (gap-map row 10.9a)', async () => {
    const toggle = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('button'),
    ).find((button) => button.textContent?.includes('New template')) as HTMLButtonElement;
    toggle.click();
    fixture.detectChanges();

    const setInput = (id: string, value: string) => {
      const el = fixture.nativeElement.querySelector(id) as HTMLInputElement;
      el.value = value;
      el.dispatchEvent(new Event('input'));
    };
    const setSelect = (id: string, value: string) => {
      const el = fixture.nativeElement.querySelector(id) as HTMLSelectElement;
      el.value = value;
      el.dispatchEvent(new Event('change'));
    };
    setInput('#template-key', 'CONFIRMED');
    setSelect('#template-fulfilment-mode', 'DINE_IN');
    setSelect('#template-channel-source', 'QR_TABLE');
    fixture.detectChanges();

    const submit = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('.form__actions button'),
    ).find((button) => button.textContent?.includes('Create')) as HTMLButtonElement;
    submit.click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(api.create).toHaveBeenCalledWith(SCOPE, {
      templateKey: 'CONFIRMED',
      notificationClass: 'TRANSACTIONAL_REQUIRED',
      channel: 'SMS',
      fulfillmentMode: 'DINE_IN',
      channelSource: 'QR_TABLE',
    });
  });

  it('creating a push template makes it a marketing one and asks for its consent purpose (ADR 0182)', async () => {
    const toggle = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('button'),
    ).find((button) => button.textContent?.includes('New template')) as HTMLButtonElement;
    toggle.click();
    fixture.detectChanges();

    const setInput = (id: string, value: string) => {
      const el = fixture.nativeElement.querySelector(id) as HTMLInputElement;
      el.value = value;
      el.dispatchEvent(new Event('input'));
    };
    const channel = fixture.nativeElement.querySelector('#template-channel') as HTMLSelectElement;
    channel.value = 'PUSH';
    channel.dispatchEvent(new Event('change'));
    fixture.detectChanges();

    const classSelect = fixture.nativeElement.querySelector('#template-class') as HTMLSelectElement;
    expect(classSelect.value).toBe('MARKETING');
    expect(classSelect.disabled).toBe(true);
    const purpose = fixture.nativeElement.querySelector(
      '#template-consent-purpose',
    ) as HTMLInputElement;
    expect(purpose.value).toBe('MARKETING_PROMOTIONS');

    setInput('#template-key', 'PROMO_PUSH');
    fixture.detectChanges();
    const submit = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('.form__actions button'),
    ).find((button) => button.textContent?.includes('Create')) as HTMLButtonElement;
    expect(submit.disabled).toBe(false);
    submit.click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(api.create).toHaveBeenCalledWith(SCOPE, {
      templateKey: 'PROMO_PUSH',
      notificationClass: 'MARKETING',
      channel: 'PUSH',
      consentPurpose: 'MARKETING_PROMOTIONS',
      fulfillmentMode: null,
      channelSource: null,
    });
  });

  it('a template whose class needs a consent purpose cannot be created without one', async () => {
    const toggle = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('button'),
    ).find((button) => button.textContent?.includes('New template')) as HTMLButtonElement;
    toggle.click();
    fixture.detectChanges();

    const classSelect = fixture.nativeElement.querySelector('#template-class') as HTMLSelectElement;
    classSelect.value = 'MARKETING';
    classSelect.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    const key = fixture.nativeElement.querySelector('#template-key') as HTMLInputElement;
    key.value = 'WIN_BACK';
    key.dispatchEvent(new Event('input'));
    const purpose = fixture.nativeElement.querySelector(
      '#template-consent-purpose',
    ) as HTMLInputElement;
    purpose.value = '   ';
    purpose.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    const submit = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('.form__actions button'),
    ).find((button) => button.textContent?.includes('Create')) as HTMLButtonElement;
    expect(submit.disabled).toBe(true);
  });

  it('labels a variant row by its fulfilment mode and channel source, and a wildcard row "Any"', () => {
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    // TEMPLATE itself is a wildcard row (both null).
    expect(text).toContain('Any');
  });

  it('renders the moderation column and warns before activating a PENDING version (gap map row 10.9c)', async () => {
    api.versions.mockResolvedValue([PENDING_VERSION]);
    await (
      fixture.componentInstance as unknown as { openVersions(t: TemplateResponse): Promise<void> }
    ).openVersions(TEMPLATE);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Awaiting gateway');

    (
      fixture.componentInstance as unknown as {
        onVersionRowAction(id: string, g: VersionGroup): void;
      }
    ).onVersionRowAction('activate', PENDING_VERSION);
    fixture.detectChanges();

    const dialog = (fixture.nativeElement as HTMLElement).querySelector('q-confirm-dialog');
    expect(dialog).toBeTruthy();
    // The exact silent failure this wave's row is named after: a merchant
    // used to see nothing here and would only discover the suppression from
    // a customer who never got a message.
    expect(dialog?.textContent).toContain('Awaiting gateway');
  });
  describe('Tab 1 as ADR 0184 finishes it (row 10.9a)', () => {
    const text = () => (fixture.nativeElement as HTMLElement).textContent ?? '';
    const rows = () =>
      Array.from(
        (fixture.nativeElement as HTMLElement).querySelectorAll<HTMLElement>(
          '[data-testid="template-table"] tbody tr[data-severity]',
        ),
      );

    async function relist(list: readonly TemplateResponse[]): Promise<void> {
      api.list.mockResolvedValue(list);
      fixture = TestBed.createComponent(NotificationsPage);
      fixture.detectChanges();
      await flushMicrotasks();
      fixture.detectChanges();
    }

    it('shows a chip per language the brand serves, and flags the one an active template lacks', async () => {
      await relist([{ ...TEMPLATE, versionLocales: ['ru', 'uz-Latn'] }]);

      const chips = Array.from(
        (fixture.nativeElement as HTMLElement).querySelectorAll<HTMLElement>(
          '[data-testid="language-chips"] .locale-chip',
        ),
      );
      expect(chips.map((chip) => chip.textContent?.trim())).toEqual(['ru', 'uz-Latn', 'en']);
      expect(chips.map((chip) => chip.getAttribute('data-present'))).toEqual([
        'true',
        'true',
        'false',
      ]);
      expect(text()).toContain('a language is missing');
    });

    it('shows the gateway moderation state in its own column on the list, not only in the versions drawer', async () => {
      await relist([{ ...TEMPLATE, providerReview: 'REJECTED' }]);

      expect(
        (fixture.nativeElement as HTMLElement).querySelector('[data-testid="moderation-cell"]')
          ?.textContent,
      ).toContain('Refused by gateway');
    });

    it('lists what is wrong first, then walks an order through its life', async () => {
      await relist([
        { ...TEMPLATE, id: 'cancelled', templateKey: 'ORDER_CANCELLED' },
        { ...TEMPLATE, id: 'ready', templateKey: 'ORDER_READY' },
        { ...TEMPLATE, id: 'confirmed', templateKey: 'ORDER_CONFIRMED' },
        {
          ...TEMPLATE,
          id: 'draft',
          templateKey: 'ORDER_PREPARING',
          status: 'DRAFT',
          activeVersion: null,
        },
        { ...TEMPLATE, id: 'blocked', templateKey: 'ORDER_REJECTED', providerReview: 'PENDING' },
      ]);

      expect(rows().map((row) => row.getAttribute('data-testid'))).toEqual([
        'template-row-blocked',
        'template-row-draft',
        'template-row-confirmed',
        'template-row-ready',
        'template-row-cancelled',
      ]);
    });

    it('marks where each row sits in ADR 0020’s order with an origin chip', async () => {
      await relist([
        { ...TEMPLATE, id: 'brand' },
        { ...TEMPLATE, id: 'tenant', brandId: null, origin: 'TENANT' },
      ]);

      const chips = Array.from(
        (fixture.nativeElement as HTMLElement).querySelectorAll<HTMLElement>(
          '[data-testid="template-table"] .origin-chip',
        ),
      );
      expect(chips.map((chip) => chip.getAttribute('data-origin'))).toEqual(['BRAND', 'TENANT']);
    });

    it('tells a marketing template’s author it is sent only to customers who consented', async () => {
      await relist([
        {
          ...TEMPLATE,
          id: 'promo',
          notificationClass: 'MARKETING',
          consentPurpose: 'MARKETING_PROMOTIONS',
        },
      ]);

      expect(
        (fixture.nativeElement as HTMLElement).querySelector('[data-testid="consent-chip"]'),
      ).toBeTruthy();
    });

    function openCreate(): void {
      const toggle = Array.from(
        (fixture.nativeElement as HTMLElement).querySelectorAll('button'),
      ).find((button) => button.textContent?.includes('New template')) as HTMLButtonElement;
      toggle.click();
      fixture.detectChanges();
    }

    function pickClass(value: string): void {
      const select = fixture.nativeElement.querySelector('#template-class') as HTMLSelectElement;
      select.value = value;
      select.dispatchEvent(new Event('change'));
      fixture.detectChanges();
    }

    it('offers four classes: a SECURITY template cannot be authored (D19)', () => {
      openCreate();
      const options = Array.from(
        (fixture.nativeElement as HTMLElement).querySelectorAll<HTMLOptionElement>(
          '#template-class option',
        ),
      ).map((option) => option.value);

      expect(options).toEqual([
        'TRANSACTIONAL_REQUIRED',
        'TRANSACTIONAL_OPTIONAL',
        'MARKETING',
        'OPERATIONS_ALERT',
      ]);
      expect(options).not.toContain('SECURITY');
    });

    it('asks for a consent purpose for an optional class, offers the platform’s, and will not create without one', () => {
      openCreate();
      expect(fixture.nativeElement.querySelector('[data-testid="consent-purpose"]')).toBeNull();

      pickClass('TRANSACTIONAL_OPTIONAL');
      const purpose = fixture.nativeElement.querySelector(
        '#template-consent-purpose',
      ) as HTMLInputElement;
      expect(purpose.placeholder).toBe('ORDER_PROGRESS_UPDATES');
      expect(purpose.value).toBe('ORDER_PROGRESS_UPDATES');

      purpose.value = '';
      purpose.dispatchEvent(new Event('input'));
      const key = fixture.nativeElement.querySelector('#template-key') as HTMLInputElement;
      key.value = 'ORDER_READY';
      key.dispatchEvent(new Event('input'));
      fixture.detectChanges();
      const submit = Array.from(
        (fixture.nativeElement as HTMLElement).querySelectorAll<HTMLButtonElement>(
          '.form__actions button',
        ),
      ).find((button) => button.textContent?.includes('Create')) as HTMLButtonElement;
      expect(submit.disabled).toBe(true);
    });

    it('sends the purpose with an optional template, and says a marketing one needs consent', async () => {
      openCreate();
      pickClass('MARKETING');
      expect(
        fixture.nativeElement.querySelector('[data-testid="marketing-notice"]')?.textContent,
      ).toContain('Sent only to customers who have consented');
      const purpose = fixture.nativeElement.querySelector(
        '#template-consent-purpose',
      ) as HTMLInputElement;
      purpose.value = 'MARKETING_PROMOTIONS';
      purpose.dispatchEvent(new Event('input'));
      const key = fixture.nativeElement.querySelector('#template-key') as HTMLInputElement;
      key.value = 'PROMO';
      key.dispatchEvent(new Event('input'));
      fixture.detectChanges();

      const submit = Array.from(
        (fixture.nativeElement as HTMLElement).querySelectorAll<HTMLButtonElement>(
          '.form__actions button',
        ),
      ).find((button) => button.textContent?.includes('Create')) as HTMLButtonElement;
      submit.click();
      await flushMicrotasks();

      expect(api.create).toHaveBeenCalledWith(
        SCOPE,
        expect.objectContaining({
          templateKey: 'PROMO',
          notificationClass: 'MARKETING',
          consentPurpose: 'MARKETING_PROMOTIONS',
        }),
      );
    });

    describe('the platform’s default wording (ADR 0020’s last layer)', () => {
      const defaults = () =>
        (fixture.nativeElement as HTMLElement).querySelector('[data-testid="platform-defaults"]');

      it('is shown read-only beneath the templates, in every language, with a Platform origin chip', () => {
        expect(defaults()).toBeTruthy();
        expect(defaults()?.textContent).toContain('Order {{orderNumber}} is confirmed.');
        expect(defaults()?.textContent).toContain('Заказ {{orderNumber}} принят.');
        expect(defaults()?.querySelector('.origin-chip')?.getAttribute('data-origin')).toBe(
          'PLATFORM',
        );
        expect(defaults()?.querySelectorAll('input, textarea').length).toBe(0);
      });

      it('says a default is overridden once an active template of the brand answers instead', async () => {
        await relist([{ ...TEMPLATE, templateKey: 'ORDER_CONFIRMED' }]);

        const confirmed = (fixture.nativeElement as HTMLElement).querySelector(
          '[data-testid="default-row-ORDER_CONFIRMED"]',
        );
        const preparing = (fixture.nativeElement as HTMLElement).querySelector(
          '[data-testid="default-row-ORDER_PREPARING"]',
        );
        expect(confirmed?.querySelector('[data-testid="overridden-note"]')).toBeTruthy();
        expect(preparing?.querySelector('[data-testid="overridden-note"]')).toBeNull();
        expect(confirmed?.textContent).toContain('Open your template');
      });

      it('customising copies the default into a template of this brand and opens the editor on its wording', async () => {
        const created: TemplateResponse = {
          ...TEMPLATE,
          id: 'template-2',
          templateKey: 'ORDER_PREPARING',
          notificationClass: 'TRANSACTIONAL_OPTIONAL',
          consentPurpose: 'ORDER_PROGRESS_UPDATES',
          status: 'DRAFT',
          activeVersion: null,
          shownVersion: null,
          versionLocales: [],
        };
        api.list.mockResolvedValueOnce([TEMPLATE, created]);

        (
          fixture.nativeElement.querySelector(
            '[data-testid="customise-ORDER_PREPARING"]',
          ) as HTMLButtonElement
        ).click();
        await flushMicrotasks();
        fixture.detectChanges();
        await flushMicrotasks();
        fixture.detectChanges();

        expect(api.create).toHaveBeenCalledWith(SCOPE, {
          templateKey: 'ORDER_PREPARING',
          notificationClass: 'TRANSACTIONAL_OPTIONAL',
          channel: 'SMS',
          consentPurpose: 'ORDER_PROGRESS_UPDATES',
        });
        const editor = (fixture.nativeElement as HTMLElement).querySelector(
          'q-notification-template-editor',
        );
        expect(editor).toBeTruthy();
        expect(
          (editor?.querySelector('[data-testid="editor-body"]') as HTMLTextAreaElement).value,
        ).toBe('Готовим {{orderNumber}}.');
      });

      it('survives the platform refusing to answer: the editable list still works', async () => {
        api.platformDefaults.mockRejectedValue(new Error('down'));
        await relist([TEMPLATE]);

        expect(defaults()).toBeNull();
        expect(text()).toContain('CONFIRMED');
      });
    });

    describe('the one-time-code sentence (D19)', () => {
      it('is shown per language as plain text, with the gateway’s state, and cannot be edited', () => {
        const card = (fixture.nativeElement as HTMLElement).querySelector(
          '[data-testid="otp-wording"]',
        );

        expect(card).toBeTruthy();
        expect(card?.textContent).toContain(
          'HorecaOS: kod {{code}}. Deystvitelen {{minutes}} min.',
        );
        expect(card?.textContent).toContain('approves wordings');
        expect(card?.querySelectorAll('input, textarea, select, button').length).toBe(0);
      });
    });

    describe('a test send beyond SMS', () => {
      const CHAT: RoutingBinding = {
        bindingId: 'binding-9',
        brandId: 'brand-1',
        locationId: null,
        status: 'ACTIVE',
        chatId: 777,
        topicId: null,
        retiredAt: null,
        retiredReason: null,
        createdAt: '2026-10-01T00:00:00Z',
        subscriptions: [],
      };
      const TELEGRAM_VERSION: VersionGroup = { ...PENDING_VERSION, status: 'ACTIVE' };

      type Internals = {
        openVersions(t: TemplateResponse): Promise<void>;
        onVersionRowAction(id: string, g: VersionGroup): void;
        versionRowMenu(g: VersionGroup): readonly { id: string }[];
      };

      it('offers Test send on a Telegram template and sends to one of the brand’s bound chats', async () => {
        api.routingBindings.mockResolvedValue([
          CHAT,
          { ...CHAT, bindingId: 'gone', retiredAt: '2026-10-02T00:00:00Z' },
        ]);
        const telegram: TemplateResponse = { ...TEMPLATE, id: 'tg', channel: 'TELEGRAM' };
        api.versions.mockResolvedValue([TELEGRAM_VERSION]);
        const page = fixture.componentInstance as unknown as Internals;
        await page.openVersions(telegram);
        fixture.detectChanges();

        expect(page.versionRowMenu(TELEGRAM_VERSION).map((item) => item.id)).toContain('testSend');
        page.onVersionRowAction('testSend', TELEGRAM_VERSION);
        await flushMicrotasks();
        fixture.detectChanges();

        const select = fixture.nativeElement.querySelector(
          '[data-testid="test-send-chat"]',
        ) as HTMLSelectElement;
        expect(select).toBeTruthy();
        expect(Array.from(select.options).map((option) => option.value)).toEqual(['', 'binding-9']);
        select.value = 'binding-9';
        select.dispatchEvent(new Event('change'));
        fixture.detectChanges();

        const send = Array.from(
          (fixture.nativeElement as HTMLElement).querySelectorAll<HTMLButtonElement>(
            '.test-send button',
          ),
        ).find((button) => button.textContent?.includes('Send')) as HTMLButtonElement;
        send.click();
        await flushMicrotasks();

        expect(api.testSend).toHaveBeenCalledWith(
          SCOPE,
          'tg',
          2,
          expect.objectContaining({ destination: 'binding-9' }),
        );
      });

      it('still asks an SMS template for a phone number', async () => {
        api.versions.mockResolvedValue([TELEGRAM_VERSION]);
        const page = fixture.componentInstance as unknown as Internals;
        await page.openVersions(TEMPLATE);
        page.onVersionRowAction('testSend', TELEGRAM_VERSION);
        fixture.detectChanges();

        expect(fixture.nativeElement.querySelector('#test-send-destination')).toBeTruthy();
        expect(fixture.nativeElement.querySelector('[data-testid="test-send-chat"]')).toBeNull();
      });
    });
  });

  describe('the two automation switches confirm themselves (row X.1)', () => {
    let toasts: Toasts;

    beforeEach(() => {
      toasts = TestBed.inject(Toasts);
      toasts.clear();
    });

    function flip(index: number, enabled: boolean): void {
      const box = (fixture.nativeElement as HTMLElement).querySelectorAll<HTMLInputElement>(
        '.checkbox input[type="checkbox"]',
      )[index];
      box.checked = enabled;
      box.dispatchEvent(new Event('change'));
    }

    it('names the setting, its new state and the level when a switch is flipped, since it saves at once', async () => {
      flip(0, true);
      await flushMicrotasks();

      expect(configApi.setValue).toHaveBeenCalledOnce();
      expect(toasts.visible().map((toast) => toast.message)).toEqual([
        expect.stringMatching(/: on — set for the brand$/),
      ]);
    });

    it('confirms the second switch the same way', async () => {
      flip(1, false);
      await flushMicrotasks();

      expect(toasts.visible().map((toast) => toast.message)).toEqual([
        expect.stringMatching(/: off — set for the brand$/),
      ]);
    });
  });
});
