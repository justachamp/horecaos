import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { CurrentTenant } from '../../../core/auth/current-tenant';
import { ConfigurationResolutionView } from '../../../core/api/configuration';
import { ApiError, ApiErrorCode } from '../../../core/api/problem-details';
import { I18n } from '../../../core/i18n/i18n';
import { AuditEventPage, AuditEventView, ActivityLogApi } from '../../staff/activity-log-api';
import { ConfigurationApi } from '../configuration-api';
import { ConsentType, DataPrivacyApi, TenantErasureRequest } from './data-privacy-api';
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
    labelRu: 'Маркетинговые рассылки',
    labelUz: 'Marketing xabarnomalari',
    labelEn: 'Marketing messages',
    description: null,
    channelSpecific: true,
    policyVersion: '1',
    active: true,
    updatedAt: '2026-09-01T10:00:00Z',
    ...overrides,
  };
}

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
  readonly configResolution?: (...args: never[]) => Promise<ConfigurationResolutionView>;
}

async function setUp(options: SetUpOptions = {}) {
  const activityLogApi = {
    search: vi.fn(options.search ?? (async () => ({ items: [], nextCursor: null }))),
  };
  const dataPrivacyApi = {
    erasureWorklist: vi.fn(options.erasureWorklist ?? (async () => [])),
    consentTypes: vi.fn(options.consentTypes ?? (async () => [consentType()])),
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
  TestBed.inject(I18n).setLocale('en');
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

  it('never shows an audit event outside the known personal-data action codes', async () => {
    const { fixture } = await setUp({
      search: async () => ({
        items: [event({ id: 'evt-approval', actionCode: 'approval.decision.refused' })],
        nextCursor: null,
      }),
    });
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).not.toContain('approval.decision.refused');
    expect(text).toContain('Nobody revealed or exported personal data in this period.');
  });

  it('states honestly that export and correction, not erasure, are what remains unbuilt', async () => {
    const { fixture } = await setUp();
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('ADR 0029');
    expect(text).toContain('export, correction, retention-expiry enforcement');
    // The stale claim this wave removes: erasure is built, not absent.
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

  it('shows the denied state on a 403 rather than an empty log', async () => {
    const { fixture } = await setUp({
      search: async () => {
        throw new ApiError(ApiErrorCode.INSUFFICIENT_CAPABILITY, 403, null, null);
      },
    });
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('No location in scope');
  });
});
