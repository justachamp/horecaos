import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';

import { ApiError } from '../../core/api/problem';
import { asDate } from '../../core/api/dates';
import { I18nService } from '../../core/i18n/i18n.service';
import type { MessageKey } from '../../core/i18n/messages.en';
import {
  FiscalReferenceApi,
  ImportFinding,
  ImportMode,
  ImportRun,
  MxikReferenceRow,
  ReferenceStatus,
} from './fiscal-reference-api';

/**
 * IA 6.2 Fiscal reference -- the national ИКПУ/MXIK list: its state, how it gets loaded, and a browse.
 *
 * Loading is ADR 0173's two-step: an upload is always a dry run that changes nothing, and apply is a separate,
 * explicit act. A run that would withdraw more than the configured share of the list is held until the
 * administrator types the exact withdrawal count they were shown. Assignment of a code to a menu item is a
 * different screen's job (`CatalogAuthoringController`'s `fiscal-classification` endpoints).
 */
@Component({
  selector: 'app-fiscal-reference',
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './fiscal-reference.html',
  styleUrl: './fiscal-reference.css',
})
export class FiscalReference {
  protected readonly i18n = inject(I18nService);
  private readonly api = inject(FiscalReferenceApi);

  protected readonly status = signal<ReferenceStatus | null>(null);

  // browse
  protected readonly query = signal('');
  protected readonly includeWithdrawn = signal(false);
  protected readonly loading = signal(false);
  protected readonly searched = signal(false);
  protected readonly loadError = signal<string | null>(null);
  protected readonly results = signal<readonly MxikReferenceRow[]>([]);

  // import
  protected readonly file = signal<File | null>(null);
  protected readonly mode = signal<ImportMode>('FULL_SNAPSHOT');
  protected readonly publishedOn = signal('');
  protected readonly working = signal(false);
  protected readonly importError = signal<string | null>(null);
  protected readonly run = signal<ImportRun | null>(null);
  protected readonly outcome = signal<MessageKey | null>(null);
  protected readonly findings = signal<readonly ImportFinding[]>([]);
  protected readonly findingsCursor = signal<string | null>(null);
  protected readonly acknowledgement = signal('');
  protected readonly recent = signal<readonly ImportRun[]>([]);

  protected readonly held = computed(() => this.run()?.status === 'HELD_FOR_REVIEW');
  protected readonly decidable = computed(() => {
    const status = this.run()?.status;
    return status === 'VALIDATED' || status === 'HELD_FOR_REVIEW';
  });
  protected readonly canApply = computed(() => {
    const run = this.run();
    if (run === null || this.working()) {
      return false;
    }
    if (run.status === 'VALIDATED') {
      return true;
    }
    return (
      run.status === 'HELD_FOR_REVIEW' &&
      this.acknowledgement().trim() === String(run.rowsWithdrawn)
    );
  });
  protected readonly lengths = computed(() =>
    Object.entries(this.run()?.codeLengthHistogram ?? {})
      .map(([length, count]) => `${length}: ${count}`)
      .join(', '),
  );

  constructor() {
    void this.refresh();
  }

  protected asDate = asDate;

  protected statusKey(status: string): MessageKey {
    return `fiscalReference.run.status.${status}` as MessageKey;
  }

  protected kindKey(kind: string): MessageKey {
    return `fiscalReference.findings.kind.${kind}` as MessageKey;
  }

  private async refresh(): Promise<void> {
    try {
      this.status.set(await this.api.status());
    } catch {
      this.status.set(null);
    }
    try {
      this.recent.set(await this.api.recentRuns());
    } catch {
      this.recent.set([]);
    }
  }

  protected async search(event: Event): Promise<void> {
    event.preventDefault();
    const query = this.query().trim();
    if (query.length < 2) {
      return;
    }
    this.loading.set(true);
    this.loadError.set(null);
    this.searched.set(true);
    try {
      this.results.set(await this.api.search(query, this.includeWithdrawn()));
    } catch (error) {
      this.loadError.set(this.i18n.describe(error as ApiError));
    } finally {
      this.loading.set(false);
    }
  }

  protected chooseFile(event: Event): void {
    const input = event.target as HTMLInputElement;
    this.file.set(input.files !== null && input.files.length > 0 ? input.files[0] : null);
  }

