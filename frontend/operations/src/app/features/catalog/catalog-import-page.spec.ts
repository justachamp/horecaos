import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';

import { LocationScope } from '../../core/api/operations-paths';
import { CurrentLocation } from '../../core/auth/current-location';
import { I18n } from '../../core/i18n/i18n';
import {
  BindingView,
  InstallationView,
  IntegrationsApi,
} from '../settings/integrations/integrations-api';
import { CatalogApi } from './catalog-api';
import { CatalogSummary } from './catalog-domain';
import { CatalogImportFileApi, CatalogImportStatus } from './catalog-import-file-api';
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

const CATALOG: CatalogSummary = {
  catalogId: 'catalog-1',
  code: 'MAIN',
  name: 'Main menu',
  status: 'ACTIVE',
};

const FILE_RUN_STATUS: CatalogImportStatus = {
  runId: 'file-run-1',
  status: 'DRY_RUN_COMPLETE',
  dryRun: true,
  catalogId: 'catalog-1',
  sourceFileName: 'products.csv',
  rowsTotal: 1,
  rowsProcessed: 1,
  rowsCreated: 1,
  rowsUpdated: 0,
  rowsSkipped: 0,
  rowsError: 0,
  failureReason: null,
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
    catalogs?: readonly CatalogSummary[];
    syncApi?: Partial<PosSyncApi>;
    mappingApi?: Partial<PosMappingApi>;
    catalogApi?: Partial<CatalogApi>;
    fileApi?: Partial<CatalogImportFileApi>;
    queryParams?: Readonly<Record<string, string>>;
  }): Promise<void> {
    const installations = options.installations ?? [INSTALLATION];
    const bindings = options.bindings ?? [BINDING];
    const runs = options.runs ?? [RUN];
    const catalogs = options.catalogs ?? [CATALOG];

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
        {
          provide: CatalogApi,
          useValue: {
            listCatalogs: () => of(catalogs),
            ...options.catalogApi,
          },
        },
        {
          provide: CatalogImportFileApi,
          useValue: {
            template: () => Promise.resolve('product_code,product_name\n'),
            export: () => Promise.resolve('product_code,product_name\n'),
            submit: () => Promise.resolve(FILE_RUN_STATUS.runId),
            status: () => Promise.resolve(FILE_RUN_STATUS),
            rows: () => Promise.resolve([]),
            history: () => Promise.resolve([]),
            ...options.fileApi,
          },
        },
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { queryParamMap: convertToParamMap(options.queryParams ?? {}) } },
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

  // ---------------------------------------------------------- row 1.2i's fix path: deep link

  it('a ?entityType=VARIANT&focusHorecaosId= deep link opens directly on the mapping tab, for that entity type, with the item pre-selected', async () => {
    let requestedEntityType: unknown;
    await render({
      queryParams: { entityType: 'VARIANT', focusHorecaosId: 'variant-9' },
      mappingApi: {
        unmapped: (_scope, _bindingId, entityType) => {
          requestedEntityType = entityType;
          return of({
            ...EMPTY_UNMAPPED,
            horecaosCandidates: [{ id: 'variant-9', name: 'Large Osh' }],
          });
        },
      },
    });

    expect(requestedEntityType).toBe('VARIANT');
    const mappingPane = fixture.nativeElement.querySelector('q-mapping-pane');
    expect(mappingPane).toBeTruthy();
    expect(
      mappingPane!.querySelector('[data-testid="q-combobox-input"]') as HTMLInputElement,
    ).toBeTruthy();
  });

  it('an unrecognized ?entityType= is ignored -- the mapping tab stays on PRODUCT rather than trusting an untyped query param', async () => {
    let requestedEntityType: unknown;
    await render({
      queryParams: { entityType: 'NOT_A_REAL_TYPE' },
      mappingApi: {
        unmapped: (_scope, _bindingId, entityType) => {
          requestedEntityType = entityType;
          return of(EMPTY_UNMAPPED);
        },
      },
    });

    // Never auto-opened the mapping tab from an untrusted param, and the
    // runs tab (the default) never calls unmapped() at all.
    expect(requestedEntityType).toBeUndefined();
    const tabs = Array.from(
      fixture.nativeElement.querySelectorAll('button[role="tab"]'),
    ) as HTMLElement[];
    const runsTab = tabs.find((tab) => tab.getAttribute('aria-selected') === 'true');
    expect(runsTab?.textContent).not.toContain('Соответствия');
  });

  // ---------------------------------------------------------- row 4.5b: CSV import

  function fileTab(): HTMLElement {
    return el('q-catalog-import-tab-file') as HTMLElement;
  }

  async function openFileTab(): Promise<void> {
    fileTab().click();
    await flushMicrotasks();
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
  }

  it('the CSV import tab is reachable even when the tenant has no POS binding at all', async () => {
    await render({ installations: [], bindings: [] });

    // The POS-only empty state is what the default "runs" tab shows --
    // proven by the pre-existing "shows the empty state" test above -- but
    // it must not be the only thing this page can ever show.
    expect(text()).toContain('No POS connection yet');

    await openFileTab();

    expect(el('q-catalog-import-file-section')).toBeTruthy();
    expect(fixture.nativeElement.querySelector('q-import-wizard')).toBeTruthy();
  });

  it('downloading the template fetches it from the file API and triggers a browser download', async () => {
    const originalCreateObjectURL = URL.createObjectURL;
    const originalRevokeObjectURL = URL.revokeObjectURL;
    URL.createObjectURL = vi.fn(() => 'blob:template');
    URL.revokeObjectURL = vi.fn();
    const clickSpy = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {});

    let templateCalls = 0;
    try {
      await render({
        fileApi: {
          template: () => {
            templateCalls++;
            return Promise.resolve('product_code,product_name\nPLOV-001,Osh\n');
          },
        },
      });
      await openFileTab();

      el('q-catalog-import-download-template')!.click();
      await flushMicrotasks();

      expect(templateCalls).toBe(1);
      expect(URL.createObjectURL).toHaveBeenCalledTimes(1);
      expect(clickSpy).toHaveBeenCalledTimes(1);
    } finally {
      URL.createObjectURL = originalCreateObjectURL;
      URL.revokeObjectURL = originalRevokeObjectURL;
      clickSpy.mockRestore();
    }
  });

  it('the file input accepts both .csv and .xlsx', async () => {
    await render({});
    await openFileTab();

    const input = fixture.nativeElement.querySelector(
      '[data-testid="import-wizard-file-input"]',
    ) as HTMLInputElement;

    expect(input.accept).toBe('.csv,.xlsx');
  });

  it('downloading the .xlsx template fetches a Blob and triggers a browser download', async () => {
    const originalCreateObjectURL = URL.createObjectURL;
    const originalRevokeObjectURL = URL.revokeObjectURL;
    URL.createObjectURL = vi.fn(() => 'blob:template-xlsx');
    URL.revokeObjectURL = vi.fn();
    const clickSpy = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {});

    let workbookCalls = 0;
    const workbookBlob = new Blob([new Uint8Array([80, 75, 3, 4])]);
    try {
      await render({
        fileApi: {
          templateWorkbook: () => {
            workbookCalls++;
            return Promise.resolve(workbookBlob);
          },
        },
      });
      await openFileTab();

      el('q-catalog-import-download-template-xlsx')!.click();
      await flushMicrotasks();

      expect(workbookCalls).toBe(1);
      expect(URL.createObjectURL).toHaveBeenCalledWith(workbookBlob);
      expect(clickSpy).toHaveBeenCalledTimes(1);
    } finally {
      URL.createObjectURL = originalCreateObjectURL;
      URL.revokeObjectURL = originalRevokeObjectURL;
      clickSpy.mockRestore();
    }
  });

  it('uploading a .xlsx file submits its exact bytes, Base64-encoded, never text-decoded', async () => {
    // Deliberately includes 0xFF and 0x00 -- not valid UTF-8 on their own,
    // so `FileReader.readAsText` would silently replace them (U+FFFD) and
    // corrupt the binary workbook. A correct round trip through
    // `arrayBuffer()` + Base64 preserves every byte.
    const bytes = new Uint8Array([0x50, 0x4b, 0x03, 0x04, 0x00, 0xff, 0x10, 0x9e]);
    let capturedContent: string | undefined;
    await render({
      fileApi: {
        submit: (_scope, request, dryRun) => {
          capturedContent = request.content;
          expect(request.fileName).toBe('catalog.xlsx');
          expect(dryRun).toBe(true);
          return Promise.resolve(FILE_RUN_STATUS.runId);
        },
        status: () => Promise.resolve(FILE_RUN_STATUS),
        rows: () => Promise.resolve([]),
      },
    });
    await openFileTab();

    const input = fixture.nativeElement.querySelector(
      '[data-testid="import-wizard-file-input"]',
    ) as HTMLInputElement;
    const file = new File([bytes], 'catalog.xlsx', {
      type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    });
    Object.defineProperty(input, 'files', { value: [file], configurable: true });
    input.dispatchEvent(new Event('change'));
    await flushMicrotasks();
    fixture.detectChanges();

    const checkButton = fixture.nativeElement.querySelector(
      '[data-testid="import-wizard-check"]',
    ) as HTMLButtonElement;
    checkButton.click();
    await flushMicrotasks();
    await flushMicrotasks();

    expect(capturedContent).toBeDefined();
    const decoded = Uint8Array.from(atob(capturedContent!), (char) => char.charCodeAt(0));
    expect(Array.from(decoded)).toEqual(Array.from(bytes));
  });

  it('checking a chosen file for problems submits a dry run for the selected catalog', async () => {
    let captured: unknown;
    await render({
      fileApi: {
        submit: (_scope, request, dryRun) => {
          captured = { catalogId: request.catalogId, fileName: request.fileName, dryRun };
          return Promise.resolve(FILE_RUN_STATUS.runId);
        },
        status: () => Promise.resolve(FILE_RUN_STATUS),
        rows: () => Promise.resolve([]),
      },
    });
    await openFileTab();

    const input = fixture.nativeElement.querySelector(
      '[data-testid="import-wizard-file-input"]',
    ) as HTMLInputElement;
    const file = new File(['product_code,product_name\nPLOV-001,Osh\n'], 'products.csv', {
      type: 'text/csv',
    });
    Object.defineProperty(input, 'files', { value: [file], configurable: true });
    input.dispatchEvent(new Event('change'));
    await flushMicrotasks();
    fixture.detectChanges();

    const checkButton = fixture.nativeElement.querySelector(
      '[data-testid="import-wizard-check"]',
    ) as HTMLButtonElement;
    checkButton.click();
    await flushMicrotasks();
    await flushMicrotasks();

    expect(captured).toEqual({ catalogId: 'catalog-1', fileName: 'products.csv', dryRun: true });
  });
});
