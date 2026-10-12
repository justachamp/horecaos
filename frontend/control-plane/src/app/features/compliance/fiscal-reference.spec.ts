import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { describe, expect, it, vi } from 'vitest';

import { ApiError } from '../../core/api/problem';
import { APP_CONFIG, AppConfig } from '../../core/config/app-config';
import { ru } from '../../core/i18n/messages.ru';
import {
  FiscalReferenceApi,
  ImportFinding,
  ImportRun,
  MxikReferenceRow,
  ReferenceStatus,
} from './fiscal-reference-api';
import { FiscalReference } from './fiscal-reference';

const CONFIG: AppConfig = {
  apiBaseUrl: 'https://api.test.horecaos.uz',
  displayTimeZone: 'Asia/Tashkent',
};

const LOADED: ReferenceStatus = {
  loaded: true,
  datasetVersion: 3,
  importedAt: '2026-10-01T09:00:00Z',
  sourcePublishedOn: '2026-09-28',
  validRows: 5210,
  withdrawnRows: 14,
  ageDays: 7,
  maxAgeDays: 45,
  stale: false,
};

const NOT_LOADED: ReferenceStatus = {
  loaded: false,
  datasetVersion: null,
  importedAt: null,
  sourcePublishedOn: null,
  validRows: 0,
  withdrawnRows: 0,
  ageDays: null,
  maxAgeDays: 45,
  stale: false,
};

function run(overrides: Partial<ImportRun> = {}): ImportRun {
  return {
    runId: 'run-1',
    status: 'VALIDATED',
    mode: 'FULL_SNAPSHOT',
    mappingProfile: 'TASNIF_CSV_V1',
    sourceFileName: 'tasnif.csv',
    sourceSha256: 'a'.repeat(64),
    sourcePublishedOn: null,
    rowsTotal: 7,
    rowsNew: 7,
    rowsChanged: 0,
    rowsUnchanged: 0,
    rowsWithdrawn: 0,
    rowsReinstated: 0,
    rowsError: 0,
    labelsBorrowed: 1,
    codeLengthHistogram: { '17': 7 },
    withdrawalThresholdPercent: 2,
    acknowledgedWithdrawals: null,
    createdBy: 'admin',
    createdAt: '2026-10-08T08:00:00Z',
    appliedBy: null,
    appliedAt: null,
    datasetVersion: null,
    failureReason: null,
    version: 2,
    sampleErrors: [],
    ...overrides,
  };
}

const NO_FINDINGS = { items: [] as ImportFinding[], nextCursor: null };

