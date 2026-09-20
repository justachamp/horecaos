import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { describe, expect, it } from 'vitest';

import { LocationScope } from '../../core/api/operations-paths';
import { CurrentLocation } from '../../core/auth/current-location';
import { I18n } from '../../core/i18n/i18n';
import {
  BindingView,
  InstallationView,
  IntegrationsApi,
} from '../settings/integrations/integrations-api';
import { CatalogImportPage } from './catalog-import-page';
import { PosMappingApi, UnmappedExternalResponse } from './pos-mapping-api';
import { PosSyncApi, SyncRunSummary } from './pos-sync-api';

const LOCATION_SCOPE: LocationScope = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };

const INSTALLATION: InstallationView = {
  id: 'installation-1',
  category: 'POS',
  providerType: 'clopos',
  environmentCode: 'clopos-open-api-v2',
  displayName: 'Main till',
  status: 'ACTIVE',
  secretReference: null,
  lastConnectionStatus: null,
  adapterVersion: null,
  lastSecretRotatedAt: null,
  secretLastUsedAt: null,
  nonSensitiveConfig: null,
  webhookRegistered: false,
  webhookRegisteredAt: null,
};

const BINDING: BindingView = {
  id: 'binding-1',
  brandId: 'b1',
  locationId: null,
  status: 'ACTIVE',
  priority: 1,
  effectiveFrom: '2026-01-01T00:00:00Z',
  effectiveUntil: null,
};

const RUN: SyncRunSummary = {
  runId: 'run-1',
  bindingId: 'binding-1',
  status: 'REVIEW_REQUIRED',
  triggerType: 'MANUAL',
  dryRun: true,
  startedAt: '2026-09-05T00:00:00Z',
  completedAt: null,
  additionCount: 3,
  changeCount: 1,
  removalCount: 0,
  conflictCount: 0,
  lastErrorCode: null,
};

const EMPTY_UNMAPPED: UnmappedExternalResponse = {
  sourced: true,
  detail: null,
  entities: [],
  horecaosCandidates: [],
};

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

