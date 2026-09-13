import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { I18n } from '../../../core/i18n/i18n';
import { ImportWizard } from './import-wizard';
import {
  ImportWizardAdapter,
  ImportWizardJobSnapshot,
  ImportWizardRowOutcome,
  ImportWizardRowPreview,
} from './import-wizard-types';

function csvFile(name: string, content: string): File {
  return new File([content], name, { type: 'text/csv' });
}

function selectFile(host: HTMLElement, file: File): void {
  const input = host.querySelector('[data-testid="import-wizard-file-input"]') as HTMLInputElement;
  Object.defineProperty(input, 'files', { value: [file], configurable: true });
  input.dispatchEvent(new Event('change'));
}

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

const PREVIEW_ROWS: readonly ImportWizardRowPreview[] = [
  { rowNumber: 1, cells: ['+998901112233'] },
  { rowNumber: 2, cells: ['+998901112244'] },
];

const DRY_RUN_SNAPSHOT: ImportWizardJobSnapshot = {
  status: 'DRY_RUN_COMPLETE',
  counts: { rowsTotal: 2, rowsProcessed: 2, created: 1, matched: 1, rejected: 0 },
  failureReason: null,
};

const REAL_SNAPSHOT: ImportWizardJobSnapshot = {
  status: 'COMPLETE',
  counts: { rowsTotal: 2, rowsProcessed: 2, created: 1, matched: 1, rejected: 0 },
  failureReason: null,
};

const ROW_OUTCOMES: readonly ImportWizardRowOutcome[] = [
  { rowNumber: 1, outcome: 'Created', detail: null, tone: 'success' },
  { rowNumber: 2, outcome: 'Matched', detail: null, tone: 'info' },
];

/** A controllable double — every call is recorded so a test can assert on dryRun/jobId without a real backend. */
class FakeAdapter implements ImportWizardAdapter {
  readonly previewColumns = ['Phone'];
  readonly submitCalls: boolean[] = [];
  pollSnapshot: ImportWizardJobSnapshot = DRY_RUN_SNAPSHOT;
  rowsResult: readonly ImportWizardRowOutcome[] = ROW_OUTCOMES;

  async parsePreview(): Promise<readonly ImportWizardRowPreview[]> {
    return PREVIEW_ROWS;
  }

  async submit(_file: File, dryRun: boolean): Promise<string> {
    this.submitCalls.push(dryRun);
    return dryRun ? 'job-dry' : 'job-real';
  }

  async poll(_jobId: string): Promise<ImportWizardJobSnapshot> {
    return this.pollSnapshot;
  }

  async rows(_jobId: string): Promise<readonly ImportWizardRowOutcome[]> {
    return this.rowsResult;
  }
}

@Component({
  selector: 'q-import-wizard-host',
  imports: [ImportWizard],
  template: `<q-import-wizard
    title="Customers"
    [adapter]="adapter()"
    (completed)="onCompleted()"
  />`,
})
class ImportWizardHost {
  readonly adapter = signal<ImportWizardAdapter>(new FakeAdapter());
  completedCount = 0;
  onCompleted(): void {
    this.completedCount++;
  }
}

