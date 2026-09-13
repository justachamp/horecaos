import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { LocationScope } from '../../../core/api/operations-paths';
import { CurrentLocation } from '../../../core/auth/current-location';
import { I18n } from '../../../core/i18n/i18n';
import { ConfigurationApi } from '../configuration-api';
import { NotificationsApi, TemplateResponse, VersionGroup } from './notifications-api';
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
      create: vi.fn().mockResolvedValue('template-2'),
      versions: vi.fn().mockResolvedValue([]),
      version: vi.fn().mockResolvedValue([]),
      variableCatalogue: vi.fn().mockResolvedValue([]),
      activate: vi.fn().mockResolvedValue(undefined),
      addVersion: vi.fn().mockResolvedValue({ templateId: 'template-2', versionNumber: 1, awaitsProviderReview: false }),
      testSend: vi.fn().mockResolvedValue({ status: 'ACCEPTED', providerStatus: null, errorCode: null }),
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
    });
    // The old behaviour hard-coded variablesSchema: {} inside this same call;
    // now there is no addVersion call at all from the create step — authoring
    // moved entirely to `TemplateEditor`, which is what proves the fix.
    expect(api.addVersion).not.toHaveBeenCalled();
    expect(
      (fixture.nativeElement as HTMLElement).querySelector('q-notification-template-editor'),
    ).toBeTruthy();
  });

  it('renders the moderation column and warns before activating a PENDING version (gap map row 10.9c)', async () => {
    api.versions.mockResolvedValue([PENDING_VERSION]);
    await (fixture.componentInstance as unknown as { openVersions(t: TemplateResponse): Promise<void> })
      .openVersions(TEMPLATE);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Awaiting gateway');

    (fixture.componentInstance as unknown as { onVersionRowAction(id: string, g: VersionGroup): void })
      .onVersionRowAction('activate', PENDING_VERSION);
    fixture.detectChanges();

    const dialog = (fixture.nativeElement as HTMLElement).querySelector('q-confirm-dialog');
    expect(dialog).toBeTruthy();
    // The exact silent failure this wave's row is named after: a merchant
    // used to see nothing here and would only discover the suppression from
    // a customer who never got a message.
    expect(dialog?.textContent).toContain('Awaiting gateway');
  });
});
