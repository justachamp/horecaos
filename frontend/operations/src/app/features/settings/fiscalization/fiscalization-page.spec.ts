import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { LocationScope } from '../../../core/api/operations-paths';
import { CurrentLocation } from '../../../core/auth/current-location';
import { I18n } from '../../../core/i18n/i18n';
import {
  FiscalizationApi,
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
    assign: ReturnType<typeof vi.fn>;
  };

  beforeEach(async () => {
    api = {
      listLegalEntities: vi.fn().mockResolvedValue([ENTITY]),
      assignmentHistory: vi.fn().mockResolvedValue([ASSIGNMENT]),
      registerLegalEntity: vi.fn().mockResolvedValue(ENTITY),
      activateLegalEntity: vi.fn().mockResolvedValue({ ...ENTITY, status: 'ACTIVE' }),
      assign: vi.fn().mockResolvedValue(ASSIGNMENT),
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

  it('lists legal entities and the current location’s active assignment on Tab 1', () => {
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Rayhon LLC');
    expect(text).toContain('123456789');
    expect(text).toContain('2026-01-01');
  });

  it('shows an honest not-built note on Tab 2 (terminals)', () => {
    const tabs = fixture.nativeElement.querySelectorAll('.tab');
    (tabs[1] as HTMLButtonElement).click();
    fixture.detectChanges();

    expect((fixture.nativeElement as HTMLElement).textContent).toContain(
      'operations-spec/settings.md §10.7 Tab 2',
    );
  });

  it('shows an honest not-built note on Tab 3 (classification)', () => {
    const tabs = fixture.nativeElement.querySelectorAll('.tab');
    (tabs[2] as HTMLButtonElement).click();
    fixture.detectChanges();

    expect((fixture.nativeElement as HTMLElement).textContent).toContain(
      'operations-spec/settings.md §10.7 Tab 3',
    );
  });

  it('registers a new legal entity with a validated nine-digit TIN', async () => {
    const toggle = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('button'),
    ).find((button) => button.textContent?.includes('Register')) as HTMLButtonElement;
    toggle.click();
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

  it('re-registers the current location under a new entity from a start date, one transaction', async () => {
    const reregister = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('button'),
    ).find((button) => button.textContent?.includes('Re-register')) as HTMLButtonElement;
    reregister.click();
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
});