  /** The dry run: reads the file, records a run, changes nothing in the list. */
  protected async dryRun(event: Event): Promise<void> {
    event.preventDefault();
    const file = this.file();
    if (file === null) {
      return;
    }
    this.working.set(true);
    this.importError.set(null);
    this.outcome.set(null);
    this.acknowledgement.set('');
    try {
      const run = await this.api.dryRun(file, this.mode(), this.publishedOn() || null);
      await this.show(run);
    } catch (error) {
      this.run.set(null);
      this.importError.set(this.describe(error));
    } finally {
      this.working.set(false);
    }
  }

  protected async open(runId: string): Promise<void> {
    this.importError.set(null);
    this.outcome.set(null);
    this.acknowledgement.set('');
    try {
      await this.show(await this.api.run(runId));
    } catch (error) {
      this.importError.set(this.describe(error));
    }
  }

  protected async moreFindings(): Promise<void> {
    const run = this.run();
    if (run === null) {
      return;
    }
    const page = await this.api.findings(run.runId, this.findingsCursor());
    this.findings.update((existing) => [...existing, ...page.items]);
    this.findingsCursor.set(page.nextCursor);
  }

  protected async apply(): Promise<void> {
    const run = this.run();
    if (run === null || !this.canApply()) {
      return;
    }
    this.working.set(true);
    this.importError.set(null);
    try {
      const applied = await this.api.apply(
        run,
        this.held() ? Number(this.acknowledgement()) : null,
      );
      this.run.set(applied);
      this.outcome.set('fiscalReference.run.applied');
      await this.refresh();
    } catch (error) {
      this.importError.set(this.describe(error));
    } finally {
      this.working.set(false);
    }
  }

  protected async reject(): Promise<void> {
    const run = this.run();
    if (run === null) {
      return;
    }
    this.working.set(true);
    this.importError.set(null);
    try {
      this.run.set(await this.api.reject(run));
      this.outcome.set('fiscalReference.run.rejected');
      await this.refresh();
    } catch (error) {
      this.importError.set(this.describe(error));
    } finally {
      this.working.set(false);
    }
  }

  private async show(run: ImportRun): Promise<void> {
    this.run.set(run);
    this.findings.set([]);
    this.findingsCursor.set(null);
    const page = await this.api.findings(run.runId, null);
    this.findings.set(page.items);
    this.findingsCursor.set(page.nextCursor);
    await this.refresh();
  }

  /**
   * The refusal the importer named (`findingCode`, a stable code) in the reader's language; the platform's
   * generic sentence for anything else. A server `detail` is written for a developer and never shown.
   */
  private describe(error: unknown): string {
    if (error instanceof ApiError) {
      const finding = error.problem['findingCode'];
      const key = typeof finding === 'string' ? REFUSALS[finding] : undefined;
      return key !== undefined ? this.i18n.t(key) : this.i18n.describe(error);
    }
    return this.i18n.t('fiscalReference.refusal.UPLOAD_FAILED');
  }
}

const REFUSALS: Readonly<Record<string, MessageKey>> = {
  UNKNOWN_HEADER: 'fiscalReference.refusal.UNKNOWN_HEADER',
  REQUIRED_COLUMN_MISSING: 'fiscalReference.refusal.REQUIRED_COLUMN_MISSING',
  FILE_TOO_LARGE: 'fiscalReference.refusal.FILE_TOO_LARGE',
  RUN_IS_STALE: 'fiscalReference.refusal.RUN_IS_STALE',
  RUN_NOT_APPLICABLE: 'fiscalReference.refusal.RUN_NOT_APPLICABLE',
  WITHDRAWAL_ACKNOWLEDGEMENT_REQUIRED:
    'fiscalReference.refusal.WITHDRAWAL_ACKNOWLEDGEMENT_REQUIRED',
  DUPLICATE_HEADER: 'fiscalReference.refusal.FILE_UNREADABLE',
  FILE_EMPTY: 'fiscalReference.refusal.FILE_UNREADABLE',
  TOO_MANY_ROWS: 'fiscalReference.refusal.FILE_UNREADABLE',
  CSV_UNREADABLE: 'fiscalReference.refusal.FILE_UNREADABLE',
  XLSX_UNREADABLE: 'fiscalReference.refusal.FILE_UNREADABLE',
  RAW_FILE_NOT_STORED: 'fiscalReference.refusal.FILE_UNREADABLE',
};
