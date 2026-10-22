import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { CurrentTenant } from '../../../core/auth/current-tenant';
import { ConfigurationResolutionView } from '../../../core/api/configuration';
import { ApiError, ApiErrorCode } from '../../../core/api/problem-details';
import { I18n } from '../../../core/i18n/i18n';
import { Toasts } from '../../../shared/ui/toast';
import { AuditEventPage, AuditEventView, ActivityLogApi } from '../../staff/activity-log-api';
import { ConfigurationApi } from '../configuration-api';
import {
  ConsentType,
  DataPrivacyApi,
  RetentionWindow,
  TenantErasureRequest,
} from './data-privacy-api';
import { LocaleSetView } from '../../../core/i18n/locale-labels';
import { LABELLED_EGRESS_CODES } from './pii-audit-labels';
import { DataPrivacyPage } from './data-privacy-page';

function event(overrides: Partial<AuditEventView> = {}): AuditEventView {
  return {
    id: 'evt-1',
    recordedAt: '2026-09-01T10:00:00Z',
    tenantId: 't1',
    auditClass: 'SECURITY',
    actionCode: 'customer.contact.revealed',
    actorType: 'USER',
    actorSubject: 'support-1',
    actorDisplay: null,
    scopeType: 'TENANT',
    scopeId: null,
    targetType: 'customer_account',
    targetId: 'cust-1',
    outcome: 'SUCCEEDED',
    reason: 'Investigating a support ticket',
    capabilityUsed: 'customer.pii.reveal',
    approvalRequestId: null,
    correlationId: 'corr-1',
    occurredAt: '2026-09-01T10:00:00Z',
    ...overrides,
  };
}

function erasureRequest(overrides: Partial<TenantErasureRequest> = {}): TenantErasureRequest {
  return {
    id: 'req-1',
    customerAccountId: 'cust-9',
    status: 'PENDING',
    requestedVia: 'OPERATIONS',
    requestedByActorType: 'USER',
    requestedByActorId: 'operator-1',
    requestedAt: '2026-09-01T10:00:00Z',
    completedAt: null,
    completedByActorId: null,
    cancelledAt: null,
    cancelledByActorId: null,
    ...overrides,
  };
}

function consentType(overrides: Partial<ConsentType> = {}): ConsentType {
  return {
    id: 'ct-1',
    code: 'MARKETING_PROMOTIONS',
    labels: {
      ru: 'Маркетинговые рассылки',
      'uz-Latn': 'Marketing xabarnomalari',
      en: 'Marketing messages',
    },
    description: null,
    channelSpecific: true,
    policyVersion: '1',
    active: true,
    updatedAt: '2026-09-01T10:00:00Z',
    ...overrides,
  };
}

function retentionWindow(overrides: Partial<RetentionWindow> = {}): RetentionWindow {
  return {
    code: 'ordering.cart_retention_days',
    unit: 'DAYS',
    platformDefault: 90,
    floor: 1,
    configured: null,
    effective: 90,
    appliesTo: 'PER_TENANT',
    enforcedBy: 'CartRetentionSweeper',
    provisional: false,
    ...overrides,
  };
}

const LOCALE_SET: LocaleSetView = {
  locales: ['ru', 'uz-Latn', 'en'],
  defaultLocale: 'ru',
  configured: false,
};

function resolution(
  overrides: Partial<ConfigurationResolutionView> = {},
): ConfigurationResolutionView {
  return {
    keyCode: 'ordering.cart_retention_days',
    value: 90,
    cameFromDefault: true,
    source: 'CODE_DEFAULT',
    winningScope: null,
    inspectedLevels: [],
    describe: 'Platform default',
    currentVersionAtScope: null,
    ...overrides,
  };
}

class FakeCurrentTenant {
  readonly tenantId = signal<string | null>('t1');
  readonly denied = signal(false);
  ensureLoaded = vi.fn().mockResolvedValue(undefined);
}

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

interface SetUpOptions {
  readonly search?: (...args: never[]) => Promise<AuditEventPage>;
  readonly erasureWorklist?: (...args: never[]) => Promise<readonly TenantErasureRequest[]>;
  readonly consentTypes?: (...args: never[]) => Promise<readonly ConsentType[]>;
  readonly consentLocaleSet?: (...args: never[]) => Promise<LocaleSetView>;
  readonly retentionWindows?: (...args: never[]) => Promise<readonly RetentionWindow[]>;
  readonly configResolution?: (...args: never[]) => Promise<ConfigurationResolutionView>;
}

