import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import { describe, expect, it, vi } from 'vitest';

import { APP_CONFIG, AppConfig } from '../../core/config/app-config';
import { ru } from '../../core/i18n/messages.ru';
import { TenantsApi } from '../tenants/tenants-api';
import { AccessApi, AuditEventDetail, AuditEventView } from './access-api';
import { AuditLog } from './audit-log';

const CONFIG: AppConfig = { apiBaseUrl: 'https://api.test.horecaos.uz', displayTimeZone: 'Asia/Tashkent' };

function event(id: string, actionCode: string): AuditEventView {
  return {
    id, recordedAt: '2026-09-10T10:00:00Z', tenantId: 'tenant-1', auditClass: 'BUSINESS', actionCode,
    actorType: 'USER', actorSubject: 'staff-a', actorDisplay: null, scopeType: 'TENANT', scopeId: 'tenant-1',
    targetType: 'Brand', targetId: 'brand-1', outcome: 'SUCCEEDED', reason: null, capabilityUsed: null,
  };
}

class FakeAccessApi {
  readonly auditEvents = vi.fn();
  readonly auditEvent = vi.fn<(...args: string[]) => Promise<AuditEventDetail>>();
}

describe('AuditLog', () => {
  let fixture: ComponentFixture<AuditLog>;
  let api: FakeAccessApi;

  async function create(): Promise<void> {
    api = new FakeAccessApi();
    api.auditEvents.mockResolvedValue({ items: [event('ev-1', 'brand.revised')], nextCursor: 'next-1' });
    localStorage.clear();
    sessionStorage.clear();
    await TestBed.configureTestingModule({
      imports: [AuditLog],
      providers: [
        { provide: APP_CONFIG, useValue: CONFIG },
        { provide: AccessApi, useValue: api },
        { provide: TenantsApi, useValue: { listTenants: vi.fn().mockResolvedValue({ items: [], nextCursor: null }) } },
        { provide: ActivatedRoute, useValue: { snapshot: { queryParamMap: convertToParamMap({ tenantId: 'tenant-1' }) } } },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(AuditLog);
    await settle();
  }

  async function settle(): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
    await new Promise((resolve) => setTimeout(resolve));
    fixture.detectChanges();
  }

  it('opens one event to show exactly what changed', async () => {
    await create();
    api.auditEvent.mockResolvedValue({
      ...event('ev-1', 'brand.revised'),
      onBehalfOfSubject: null, targetVersion: 3, evidenceReference: null, approvalRequestId: null,
      correlationId: 'corr-7', occurredAt: '2026-09-10T10:00:00Z',
      reason: 'Control-plane brand correction',
      changeDocument: { before: { displayName: 'Oshxona' }, after: { displayName: 'Oshxona No.1' } },
    });

    (fixture.nativeElement.querySelector('.rowButton') as HTMLButtonElement).click();
    await settle();

    expect(api.auditEvent).toHaveBeenCalledWith('tenant-1', 'ev-1');
    const detail = fixture.nativeElement.querySelector('.detailRow').textContent as string;
    expect(detail).toContain('Oshxona No.1');
    expect(detail).toContain('corr-7');
    expect(detail).toContain(ru['auditLog.detail.lead']);
  });

  it('filters by action and outcome, and loads the next page from the cursor', async () => {
    await create();
    expect(api.auditEvents).toHaveBeenCalledWith('tenant-1', { actionCode: '', outcome: '' });

    const action = fixture.nativeElement.querySelector('input[name="actionCode"]') as HTMLInputElement;
    action.value = 'tenant.suspended';
    action.dispatchEvent(new Event('input'));
    const outcome = fixture.nativeElement.querySelector('select[name="outcome"]') as HTMLSelectElement;
    outcome.value = 'REJECTED';
    outcome.dispatchEvent(new Event('change'));
    await settle();
    (fixture.nativeElement.querySelector('.filters') as HTMLFormElement).dispatchEvent(new Event('submit', { cancelable: true }));
    await settle();
    expect(api.auditEvents).toHaveBeenLastCalledWith('tenant-1', { actionCode: 'tenant.suspended', outcome: 'REJECTED' });

    api.auditEvents.mockResolvedValue({ items: [event('ev-2', 'tenant.suspended')], nextCursor: null });
    (fixture.nativeElement.querySelector('.more') as HTMLButtonElement).click();
    await settle();
    expect(api.auditEvents).toHaveBeenLastCalledWith('tenant-1', { actionCode: 'tenant.suspended', outcome: 'REJECTED' }, 'next-1');
    expect(fixture.nativeElement.querySelector('.more')).toBeNull();
  });
});
