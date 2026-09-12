import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { LocationScope } from '../../../core/api/operations-paths';
import { CurrentLocation } from '../../../core/auth/current-location';
import { I18n } from '../../../core/i18n/i18n';
import {
  FiscalCoverageSummary,
  FiscalizationApi,
  FiscalTerminalView,
  LegalEntityView,
  LocationFiscalAssignmentView,
} from './fiscalization-api';
import { FiscalizationPage } from './fiscalization-page';

const SCOPE: LocationScope = { tenantId: 'tenant-1', brandId: 'brand-1', locationId: 'location-1' };

const ENTITY: LegalEntityView = {
  id: 'entity-1',
  code: 'MAIN',
  legalName: 'Rayhon LLC',
  shortName: null,
  tin: '123456789',
  vatRegistered: true,
  vatCertificateReference: null,
  taxProfileId: null,
  registeredAddress: null,
  contactPhone: null,
  status: 'ACTIVE',
  version: 1,
};

const ASSIGNMENT: LocationFiscalAssignmentView = {
  id: 'assignment-1',
  brandId: 'brand-1',
  locationId: 'location-1',
  legalEntityId: 'entity-1',
  effectiveFrom: '2026-01-01',
  effectiveUntil: null,
  approvedBy: 'owner-1',
  approvalReference: null,
  version: 1,
};

const TERMINAL: FiscalTerminalView = {
  id: 'terminal-1',
  brandId: 'brand-1',
  locationId: 'location-1',
  legalEntityId: 'entity-1',
  kind: 'POS',
  providerBindingId: 'binding-1',
  terminalReference: 'KASSA-1',
  capabilitySnapshot: { IssueFiscalReceipt: true },
  capable: true,
  status: 'ACTIVE',
  lastHealthCheckAt: null,
  lastHealthStatus: null,
  version: 1,
};