describe('ImportWizard', () => {
  let fixture: ReturnType<typeof TestBed.createComponent<ImportWizardHost>>;
  let host: HTMLElement;
  let adapter: FakeAdapter;

  beforeEach(() => {
    TestBed.configureTestingModule({});
    TestBed.inject(I18n).setLocale('en');
    adapter = new FakeAdapter();
    fixture = TestBed.createComponent(ImportWizardHost);
    fixture.componentInstance.adapter.set(adapter);
    fixture.detectChanges();
    host = fixture.nativeElement;
  });

  it('shows the row-level preview once a file is chosen, without calling the backend', async () => {
    selectFile(host, csvFile('customers.csv', 'phone\n+998901112233\n+998901112244\n'));
    await flushMicrotasks();
    fixture.detectChanges();

    expect(host.querySelector('[data-testid="import-wizard-dropzone"]')).toBeNull();
    const rows = host.querySelectorAll('.q-import-wizard__table tbody tr');
    expect(rows).toHaveLength(2);
    expect(adapter.submitCalls).toHaveLength(0);
  });

  it('runs a dry run before offering the real import, and renders the diff as ResultSummary', async () => {
    vi.useFakeTimers();
    try {
      selectFile(host, csvFile('customers.csv', 'phone\n+998901112233\n+998901112244\n'));
      await vi.advanceTimersByTimeAsync(0);
      fixture.detectChanges();

      (host.querySelector('[data-testid="import-wizard-check"]') as HTMLButtonElement).click();
      await vi.advanceTimersByTimeAsync(0);
      fixture.detectChanges();
      await vi.advanceTimersByTimeAsync(1500);
      await vi.advanceTimersByTimeAsync(0);
      fixture.detectChanges();

      expect(adapter.submitCalls).toEqual([true]);
      const result = host.querySelector('[data-testid="import-wizard-result"]');
      expect(result).not.toBeNull();
      expect(result!.textContent).toContain('Dry-run');
      expect(host.querySelectorAll('.q-import-wizard__table tbody tr')).toHaveLength(2);
      expect(host.querySelector('[data-testid="import-wizard-confirm"]')).not.toBeNull();
    } finally {
      vi.useRealTimers();
    }
  });

  it('a confirmed real import polls to COMPLETE and emits completed exactly once', async () => {
    vi.useFakeTimers();
    try {
      selectFile(host, csvFile('customers.csv', 'phone\n+998901112233\n+998901112244\n'));
      await vi.advanceTimersByTimeAsync(0);
      fixture.detectChanges();

      (host.querySelector('[data-testid="import-wizard-check"]') as HTMLButtonElement).click();
      await vi.advanceTimersByTimeAsync(0);
      await vi.advanceTimersByTimeAsync(1500);
      await vi.advanceTimersByTimeAsync(0);
      fixture.detectChanges();

      adapter.pollSnapshot = REAL_SNAPSHOT;
      (host.querySelector('[data-testid="import-wizard-confirm"]') as HTMLButtonElement).click();
      await vi.advanceTimersByTimeAsync(0);
      await vi.advanceTimersByTimeAsync(1500);
      await vi.advanceTimersByTimeAsync(0);
      fixture.detectChanges();

      expect(adapter.submitCalls).toEqual([true, false]);
      expect(fixture.componentInstance.completedCount).toBe(1);
      const result = host.querySelector('[data-testid="import-wizard-result"]');
      expect(result!.textContent).toContain('Import complete');
      // A finished real import offers no further "Import for real" — that
      // action only ever follows a dry run.
      expect(host.querySelector('[data-testid="import-wizard-confirm"]')).toBeNull();
    } finally {
      vi.useRealTimers();
    }
  });

  it('a FAILED dry run shows the failure reason and no confirm action', async () => {
    vi.useFakeTimers();
    try {
      adapter.pollSnapshot = {
        status: 'FAILED',
        counts: { rowsTotal: 0, rowsProcessed: 0, created: 0, matched: 0, rejected: 0 },
        failureReason: 'The file could not be read',
      };
      selectFile(host, csvFile('customers.csv', 'phone\n+998901112233\n'));
      await vi.advanceTimersByTimeAsync(0);
      fixture.detectChanges();

      (host.querySelector('[data-testid="import-wizard-check"]') as HTMLButtonElement).click();
      await vi.advanceTimersByTimeAsync(0);
      await vi.advanceTimersByTimeAsync(1500);
      await vi.advanceTimersByTimeAsync(0);
      fixture.detectChanges();

      const result = host.querySelector('[data-testid="import-wizard-result"]');
      expect(result!.textContent).toContain('The file could not be read');
      expect(host.querySelector('[data-testid="import-wizard-confirm"]')).toBeNull();
    } finally {
      vi.useRealTimers();
    }
  });
});