async function setUp(options: SetUpOptions = {}, locale: 'en' | 'ru' | 'uz-Latn' = 'en') {
  const activityLogApi = {
    search: vi.fn(options.search ?? (async () => ({ items: [], nextCursor: null }))),
  };
  const dataPrivacyApi = {
    erasureWorklist: vi.fn(options.erasureWorklist ?? (async () => [])),
    consentTypes: vi.fn(options.consentTypes ?? (async () => [consentType()])),
    consentLocaleSet: vi.fn(options.consentLocaleSet ?? (async () => LOCALE_SET)),
    retentionWindows: vi.fn(options.retentionWindows ?? (async () => [retentionWindow()])),
  };
  const configurationApi = {
    resolution: vi.fn(options.configResolution ?? (async () => resolution())),
    setValue: vi.fn(async () => resolution({ currentVersionAtScope: 1 })),
  };
  await TestBed.configureTestingModule({
    imports: [DataPrivacyPage],
    providers: [
      provideRouter([]),
      { provide: ActivityLogApi, useValue: activityLogApi },
      { provide: DataPrivacyApi, useValue: dataPrivacyApi },
      { provide: ConfigurationApi, useValue: configurationApi },
      { provide: CurrentTenant, useValue: new FakeCurrentTenant() },
    ],
  }).compileComponents();
  TestBed.inject(I18n).setLocale(locale);
  const fixture: ComponentFixture<DataPrivacyPage> = TestBed.createComponent(DataPrivacyPage);
  fixture.detectChanges();
  await flushMicrotasks();
  fixture.detectChanges();
  return { fixture, activityLogApi, dataPrivacyApi, configurationApi };
}