const COVERAGE: FiscalCoverageSummary = {
  totalNodes: 3,
  unclassifiedCount: 1,
  nodes: [{ nodeType: 'FEE', nodeId: 'fee-1', name: null, categoryName: null, locationCount: 0 }],
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

describe('FiscalizationPage', () => {
  let fixture: ComponentFixture<FiscalizationPage>;
  let api: {
    listLegalEntities: ReturnType<typeof vi.fn>;
    assignmentHistory: ReturnType<typeof vi.fn>;
    registerLegalEntity: ReturnType<typeof vi.fn>;
    activateLegalEntity: ReturnType<typeof vi.fn>;
    updateLegalEntity: ReturnType<typeof vi.fn>;
    suspendLegalEntity: ReturnType<typeof vi.fn>;
    archiveLegalEntity: ReturnType<typeof vi.fn>;
    assign: ReturnType<typeof vi.fn>;
    listFiscalTerminals: ReturnType<typeof vi.fn>;
    registerFiscalTerminal: ReturnType<typeof vi.fn>;
    checkFiscalTerminalHealth: ReturnType<typeof vi.fn>;
    suspendFiscalTerminal: ReturnType<typeof vi.fn>;
    reactivateFiscalTerminal: ReturnType<typeof vi.fn>;
    retireFiscalTerminal: ReturnType<typeof vi.fn>;
    fiscalCoverage: ReturnType<typeof vi.fn>;
    classifyDeliveryFee: ReturnType<typeof vi.fn>;
  };

  beforeEach(async () => {
    api = {
      listLegalEntities: vi.fn().mockResolvedValue([ENTITY]),
      assignmentHistory: vi.fn().mockResolvedValue([ASSIGNMENT]),
      registerLegalEntity: vi.fn().mockResolvedValue(ENTITY),
      activateLegalEntity: vi.fn().mockResolvedValue({ ...ENTITY, status: 'ACTIVE' }),
      updateLegalEntity: vi.fn().mockResolvedValue({ ...ENTITY, legalName: 'Corrected LLC' }),
      suspendLegalEntity: vi.fn().mockResolvedValue({ ...ENTITY, status: 'SUSPENDED' }),
      archiveLegalEntity: vi.fn().mockResolvedValue({ ...ENTITY, status: 'ARCHIVED' }),
      assign: vi.fn().mockResolvedValue(ASSIGNMENT),
      listFiscalTerminals: vi.fn().mockResolvedValue([TERMINAL]),
      registerFiscalTerminal: vi.fn().mockResolvedValue(TERMINAL),
      checkFiscalTerminalHealth: vi
        .fn()
        .mockResolvedValue({ ...TERMINAL, lastHealthStatus: 'HEALTHY' }),
      suspendFiscalTerminal: vi.fn().mockResolvedValue({ ...TERMINAL, status: 'SUSPENDED' }),
      reactivateFiscalTerminal: vi.fn().mockResolvedValue({ ...TERMINAL, status: 'ACTIVE' }),
      retireFiscalTerminal: vi.fn().mockResolvedValue({ ...TERMINAL, status: 'RETIRED' }),
      fiscalCoverage: vi.fn().mockResolvedValue(COVERAGE),
      classifyDeliveryFee: vi.fn().mockResolvedValue(undefined),
    };

    await TestBed.configureTestingModule({
      imports: [FiscalizationPage],
      providers: [
        { provide: FiscalizationApi, useValue: api },
        { provide: CurrentLocation, useValue: new FakeCurrentLocation() },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(FiscalizationPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
  });

  function text(): string {
    return (fixture.nativeElement as HTMLElement).textContent ?? '';
  }

  function selectTab(index: number): void {
    const tabs = fixture.nativeElement.querySelectorAll('.tab');
    (tabs[index] as HTMLButtonElement).click();
    fixture.detectChanges();
  }

  function findButton(label: string): HTMLButtonElement {
    return Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('button')).find(
      (button) => button.textContent?.includes(label),
    ) as HTMLButtonElement;
  }

  it('lists legal entities and the current location’s active assignment on Tab 1', () => {
    expect(text()).toContain('Rayhon LLC');
    expect(text()).toContain('123456789');
    expect(text()).toContain('2026-01-01');
  });

  it('registers a new legal entity with a validated nine-digit TIN', async () => {
    findButton('Register legal entity').click();
    fixture.detectChanges();

    const codeInput = fixture.nativeElement.querySelector('#entity-code') as HTMLInputElement;
    const nameInput = fixture.nativeElement.querySelector('#entity-legal-name') as HTMLInputElement;
    const tinInput = fixture.nativeElement.querySelector('#entity-tin') as HTMLInputElement;
    codeInput.value = 'second';
    codeInput.dispatchEvent(new Event('input'));
    nameInput.value = 'Second Co';
    nameInput.dispatchEvent(new Event('input'));
    tinInput.value = '987654321';
    tinInput.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    const submit = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('.form__actions button'),
    ).find(
      (button) =>
        button.textContent?.includes('Register') || button.textContent?.includes('Submit'),
    ) as HTMLButtonElement;
    expect(submit.disabled).toBe(false);
    submit.click();
    await flushMicrotasks();

    expect(api.registerLegalEntity).toHaveBeenCalledWith(
      SCOPE,
      expect.objectContaining({ code: 'SECOND', legalName: 'Second Co', tin: '987654321' }),
    );
  });

  it('corrects a registered entity through the edit form — there was no way to do this before wave P34', async () => {
    findButton('Edit').click();
    fixture.detectChanges();

    const nameInput = fixture.nativeElement.querySelector('#edit-legal-name') as HTMLInputElement;
    nameInput.value = 'Corrected LLC';
    nameInput.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    findButton('Save').click();
    await flushMicrotasks();

    expect(api.updateLegalEntity).toHaveBeenCalledWith(
      SCOPE,
      'entity-1',
      expect.objectContaining({ legalName: 'Corrected LLC' }),
      1,
    );
  });

  it('suspends and archives an entity — neither had an HTTP surface before wave P34', async () => {
    findButton('Suspend').click();
    await flushMicrotasks();

    expect(api.suspendLegalEntity).toHaveBeenCalledWith(SCOPE, 'entity-1', 1);
  });

  it('re-registers the current location under a new entity from a start date, one transaction', async () => {
    findButton('Re-register').click();
    fixture.detectChanges();

    const select = fixture.nativeElement.querySelector('#assign-entity') as HTMLSelectElement;
    select.value = 'entity-1';
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();

    const submit = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('.form__actions button'),
    ).find(
      (button) => button.textContent?.includes('Assign') || button.textContent?.includes('Confirm'),
    ) as HTMLButtonElement;
    submit.click();
    await flushMicrotasks();

    expect(api.assign).toHaveBeenCalledWith(SCOPE, 'entity-1', expect.any(String));
  });

  it('renders the assignment table with its effective window, marking the open row current', () => {
    expect(text()).toContain('Effective from');
    expect(text()).toContain('Effective until');
    expect(text()).toContain('Current');
  });

  it('lists fiscal terminals on Tab 2 and lets an operator register one (wave P34: no schema had a caller before)', async () => {
    selectTab(1);
    await flushMicrotasks();
    fixture.detectChanges();

    expect(api.listFiscalTerminals).toHaveBeenCalledWith(SCOPE);
    expect(text()).toContain('KASSA-1');

    findButton('Register terminal').click();
    fixture.detectChanges();

    const referenceInput = fixture.nativeElement.querySelector(
      '#terminal-reference',
    ) as HTMLInputElement;
    referenceInput.value = 'KASSA-2';
    referenceInput.dispatchEvent(new Event('input'));
    const entitySelect = fixture.nativeElement.querySelector(
      '#terminal-legal-entity',
    ) as HTMLSelectElement;
    entitySelect.value = 'entity-1';
    entitySelect.dispatchEvent(new Event('change'));
    fixture.detectChanges();

    const submitTerminal = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('.form__actions button'),
    ).find((button) => button.textContent?.includes('Register')) as HTMLButtonElement;
    submitTerminal.click();
    await flushMicrotasks();

    expect(api.registerFiscalTerminal).toHaveBeenCalledWith(
      SCOPE,
      expect.objectContaining({ terminalReference: 'KASSA-2', legalEntityId: 'entity-1' }),
    );
  });

  it('suspends a fiscal terminal from Tab 2', async () => {
    selectTab(1);
    await flushMicrotasks();
    fixture.detectChanges();

    findButton('Disable').click();
    await flushMicrotasks();

    expect(api.suspendFiscalTerminal).toHaveBeenCalledWith(SCOPE, 'terminal-1', 1);
  });

  it('shows the coverage report on Tab 3 and flags the unclassified delivery fee', async () => {
    selectTab(2);
    await flushMicrotasks();
    fixture.detectChanges();

    expect(api.fiscalCoverage).toHaveBeenCalledWith(SCOPE);
    expect(text()).toContain('Unclassified: 1 of 3 items');
    expect(text()).toContain('Delivery fee');
  });

  it('classifies the delivery fee with an ИКПУ code and the marking control', async () => {
    selectTab(2);
    await flushMicrotasks();
    fixture.detectChanges();

    findButton('Classify delivery fee').click();
    fixture.detectChanges();

    const mxikInput = fixture.nativeElement.querySelector('#delivery-fee-mxik') as HTMLInputElement;
    mxikInput.value = '10101001001000000';
    mxikInput.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    const submit = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('.form__actions button'),
    ).find((button) => button.textContent?.includes('Save')) as HTMLButtonElement;
    submit.click();
    await flushMicrotasks();

    expect(api.classifyDeliveryFee).toHaveBeenCalledWith(
      SCOPE,
      expect.objectContaining({ mxikCode: '10101001001000000' }),
    );
  });
});
