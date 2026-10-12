import { ComponentFixture, TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';

import { LocationScope } from '../../core/api/operations-paths';
import { ApiError } from '../../core/api/problem-details';
import { I18n } from '../../core/i18n/i18n';
import { HeadcountApi, ProductivityDocument } from './headcount-api';
import { StationResponse } from './kitchen-api';
import { ProductivityEditor } from './productivity-editor';

const SCOPE: LocationScope = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };

const STATIONS: readonly StationResponse[] = [
  {
    stationId: 'grill',
    code: 'GRILL-1',
    role: 'GRILL',
    displayNameRu: 'Гриль',
    displayNameUz: 'Gril',
    displayNameEn: 'Grill',
    sortOrder: 1,
    fallback: false,
    status: 'ACTIVE',
    version: 1,
  },
  {
    stationId: 'pass',
    code: 'PASS',
    role: 'EXPO',
    displayNameRu: 'Раздача',
    displayNameUz: 'Tarqatish',
    displayNameEn: 'Pass',
    sortOrder: 2,
    fallback: true,
    status: 'ACTIVE',
    version: 1,
  },
];

function document_(overrides: Partial<ProductivityDocument> = {}): ProductivityDocument {
  return {
    schema: 1,
    roles: {},
    stations: {},
    isDefault: true,
    winningScope: null,
    policyId: null,
    policyVersion: 0,
    versionAtScope: 0,
    levels: [
      { scopeType: 'LOCATION', authored: false },
      { scopeType: 'BRAND', authored: false },
      { scopeType: 'TENANT', authored: false },
    ],
    ...overrides,
  };
}

const TENANT_FIGURES = document_({
  roles: { GRILL: 20, HOT: 30 },
  isDefault: false,
  winningScope: 'TENANT',
  policyId: 'p1',
  policyVersion: 2,
  levels: [
    { scopeType: 'LOCATION', authored: false },
    { scopeType: 'BRAND', authored: false },
    { scopeType: 'TENANT', authored: true },
  ],
});