describe('DataPrivacyPage', () => {
  it('shows a PII reveal with its plain-language action and the stated purpose', async () => {
    const { fixture } = await setUp({
      search: async () => ({ items: [event()], nextCursor: null }),
    });
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Revealed phone or email');
    expect(text).toContain('Investigating a support ticket');
    expect(text).toContain('customer_account · cust-1');
  });

  it('asks the platform for the personal-data egress it registers, and keeps no list of its own', async () => {
    const { activityLogApi } = await setUp();
    const filters = activityLogApi.search.mock.calls[0]?.[1] as unknown as Record<string, unknown>;
    expect(filters['piiEgress']).toBe(true);
    // The security-class filter was part of the hand-kept list's narrowing; the backend registry replaces it.
    expect(filters['auditClass']).toBeUndefined();
  });

  it('shows an egress action the console has no words for under its code, rather than hiding it', async () => {
    const { fixture } = await setUp({
      search: async () => ({
        items: [event({ id: 'evt-new', actionCode: 'billing.invoice.recipient_revealed' })],
        nextCursor: null,
      }),
    });
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('billing.invoice.recipient_revealed');
  });

  it('has words for the reveals the old hand-kept list was missing', async () => {
    expect(LABELLED_EGRESS_CODES).toEqual(
      expect.arrayContaining([
        'customer.lead.revealed',
        'dinein.reservation.guest_revealed',
        'pricing.promotion.redemption.customer_revealed',
        'report.export.completed',
        'courier.customer_location.revealed',
        'telemetry.courier_track.revealed',
        'voice.screen_pop.caller_number_revealed',
      ]),
    );
    const { fixture } = await setUp({
      search: async () => ({
        items: [event({ id: 'evt-lead', actionCode: 'customer.lead.revealed' })],
        nextCursor: null,
      }),
    });
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Revealed a caller’s name or number from the call desk');
  });

  it('states honestly that only a data subject’s export and correction remain unbuilt', async () => {
    const { fixture } = await setUp();
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('ADR 0029');
    expect(text).toContain('export and the correction of their data remain');
    // The stale claims: legal hold, proof and retention are built now.
    expect(text).not.toContain('retention-expiry enforcement, legal hold and proof remain');
    expect(text).not.toContain('no data-subject export, correction, anonymisation');
  });

  it('shows the tenant-wide DSAR worklist with a pending request', async () => {
    const { fixture, dataPrivacyApi } = await setUp({
      erasureWorklist: async () => [erasureRequest()],
    });
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('cust-9');
    expect(text).toContain('Pending');
    expect(dataPrivacyApi.erasureWorklist).toHaveBeenCalledWith('t1', undefined);
  });

  it('shows the consent-type registry', async () => {
    const { fixture } = await setUp({ consentTypes: async () => [consentType()] });
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('MARKETING_PROMOTIONS');
    expect(text).toContain('Marketing messages');
  });

  it('reads both retention rows as real, editable values rather than a fixed enforced/not-enforced pair', async () => {
    const { fixture, configurationApi } = await setUp({
      configResolution: async (...args: unknown[]) => {
        const code = args[1] as string;
        return resolution({ keyCode: code, value: code.includes('telemetry') ? 30 : 90 });
      },
    });
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Courier location history');
    expect(text).toContain('Abandoned carts');
    // The stale claim this wave removes: neither row is a fixed status pill any more.
    expect(text).not.toContain('Not enforced');
    expect(configurationApi.resolution).toHaveBeenCalledWith(
      't1',
      'telemetry.track_retention_days',
      'TENANT',
    );
    expect(configurationApi.resolution).toHaveBeenCalledWith(
      't1',
      'ordering.cart_retention_days',
      'TENANT',
    );
    expect(configurationApi.resolution).toHaveBeenCalledWith(
      't1',
      'courier.applicant_retention_months',
      'TENANT',
    );
  });

  it('writes a changed retention value through ConfigurationApi', async () => {
    const { fixture, configurationApi } = await setUp();
    const input = fixture.nativeElement.querySelector(
      '[data-testid="retention-input-ordering.cart_retention_days"]',
    ) as HTMLInputElement;
    input.value = '120';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    (
      fixture.nativeElement.querySelector(
        '[data-testid="retention-save-ordering.cart_retention_days"]',
      ) as HTMLElement
    ).click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(configurationApi.setValue).toHaveBeenCalledWith(
      't1',
      'ordering.cart_retention_days',
      expect.objectContaining({ scopeType: 'TENANT', integerValue: 120 }),
    );
  });

  it('announces a saved retention period in the toast host, naming the company and the new value (row X.1)', async () => {
    const { fixture } = await setUp();
    const toasts = TestBed.inject(Toasts);
    toasts.clear();
    const input = fixture.nativeElement.querySelector(
      '[data-testid="retention-input-ordering.cart_retention_days"]',
    ) as HTMLInputElement;
    input.value = '120';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    (
      fixture.nativeElement.querySelector(
        '[data-testid="retention-save-ordering.cart_retention_days"]',
      ) as HTMLElement
    ).click();
    await flushMicrotasks();

    expect(toasts.visible().map((toast) => toast.message)).toEqual([
      'Abandoned carts: 120 days — set for the whole company',
    ]);
    toasts.clear();
  });

  it('says nothing in the toast host when a retention value is refused as invalid', async () => {
    const { fixture, configurationApi } = await setUp();
    const toasts = TestBed.inject(Toasts);
    toasts.clear();
    const input = fixture.nativeElement.querySelector(
      '[data-testid="retention-input-ordering.cart_retention_days"]',
    ) as HTMLInputElement;
    input.value = '-3';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    (
      fixture.nativeElement.querySelector(
        '[data-testid="retention-save-ordering.cart_retention_days"]',
      ) as HTMLElement
    ).click();
    await flushMicrotasks();

    expect(configurationApi.setValue).not.toHaveBeenCalled();
    expect(toasts.visible()).toEqual([]);
  });

  it('reads a consent purpose in the console’s language from the label map', async () => {
    const { fixture } = await setUp({ consentTypes: async () => [consentType()] }, 'uz-Latn');
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Marketing xabarnomalari');
  });

  it('falls back through the tenant’s default language, then any wording, when the console’s language has none', async () => {
    const { fixture } = await setUp(
      {
        consentTypes: async () => [
          consentType({
            id: 'ct-kk',
            code: 'LOYALTY_NEWS',
            labels: { ru: 'Новости программы', kk: 'Бағдарлама жаңалықтары' },
          }),
          consentType({ id: 'ct-only-kk', code: 'KK_ONLY', labels: { kk: 'Тек қазақша' } }),
        ],
      },
      'en',
    );
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Новости программы');
    expect(text).toContain('Тек қазақша');
  });

  it('shows the code when a purpose has no wording at all, never an empty cell', async () => {
    const { fixture } = await setUp({
      consentTypes: async () => [consentType({ code: 'BARE_CODE', labels: {} })],
    });
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('BARE_CODE');
  });

  it('shows the effective window and the platform floor beside each retention field', async () => {
    const { fixture, dataPrivacyApi } = await setUp({
      retentionWindows: async () => [
        retentionWindow({ configured: 30, effective: 30, floor: 1 }),
        retentionWindow({
          code: 'telemetry.track_retention_days',
          platformDefault: 30,
          floor: 14,
          configured: 10,
          effective: 30,
          appliesTo: 'ALL_TENANTS',
          enforcedBy: 'TrackRetentionSweeper',
        }),
        retentionWindow({
          code: 'courier.applicant_retention_months',
          unit: 'MONTHS',
          platformDefault: 12,
          floor: 1,
          effective: 12,
          enforcedBy: 'CourierApplicantRetentionSweeper',
        }),
      ],
    });
    expect(dataPrivacyApi.retentionWindows).toHaveBeenCalledWith('t1');

    const carts = (
      fixture.nativeElement.querySelector(
        '[data-testid="retention-window-ordering.cart_retention_days"]',
      ) as HTMLElement
    ).textContent;
    expect(carts).toContain('In force for your company: 30 days');
    expect(carts).toContain('Platform floor: 1 days');
    expect(carts).toContain('Applies to your company only.');

    const tracks = (
      fixture.nativeElement.querySelector(
        '[data-testid="retention-window-telemetry.track_retention_days"]',
      ) as HTMLElement
    ).textContent;
    // A shorter tenant value does not shorten a shared partition, and the card says so.
    expect(tracks).toContain('In force for your company: 30 days');
    expect(tracks).toContain('Platform floor: 14 days');
    expect(tracks).toContain('Shared with every company');

    const applicants = (
      fixture.nativeElement.querySelector(
        '[data-testid="retention-window-courier.applicant_retention_months"]',
      ) as HTMLElement
    ).textContent;
    expect(applicants).toContain('In force for your company: 12 months');
  });

  it('marks a retention value that is still provisional pending legal', async () => {
    const { fixture } = await setUp({
      retentionWindows: async () => [retentionWindow({ provisional: true })],
    });
    expect(
      fixture.nativeElement.querySelector('[data-testid="retention-provisional"]'),
    ).not.toBeNull();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain(
      'Provisional — awaiting legal confirmation',
    );
  });

  it('still shows the editable value when the windows cannot be read', async () => {
    const { fixture } = await setUp({
      retentionWindows: async () => {
        throw new ApiError(ApiErrorCode.RESOURCE_NOT_FOUND, 404, null, null);
      },
    });
    expect(
      fixture.nativeElement.querySelector(
        '[data-testid="retention-input-ordering.cart_retention_days"]',
      ),
    ).not.toBeNull();
    expect(fixture.nativeElement.querySelector('[data-testid="retention-effective"]')).toBeNull();
  });

  it('re-reads the windows after a retention value is saved, so the card shows what the new value does', async () => {
    const { fixture, dataPrivacyApi } = await setUp();
    const input = fixture.nativeElement.querySelector(
      '[data-testid="retention-input-ordering.cart_retention_days"]',
    ) as HTMLInputElement;
    input.value = '30';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    const before = dataPrivacyApi.retentionWindows.mock.calls.length;

    (
      fixture.nativeElement.querySelector(
        '[data-testid="retention-save-ordering.cart_retention_days"]',
      ) as HTMLElement
    ).click();

    await vi.waitFor(() => {
      expect(dataPrivacyApi.retentionWindows.mock.calls.length).toBeGreaterThan(before);
    });
  });

  it('shows the denied state on a 403 rather than an empty log', async () => {
    const { fixture } = await setUp({
      search: async () => {
        throw new ApiError(ApiErrorCode.INSUFFICIENT_CAPABILITY, 403, null, null);
      },
    });
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('No location in scope');
  });
});
