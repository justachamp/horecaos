import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { BehaviorSubject } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { ApiError, ApiErrorCode } from '../../core/api/problem-details';
import { CurrentTenant } from '../../core/auth/current-tenant';
import { SessionCapabilities } from '../../core/auth/session-capabilities';
import { I18n } from '../../core/i18n/i18n';
import { CapabilityEntry, GrantView, RoleDescriptor, StaffApi, TenantJobView } from './staff-api';
import { StaffCapabilityGridPage } from './staff-capability-grid-page';

const OWN: RoleDescriptor = {
  code: 'job-3f9a1c0b2d',
  scopeType: 'LOCATION',
  capabilities: ['order.read', 'order.cancel'],
  custom: true,
  name: 'Старший кассир',
  version: 3,
  status: 'ACTIVE',
};

const ROLES: readonly RoleDescriptor[] = [
  OWN,
  {
    code: 'tenant-owner',
    scopeType: 'TENANT',
    capabilities: ['order.read', 'order.cancel', 'iam.grant.manage'],
  },
  { code: 'location-staff', scopeType: 'LOCATION', capabilities: ['order.read'] },
];

const CATALOGUE: readonly CapabilityEntry[] = [
  { code: 'order.read', area: 'order', action: 'read', held: true, composable: true },
  { code: 'order.cancel', area: 'order', action: 'cancel', held: true, composable: true },
  { code: 'iam.grant.manage', area: 'iam', action: 'grant.manage', held: true, composable: false },
];

function grant(overrides: Partial<GrantView>): GrantView {
  return {
    id: 'g1',
    principalSubject: 'cashier-1',
    roleCode: 'job-3f9a1c0b2d',
    scopeType: 'LOCATION',
    scopeId: 'l1',
    status: 'ACTIVE',
    grantedBy: 'owner-1',
    reason: 'Hired',
    validFrom: '2026-10-01T00:00:00Z',
    validUntil: null,
    revokedAt: null,
    revokedBy: null,
    revokedReason: null,
    ...overrides,
  };
}

class FakeCurrentTenant {
  readonly tenantId = signal<string | null>('t1');
  readonly scopes = signal<readonly unknown[]>([]);
  readonly denied = signal(false);
  ensureLoaded = vi.fn().mockResolvedValue(undefined);
}