describe('CatalogImportPage', () => {
  let fixture: ComponentFixture<CatalogImportPage>;

  async function render(options: {
    locationScope?: LocationScope | null;
    locationDenied?: boolean;
    installations?: readonly InstallationView[];
    bindings?: readonly BindingView[];
    runs?: readonly SyncRunSummary[];
    syncApi?: Partial<PosSyncApi>;
    mappingApi?: Partial<PosMappingApi>;
  }): Promise<void> {
    const installations = options.installations ?? [INSTALLATION];
    const bindings = options.bindings ?? [BINDING];
    const runs = options.runs ?? [RUN];

    await TestBed.configureTestingModule({
      imports: [CatalogImportPage],
      providers: [
        {
          provide: CurrentLocation,
          useValue: {
            scope: signal<LocationScope | null>(
              'locationScope' in options ? (options.locationScope ?? null) : LOCATION_SCOPE,
            ),
            denied: signal(options.locationDenied ?? false),
            ensureLoaded: () => Promise.resolve(),
          },
        },
        {
          provide: IntegrationsApi,
          useValue: {
            listInstallations: () => Promise.resolve(installations),
            listBindings: () => Promise.resolve(bindings),
          },
        },
        {
          provide: PosSyncApi,
          useValue: {
            listRuns: () => of({ items: runs, nextCursor: null }),
            runDetail: () =>
              of({
                runId: 'run-1',
                bindingId: 'binding-1',
                status: 'REVIEW_REQUIRED',
                triggerType: 'MANUAL',
                dryRun: true,
                adapterVersion: 'CloposAdapter',
                fieldPolicyVersion: 1,
                startedAt: '2026-09-05T00:00:00Z',
                fetchedAt: null,
                normalizedAt: null,
                comparedAt: null,
                appliedAt: null,
                completedAt: null,
                receivedCount: 10,
                validCount: 10,
                invalidCount: 0,
                additionCount: 3,
                changeCount: 1,
                removalCount: 0,
                conflictCount: 0,
                pageCount: 1,
                walkKind: 'KEYSET',
                lastErrorCode: null,
                lastError: null,
              }),
            applyItems: () => of({ items: [], nextCursor: null }),
            start: () =>
              of({
                runId: 'run-2',
                status: 'REVIEW_REQUIRED',
                differenceCount: 0,
                conflictCount: 0,
                detail: '',
              }),
            ...options.syncApi,
          },
        },
        {
          provide: PosMappingApi,
          useValue: {
            list: () => of({ items: [], nextCursor: null }),
            unmapped: () => of(EMPTY_UNMAPPED),
            ...options.mappingApi,
          },
        },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(CatalogImportPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
  }

  function text(): string {
    return (fixture.nativeElement as HTMLElement).textContent ?? '';
  }

  function el(testId: string): HTMLElement | null {
    return (fixture.nativeElement as HTMLElement).querySelector<HTMLElement>(
      `[data-testid="${testId}"]`,
    );
  }

  it('shows the denied state when the operator has no location grant', async () => {
    await render({ locationScope: null, locationDenied: true });

    expect(fixture.nativeElement.querySelector('q-denied-state')).toBeTruthy();
  });

  it('shows the empty state when the tenant has no POS binding', async () => {
    await render({ installations: [], bindings: [] });

    expect(text()).toContain('No POS connection yet');
  });

  it('lists the run history for the selected binding, newest first as the API already ordered it', async () => {
    await render({});

    const rows = fixture.nativeElement.querySelectorAll('[data-testid="q-catalog-import-run-row"]');
    expect(rows.length).toBe(1);
    expect(text()).toContain('3'); // additionCount
  });

  it('selecting a run loads and shows its detail and per-item outcomes', async () => {
    await render({});

    const row = el('q-catalog-import-run-row') as HTMLElement;
    row.click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(el('q-catalog-import-run-detail')).toBeTruthy();
    expect(text()).toContain('CloposAdapter');
  });

  it('starting an import sends the dry-run flag, the import language and price-re-import choice, then reloads runs', async () => {
    let captured: unknown;
    await render({
      syncApi: {
        start: (_scope, _bindingId, dryRun, importLanguage, priceReImport) => {
          captured = { dryRun, importLanguage, priceReImport };
          return of({
            runId: 'run-2',
            status: 'REVIEW_REQUIRED',
            differenceCount: 0,
            conflictCount: 0,
            detail: '',
          });
        },
      },
    });

    fixture.componentInstance['importLanguage'].set('ru-RU');
    fixture.componentInstance['priceReImport'].set(true);
    fixture.detectChanges();

    const startButton = Array.from(fixture.nativeElement.querySelectorAll('button')).find(
      (button) => (button as HTMLElement).textContent?.includes('Start'),
    ) as HTMLButtonElement;
    startButton.click();
    await flushMicrotasks();

    expect(captured).toEqual({ dryRun: true, importLanguage: 'ru-RU', priceReImport: true });
  });

  it('switching to the mapping tab loads and renders the mapping pane for PRODUCT', async () => {
    let requestedEntityType: unknown;
    await render({
      mappingApi: {
        unmapped: (_scope, _bindingId, entityType) => {
          requestedEntityType = entityType;
          return of(EMPTY_UNMAPPED);
        },
      },
    });

    const tabs = Array.from(fixture.nativeElement.querySelectorAll('button[role="tab"]'));
    const mappingTab = tabs.find((tab) =>
      (tab as HTMLElement).textContent?.includes('Соответствия'),
    ) as HTMLElement;
    mappingTab.click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(requestedEntityType).toBe('PRODUCT');
    expect(fixture.nativeElement.querySelector('q-mapping-pane')).toBeTruthy();
  });
});