describe('FiscalReference', () => {
  let fixture: ComponentFixture<FiscalReference>;
  let api: {
    status: ReturnType<typeof vi.fn>;
    recentRuns: ReturnType<typeof vi.fn>;
    search: ReturnType<typeof vi.fn>;
    dryRun: ReturnType<typeof vi.fn>;
    run: ReturnType<typeof vi.fn>;
    findings: ReturnType<typeof vi.fn>;
    apply: ReturnType<typeof vi.fn>;
    reject: ReturnType<typeof vi.fn>;
  };

  async function create(status: ReferenceStatus = LOADED): Promise<void> {
    api = {
      status: vi.fn().mockResolvedValue(status),
      recentRuns: vi.fn().mockResolvedValue([]),
      search: vi.fn().mockResolvedValue([]),
      dryRun: vi.fn().mockResolvedValue(run()),
      run: vi.fn().mockResolvedValue(run()),
      findings: vi.fn().mockResolvedValue(NO_FINDINGS),
      apply: vi.fn().mockResolvedValue(run({ status: 'APPLIED', datasetVersion: 4, version: 3 })),
      reject: vi.fn().mockResolvedValue(run({ status: 'REJECTED', version: 3 })),
    };
    localStorage.clear();
    await TestBed.configureTestingModule({
      imports: [FiscalReference],
      providers: [
        provideRouter([]),
        { provide: APP_CONFIG, useValue: CONFIG },
        { provide: FiscalReferenceApi, useValue: api },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(FiscalReference);
    await settle();
  }

  async function settle(): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
    await new Promise((resolve) => setTimeout(resolve));
    fixture.detectChanges();
  }

  function el<T extends HTMLElement>(selector: string): T {
    return fixture.nativeElement.querySelector(selector) as T;
  }

  function text(): string {
    return fixture.nativeElement.textContent as string;
  }

  async function choose(name = 'tasnif.csv'): Promise<File> {
    const file = new File(['code,label ru\n1,x\n'], name, { type: 'text/csv' });
    const input = el<HTMLInputElement>('.fileInput');
    Object.defineProperty(input, 'files', { value: [file], configurable: true });
    input.dispatchEvent(new Event('change'));
    await settle();
    return file;
  }

  async function validate(): Promise<void> {
    el<HTMLFormElement>('.importForm').dispatchEvent(new Event('submit', { cancelable: true }));
    await settle();
  }

  async function type(selector: string, value: string): Promise<void> {
    const input = el<HTMLInputElement>(selector);
    input.value = value;
    input.dispatchEvent(new Event('input'));
    await settle();
  }

  it('says the list is not loaded, before anything else, when it has never been imported', async () => {
    await create(NOT_LOADED);

    expect(el('[data-notice="notLoaded"]')).not.toBeNull();
    expect(el('[data-block="status"]')).toBeNull();
  });

  it('shows the dataset, the counts and the authority date once loaded, and no stale warning while fresh', async () => {
    await create();

    expect(el('[data-notice="notLoaded"]')).toBeNull();
    expect(el('[data-status="dataset"]').textContent).toContain('3');
    expect(el('[data-status="counts"]').textContent).toContain('5210');
    expect(el('[data-status="counts"]').textContent).toContain('14');
    expect(text()).toContain('2026-09-28');
    expect(el('[data-notice="stale"]')).toBeNull();
  });

  it('warns when the list is older than the configured age, naming both numbers', async () => {
    await create({ ...LOADED, ageDays: 60, stale: true });

    expect(el('[data-notice="stale"]').textContent).toContain('60');
    expect(el('[data-notice="stale"]').textContent).toContain('45');
  });

  it('sends the chosen file and mode as a dry run, and shows what it found before anything is applied', async () => {
    await create();
    const file = await choose();
    await type('.dateInput', '2026-10-01');

    await validate();

    expect(api.dryRun).toHaveBeenCalledWith(file, 'FULL_SNAPSHOT', '2026-10-01');
    expect(el('[data-run="counts"]').textContent).toContain('7');
    expect(el('[data-run="borrowed"]').textContent).toContain('1');
    expect(el('[data-run="lengths"]').textContent).toContain('17: 7');
    expect(api.apply).not.toHaveBeenCalled();
    expect(el<HTMLButtonElement>('.applyButton').disabled).toBe(false);
    expect(el('[data-notice="held"]')).toBeNull();
  });

  it('applies a validated run against the version it read, then reloads the status', async () => {
    await create();
    await choose();
    await validate();

    el<HTMLButtonElement>('.applyButton').click();
    await settle();

    expect(api.apply).toHaveBeenCalledWith(
      expect.objectContaining({ runId: 'run-1', version: 2 }),
      null,
    );
    expect(el('[data-run="outcome"]').textContent).toContain('4');
    expect(el('.applyButton')).toBeNull();
    expect(api.status).toHaveBeenCalledTimes(3);
  });

  it('holds an alarming run until the exact withdrawal count is typed', async () => {
    await create();
    api.dryRun.mockResolvedValue(run({ status: 'HELD_FOR_REVIEW', rowsWithdrawn: 50 }));
    await choose();
    await validate();

    expect(el('[data-notice="held"]').textContent).toContain('50');
    expect(el<HTMLButtonElement>('.applyButton').disabled).toBe(true);

    await type('.ackInput', '49');
    expect(el<HTMLButtonElement>('.applyButton').disabled).toBe(true);

    await type('.ackInput', '50');
    expect(el<HTMLButtonElement>('.applyButton').disabled).toBe(false);
    el<HTMLButtonElement>('.applyButton').click();
    await settle();

    expect(api.apply).toHaveBeenCalledWith(expect.objectContaining({ runId: 'run-1' }), 50);
  });

  it('rejects a run and says the list is unchanged', async () => {
    await create();
    await choose();
    await validate();

    el<HTMLButtonElement>('.rejectButton').click();
    await settle();

    expect(api.reject).toHaveBeenCalledOnce();
    expect(el('[data-run="outcome"]').textContent).toContain(ru['fiscalReference.run.rejected']);
    expect(api.apply).not.toHaveBeenCalled();
  });

  it('names an unknown column in the reader’s language instead of showing the server text', async () => {
    await create();
    api.dryRun.mockRejectedValue(
      new ApiError({
        status: 422,
        code: 'UNPROCESSABLE_STATE',
        detail: 'The file has columns this mapping profile does not know: mystery',
        findingCode: 'UNKNOWN_HEADER',
      }),
    );
    await choose();
    await validate();

    expect(el('[data-error="import"]').textContent).toContain(
      ru['fiscalReference.refusal.UNKNOWN_HEADER'],
    );
    expect(text()).not.toContain('mapping profile');
    expect(el('[data-block="run"]')).toBeNull();
  });

  it('lists findings and fetches the next page when there is one', async () => {
    await create();
    api.findings
      .mockResolvedValueOnce({
        items: [{ kind: 'WITHDRAWN', code: 'C-1', detailCode: 'NOT_IN_FILE' }],
        nextCursor: '1',
      })
      .mockResolvedValueOnce({
        items: [{ kind: 'REINSTATED', code: 'C-2', detailCode: 'RETURNED' }],
        nextCursor: null,
      });
    await choose();
    await validate();

    expect(el('[data-table="findings"]').textContent).toContain('C-1');
    el<HTMLButtonElement>('.moreButton').click();
    await settle();

    expect(api.findings).toHaveBeenLastCalledWith('run-1', '1');
    expect(el('[data-table="findings"]').textContent).toContain('C-2');
    expect(el('.moreButton')).toBeNull();
  });

  it('shows a withdrawn code in muted ink with its date and successor when asked to include them', async () => {
    await create();
    const withdrawn: MxikReferenceRow = {
      code: '10202001001000000',
      parentCode: null,
      labelRu: 'Бутылка',
      labelUz: 'Shisha',
      labelEn: null,
      defaultPackageCodes: ['1001'],
      validFrom: '2021-08-01',
      validUntil: '2026-09-01',
      withdrawn: true,
      replacedByCode: '10202002001000000',
    };
    api.search.mockResolvedValue([withdrawn]);
    await type('.queryInput', 'бутылка');
    el<HTMLInputElement>('.withdrawnToggle').click();
    await settle();

    el<HTMLFormElement>('.picker').dispatchEvent(new Event('submit', { cancelable: true }));
    await settle();

    expect(api.search).toHaveBeenCalledWith('бутылка', true);
    const row = el('[data-code="10202001001000000"]');
    expect(row.classList.contains('withdrawnRow')).toBe(true);
    expect(row.textContent).toContain('2026-09-01');
    expect(row.textContent).toContain('10202002001000000');
  });
});