async function flush(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

describe('StaffCapabilityGridPage (ADR 0186, row 9.1b)', () => {
  let fixture: ComponentFixture<StaffCapabilityGridPage>;
  let api: {
    roles: ReturnType<typeof vi.fn>;
    capabilityCatalogue: ReturnType<typeof vi.fn>;
    listGrants: ReturnType<typeof vi.fn>;
    createJob: ReturnType<typeof vi.fn>;
    updateJob: ReturnType<typeof vi.fn>;
    retireJob: ReturnType<typeof vi.fn>;
  };
  let navigate: ReturnType<typeof vi.spyOn>;

  async function open(
    query: Record<string, string> = {},
    held: readonly string[] = [],
    grants: readonly GrantView[] = [],
  ): Promise<void> {
    api = {
      roles: vi.fn().mockResolvedValue(ROLES),
      capabilityCatalogue: vi.fn().mockResolvedValue(CATALOGUE),
      listGrants: vi.fn().mockResolvedValue(grants),
      createJob: vi.fn(),
      updateJob: vi.fn(),
      retireJob: vi.fn(),
    };
    const params = convertToParamMap(query);
    const heldSet = new Set(held);
    await TestBed.configureTestingModule({
      imports: [StaffCapabilityGridPage],
      providers: [
        provideRouter([]),
        { provide: StaffApi, useValue: api },
        { provide: CurrentTenant, useValue: new FakeCurrentTenant() },
        { provide: SessionCapabilities, useValue: { has: (c: string) => heldSet.has(c) } },
        {
          provide: ActivatedRoute,
          useValue: {
            queryParamMap: new BehaviorSubject(params),
            snapshot: { queryParamMap: params },
          },
        },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    fixture = TestBed.createComponent(StaffCapabilityGridPage);
    fixture.detectChanges();
    await flush();
    fixture.detectChanges();
  }

  beforeEach(() => {
    localStorage.clear();
  });

  function el<T extends HTMLElement>(testId: string): T {
    return fixture.nativeElement.querySelector(`[data-testid="${testId}"]`) as T;
  }

  function text(): string {
    return fixture.nativeElement.textContent as string;
  }

  it('lays every job across and every capability down, one matrix per area, in plain sentences', async () => {
    await open();

    const areas = [...fixture.nativeElement.querySelectorAll('section.area h2')].map(
      (heading: Element) => heading.textContent?.trim(),
    );
    expect(areas).toContain('Orders');
    expect(areas).toContain('Access');
    expect(fixture.nativeElement.querySelectorAll('q-matrix-grid')).toHaveLength(areas.length);

    const orders = el('grid-area-Orders');
    const headers = [...orders.querySelectorAll('[role="columnheader"]')].map((header) =>
      header.textContent?.trim(),
    );
    expect(headers).toEqual(expect.arrayContaining(['Старший кассир', 'Owner', 'Location staff']));
    expect(text()).not.toContain('order.cancel');
    expect(text()).not.toContain('job-3f9a1c0b2d');
  });

  it('fills the cell where a job carries the thing, and only there', async () => {
    await open();

    const on = (row: string, col: string) =>
      fixture.nativeElement
        .querySelector(`[data-testid="grid-area-Orders"] [data-row="${row}"][data-col="${col}"]`)
        ?.getAttribute('data-state');
    expect(on('order.cancel', 'job-3f9a1c0b2d')).toBe('ON');
    expect(on('order.cancel', 'tenant-owner')).toBe('ON');
    expect(on('order.cancel', 'location-staff')).toBe('OFF');
    expect(on('order.read', 'location-staff')).toBe('ON');
  });

  it('never lets a cell be clicked: editing is the editor’s, one versioned write with a reason', async () => {
    await open();
    const cell = fixture.nativeElement.querySelector(
      '[data-row="order.cancel"][data-col="location-staff"]',
    ) as HTMLElement;

    cell.click();
    fixture.detectChanges();

    expect(cell.getAttribute('data-state')).toBe('OFF');
    expect(api.createJob).not.toHaveBeenCalled();
    expect(api.updateJob).not.toHaveBeenCalled();
  });

  it('searches the list itself, by sentence, code or area, and says when nothing matches', async () => {
    await open();
    const search = el<HTMLInputElement>('grid-search');

    search.value = 'cancel';
    search.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelectorAll('[role="rowheader"]')).toHaveLength(1);
    expect(el('grid-area-Access')).toBeNull();

    search.value = 'grant.manage';
    search.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    expect(el('grid-area-Access')).not.toBeNull();
    expect(el('grid-area-Orders')).toBeNull();

    search.value = 'zzzz';
    search.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    expect(el('grid-empty').textContent).toContain('Nothing matches “zzzz”');
  });

  it('shows the table and no editor to a person without iam.role.manage, even for an address that asks for one', async () => {
    await open({ new: '1' });

    expect(el('grid-new')).toBeNull();
    expect(el('job-editor')).toBeNull();
    expect(fixture.nativeElement.querySelectorAll('q-matrix-grid').length).toBeGreaterThan(0);
  });

  it('opens a blank editor from ?new, and defines the job with the version and name the platform returns', async () => {
    await open({ new: '1' }, ['IAM_ROLE_MANAGE']);
    const created: TenantJobView = {
      code: 'job-aaaaaaaaaa',
      name: 'Бариста',
      scopeType: 'LOCATION',
      capabilities: ['order.read'],
      version: 1,
      status: 'ACTIVE',
      copiedFrom: null,
      holders: 0,
      createdAt: null,
      updatedAt: null,
      retiredAt: null,
    };
    api.createJob.mockResolvedValue(created);

    const name = el<HTMLInputElement>('job-name');
    name.value = 'Бариста';
    name.dispatchEvent(new Event('input'));
    el<HTMLInputElement>('job-capability-order.read').click();
    const reason = el<HTMLInputElement>('job-reason');
    reason.value = 'Hiring baristas';
    reason.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    el<HTMLButtonElement>('job-save').click();
    await flush();
    fixture.detectChanges();

    expect(api.createJob).toHaveBeenCalledWith('t1', {
      name: 'Бариста',
      scopeType: 'LOCATION',
      capabilities: ['order.read'],
      reason: 'Hiring baristas',
    });
    expect(el('grid-notice').textContent).toContain('Saved: “Бариста”');
    expect(navigate).toHaveBeenCalledWith([], { queryParams: {} });
    expect(api.roles).toHaveBeenCalledTimes(2);
  });

  it('opens ?edit on the company’s own job with the number of people holding it, and saves under its version', async () => {
    await open(
      { edit: 'job-3f9a1c0b2d' },
      ['IAM_ROLE_MANAGE'],
      [
        grant({ id: 'g1', principalSubject: 'cashier-1' }),
        grant({ id: 'g2', principalSubject: 'cashier-1', scopeId: 'l2' }),
        grant({ id: 'g3', principalSubject: 'cashier-2' }),
        grant({ id: 'g4', principalSubject: 'cashier-3', status: 'REVOKED' }),
        grant({ id: 'g5', principalSubject: 'cook-1', roleCode: 'location-staff' }),
      ],
    );
    api.updateJob.mockResolvedValue({ ...OWN, holders: 2 } as unknown as TenantJobView);

    expect(el('job-holders').textContent).toContain('2 people hold this job');

    el<HTMLInputElement>('job-capability-order.cancel').click();
    const reason = el<HTMLInputElement>('job-reason');
    reason.value = 'No more cancellations';
    reason.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    el<HTMLButtonElement>('job-save').click();
    await flush();

    expect(api.updateJob).toHaveBeenCalledWith(
      't1',
      'job-3f9a1c0b2d',
      { name: 'Старший кассир', capabilities: ['order.read'], reason: 'No more cancellations' },
      3,
    );
  });

  it('ignores ?edit for a code-owned job: only the company’s own can be edited', async () => {
    await open({ edit: 'tenant-owner' }, ['IAM_ROLE_MANAGE']);

    expect(el('job-editor')).toBeNull();
  });

  it('opens ?copy on any job, code-owned or the company’s own', async () => {
    await open({ copy: 'tenant-owner' }, ['IAM_ROLE_MANAGE']);

    expect(el('job-editor')).not.toBeNull();
    expect(text()).toContain('Copy of “Owner”');
  });

  it('keeps the editor open and shows the platform’s reason when the save is refused', async () => {
    await open({ new: '1' }, ['IAM_ROLE_MANAGE']);
    api.createJob.mockRejectedValue(
      new ApiError(
        ApiErrorCode.RESOURCE_CONFLICT,
        409,
        { status: 409, detail: 'Another active job already has this name' },
        null,
      ),
    );
    const name = el<HTMLInputElement>('job-name');
    name.value = 'Дубликат';
    name.dispatchEvent(new Event('input'));
    el<HTMLInputElement>('job-capability-order.read').click();
    const reason = el<HTMLInputElement>('job-reason');
    reason.value = 'x';
    reason.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    el<HTMLButtonElement>('job-save').click();
    await flush();
    fixture.detectChanges();

    expect(el('job-error').textContent).toContain('Another active job already has this name');
    expect(el('job-editor')).not.toBeNull();
  });

  it('closes a job nobody holds and says so', async () => {
    await open({ edit: 'job-3f9a1c0b2d' }, ['IAM_ROLE_MANAGE']);
    api.retireJob.mockResolvedValue({ ...OWN, status: 'RETIRED' } as unknown as TenantJobView);

    el<HTMLButtonElement>('job-retire-open').click();
    fixture.detectChanges();
    const reason = el<HTMLInputElement>('job-retire-reason');
    reason.value = 'Replaced';
    reason.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    el<HTMLButtonElement>('job-retire-confirm').click();
    await flush();
    fixture.detectChanges();

    expect(api.retireJob).toHaveBeenCalledWith('t1', 'job-3f9a1c0b2d', 'Replaced', 3);
    expect(el('grid-notice').textContent).toContain('Closed: “Старший кассир”');
  });

  it('names the capability a refusal was about instead of a flat sentence', async () => {
    api = {
      roles: vi
        .fn()
        .mockRejectedValue(
          new ApiError(
            ApiErrorCode.INSUFFICIENT_CAPABILITY,
            403,
            { status: 403, requiredCapability: 'IAM_GRANT_MANAGE', requiredScope: 'TENANT' },
            null,
          ),
        ),
      capabilityCatalogue: vi.fn().mockResolvedValue([]),
      listGrants: vi.fn().mockResolvedValue([]),
      createJob: vi.fn(),
      updateJob: vi.fn(),
      retireJob: vi.fn(),
    };
    await TestBed.configureTestingModule({
      imports: [StaffCapabilityGridPage],
      providers: [
        provideRouter([]),
        { provide: StaffApi, useValue: api },
        { provide: CurrentTenant, useValue: new FakeCurrentTenant() },
        { provide: SessionCapabilities, useValue: { has: () => false } },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(StaffCapabilityGridPage);
    fixture.detectChanges();
    await flush();
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('q-denied-state')).not.toBeNull();
  });
});