async function flush(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

describe('ProductivityEditor', () => {
  let fixture: ComponentFixture<ProductivityEditor>;

  async function render(
    api: Partial<HeadcountApi>,
    options: { canManage?: boolean } = {},
  ): Promise<void> {
    await TestBed.configureTestingModule({
      imports: [ProductivityEditor],
      providers: [{ provide: HeadcountApi, useValue: api }],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(ProductivityEditor);
    fixture.componentRef.setInput('scope', SCOPE);
    fixture.componentRef.setInput('stations', STATIONS);
    fixture.componentRef.setInput('canManage', options.canManage ?? true);
    fixture.detectChanges();
    await flush();
    fixture.detectChanges();
  }

  function host(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  function byId(testId: string): HTMLElement | null {
    return host().querySelector(`[data-testid="${testId}"]`);
  }

  function type(testId: string, value: string): void {
    const input = byId(testId) as HTMLInputElement;
    input.value = value;
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
  }

  async function openForm(): Promise<void> {
    const action = [...host().querySelectorAll('button')].find((button) =>
      /Override|Edit|Set value/i.test(button.textContent ?? ''),
    );
    expect(action).toBeDefined();
    action?.click();
    await flush();
    fixture.detectChanges();
  }

  it('reads the figures in force at this branch and says where they come from', async () => {
    await render({ productivity: () => Promise.resolve(TENANT_FIGURES) });

    const text = host().textContent ?? '';
    expect(text).toContain('GRILL 20');
    expect(text).toContain('HOT 30');
    expect(text).toContain('From the company');
  });

  it('says plainly that no pace is stated when the document is the empty default', async () => {
    await render({ productivity: () => Promise.resolve(document_()) });

    expect(host().textContent).toContain('None stated');
  });

  it('shows the figures read-only to a manager without kitchen.productivity.manage', async () => {
    await render({ productivity: () => Promise.resolve(TENANT_FIGURES) }, { canManage: false });

    expect(byId('pace-readonly')?.textContent).toContain('GRILL 20');
    expect(host().querySelector('q-inherited-field')).toBeNull();
  });

  it('pre-fills the form with the document in force and publishes it whole under the version read', async () => {
    const publish = vi.fn().mockResolvedValue(TENANT_FIGURES);
    const productivity = vi
      .fn()
      .mockResolvedValueOnce(TENANT_FIGURES) // the field
      .mockResolvedValueOnce(document_({ ...TENANT_FIGURES, versionAtScope: 0 })); // the LOCATION level, opened
    await render({ productivity, publishProductivity: publish });
    await openForm();

    expect((byId('pace-role-GRILL') as HTMLInputElement).value).toBe('20');
    expect(byId('pace-station-PASS')).toBeNull();
    expect(byId('pace-station-GRILL-1')).not.toBeNull();

    type('pace-role-GRILL', '24');
    type('pace-station-GRILL-1', '35');
    type('pace-reason', 'measured on a Friday');
    (byId('pace-save') as HTMLButtonElement).click();
    await flush();

    expect(publish).toHaveBeenCalledWith(
      SCOPE,
      'LOCATION',
      {
        roles: { GRILL: 24, HOT: 30 },
        stations: { 'GRILL-1': 35 },
        reason: 'measured on a Friday',
      },
      0,
    );
  });

  it('will not save a figure outside 1..1000, a decimal, or a missing reason', async () => {
    const publish = vi.fn();
    await render({
      productivity: () => Promise.resolve(document_()),
      publishProductivity: publish,
    });
    await openForm();

    type('pace-reason', 'because');
    type('pace-role-GRILL', '0');
    expect((byId('pace-save') as HTMLButtonElement).disabled).toBe(true);
    type('pace-role-GRILL', '1001');
    expect((byId('pace-save') as HTMLButtonElement).disabled).toBe(true);
    type('pace-role-GRILL', '2.5');
    expect((byId('pace-save') as HTMLButtonElement).disabled).toBe(true);
    expect(byId('pace-invalid')).not.toBeNull();
    type('pace-role-GRILL', '20');
    expect((byId('pace-save') as HTMLButtonElement).disabled).toBe(false);
    type('pace-reason', '   ');
    expect((byId('pace-save') as HTMLButtonElement).disabled).toBe(true);
    expect(publish).not.toHaveBeenCalled();
  });

  it('re-reads the document when another level is chosen and saves at that level', async () => {
    const publish = vi.fn().mockResolvedValue(TENANT_FIGURES);
    const productivity = vi.fn().mockResolvedValue(document_({ versionAtScope: 3 }));
    await render({ productivity, publishProductivity: publish });
    await openForm();

    const select = byId('pace-level') as HTMLSelectElement;
    select.value = 'BRAND';
    select.dispatchEvent(new Event('change'));
    await flush();
    fixture.detectChanges();

    expect(productivity).toHaveBeenLastCalledWith(SCOPE, 'BRAND');
    type('pace-role-HOT', '25');
    type('pace-reason', 'a slower cuisine');
    (byId('pace-save') as HTMLButtonElement).click();
    await flush();

    expect(publish).toHaveBeenCalledWith(
      SCOPE,
      'BRAND',
      { roles: { HOT: 25 }, stations: {}, reason: 'a slower cuisine' },
      3,
    );
  });

  it('tells the manager someone else changed the figures, and offers no further save', async () => {
    const publish = vi.fn().mockRejectedValue(new ApiError('STALE_VERSION', 409, null, 'corr-1'));
    await render({
      productivity: () => Promise.resolve(document_()),
      publishProductivity: publish,
    });
    await openForm();
    type('pace-role-GRILL', '20');
    type('pace-reason', 'first');

    (byId('pace-save') as HTMLButtonElement).click();
    await flush();
    fixture.detectChanges();

    expect(byId('pace-stale')).not.toBeNull();
    expect((byId('pace-save') as HTMLButtonElement).disabled).toBe(true);
  });

  it('keeps the form open and shows the refusal when the platform refuses the document', async () => {
    const publish = vi.fn().mockRejectedValue(new ApiError('FORBIDDEN', 403, null, 'corr-2'));
    await render({
      productivity: () => Promise.resolve(document_()),
      publishProductivity: publish,
    });
    await openForm();
    type('pace-role-GRILL', '20');
    type('pace-reason', 'x');

    (byId('pace-save') as HTMLButtonElement).click();
    await flush();
    fixture.detectChanges();

    expect(byId('pace-save-error')).not.toBeNull();
    expect(byId('pace-form')).not.toBeNull();
  });

  it('tells the plan to recompute once a publication lands', async () => {
    const publish = vi.fn().mockResolvedValue(TENANT_FIGURES);
    await render({
      productivity: () => Promise.resolve(document_()),
      publishProductivity: publish,
    });
    const saved = vi.fn();
    fixture.componentInstance.saved.subscribe(saved);
    await openForm();
    type('pace-role-GRILL', '20');
    type('pace-reason', 'x');

    (byId('pace-save') as HTMLButtonElement).click();
    await flush();
    fixture.detectChanges();

    expect(saved).toHaveBeenCalledTimes(1);
    expect(byId('pace-form')).toBeNull();
    expect(byId('pace-saved')).not.toBeNull();
  });
});
