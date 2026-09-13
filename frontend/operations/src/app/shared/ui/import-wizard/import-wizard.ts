import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  inject,
  input,
  output,
  signal,
} from '@angular/core';

import { I18n } from '../../../core/i18n/i18n';
import { TPipe } from '../../../core/i18n/t.pipe';
import { InlineAlert } from '../inline-alert';
import { StatusPill } from '../status-pill';
import {
  ImportWizardAdapter,
  ImportWizardCounts,
  ImportWizardJobSnapshot,
  ImportWizardRowOutcome,
  ImportWizardRowPreview,
} from './import-wizard-types';

/** Where the wizard is in its own flow — never persisted, never read by the adapter. */
type WizardStage = 'select' | 'preview' | 'running' | 'result';

/** Which of the two runs {@link WizardStage.running}/{@link WizardStage.result} is showing. */
type RunMode = 'dryRun' | 'real';

const POLL_INTERVAL_MS = 1200;

const ZERO_COUNTS: ImportWizardCounts = {
  rowsTotal: 0,
  rowsProcessed: 0,
  created: 0,
  matched: 0,
  rejected: 0,
};

/**
 * FileDropzone + row-level preview + dry-run diff + JobProgress + ResultSummary,
 * one component — `q-import-wizard` (row `X.13`), the pilot blocker for `4.5`
 * (catalog import), `5.1b` (customer CSV) and `3.6c` (geozone upload).
 *
 * **One flow, three backends.** This component owns the sequence — pick a
 * file, see the rows, check for problems before anything is written, watch
 * an async job progress, read what happened per row — and knows nothing
 * about phone numbers, prices, or polygons. Everything domain-shaped comes
 * from {@link ImportWizardAdapter}, so `4.5`'s catalog import and `3.6c`'s
 * geozone upload reuse this component rather than rebuilding the flow.
 *
 * **Per-row outcome reporting is a deliberate improvement over Delever's
 * silent skip** (frontend information architecture, `ImportWizard`'s own
 * row). A row that could not be imported says why, in {@link
 * ImportWizardRowOutcome.detail} — never a count that quietly drops one.
 *
 * **The dry run is not optional.** Every file goes through a dry-run job
 * before {@link confirmed} can ever fire the real one — the same "REQUIRED
 * dry-run mode" `SendPulseContactImportController` and `PosSyncRunController`
 * already enforce server-side, mirrored here so an operator sees the diff
 * before committing to it rather than discovering it was available only
 * after asking for it.
 */
@Component({
  selector: 'q-import-wizard',
  imports: [TPipe, InlineAlert, StatusPill],
  templateUrl: './import-wizard.html',
  styleUrl: './import-wizard.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ImportWizard {
  private readonly i18n = inject(I18n);
  private readonly destroyRef = inject(DestroyRef);

  /** What this wizard imports, already translated — «Клиенты», «Каталог», «Геозоны». */
  readonly title = input.required<string>();
  /** The `accept` attribute on the file input — `.csv` by default. */
  readonly accept = input<string>('.csv');
  readonly adapter = input.required<ImportWizardAdapter>();

  /** Fires once, after a *real* run settles as `COMPLETE` — never for a dry run or a `FAILED` real run. */
  readonly completed = output<void>();

  protected readonly stage = signal<WizardStage>('select');
  protected readonly mode = signal<RunMode>('dryRun');

  protected readonly selectedFile = signal<File | null>(null);
  protected readonly preview = signal<readonly ImportWizardRowPreview[]>([]);
  protected readonly previewError = signal<string | null>(null);
  protected readonly previewing = signal(false);

  protected readonly submitting = signal(false);
  protected readonly submitError = signal<string | null>(null);

  protected readonly snapshot = signal<ImportWizardJobSnapshot>({
    status: 'QUEUED',
    counts: ZERO_COUNTS,
    failureReason: null,
  });
  protected readonly rows = signal<readonly ImportWizardRowOutcome[]>([]);
  protected readonly dragOver = signal(false);

  protected readonly progressRatio = computed(() => {
    const counts = this.snapshot().counts;
    return counts.rowsTotal === 0 ? 0 : Math.min(1, counts.rowsProcessed / counts.rowsTotal);
  });

  /** Preview rows beyond this many are counted, not rendered — a 50,000-row file must not lock up the table. */
  private static readonly MAX_PREVIEW_ROWS = 50;
  protected readonly visiblePreview = computed(() =>
    this.preview().slice(0, ImportWizard.MAX_PREVIEW_ROWS),
  );
  protected readonly hiddenPreviewCount = computed(() =>
    Math.max(0, this.preview().length - ImportWizard.MAX_PREVIEW_ROWS),
  );

  private pollHandle: ReturnType<typeof setInterval> | null = null;
  private currentJobId: string | null = null;

  constructor() {
    this.destroyRef.onDestroy(() => this.stopPolling());
  }

  // -------------------------------------------------------------- dropzone

  protected onDragOver(event: DragEvent): void {
    event.preventDefault();
    this.dragOver.set(true);
  }

  protected onDragLeave(): void {
    this.dragOver.set(false);
  }

  protected onDrop(event: DragEvent): void {
    event.preventDefault();
    this.dragOver.set(false);
    const file = event.dataTransfer?.files?.[0];
    if (file) {
      void this.pickFile(file);
    }
  }

  protected onFileInputChange(event: Event): void {
    const file = (event.target as HTMLInputElement).files?.[0];
    if (file) {
      void this.pickFile(file);
    }
    // Cleared so choosing the same filename twice in a row still fires 'change'.
    (event.target as HTMLInputElement).value = '';
  }

  private async pickFile(file: File): Promise<void> {
    this.selectedFile.set(file);
    this.previewError.set(null);
    this.previewing.set(true);
    try {
      this.preview.set(await this.adapter().parsePreview(file));
      this.stage.set('preview');
    } catch (error) {
      this.previewError.set(this.describe(error));
    } finally {
      this.previewing.set(false);
    }
  }

  protected startOver(): void {
    this.stopPolling();
    this.selectedFile.set(null);
    this.preview.set([]);
    this.previewError.set(null);
    this.submitError.set(null);
    this.rows.set([]);
    this.stage.set('select');
  }

  // -------------------------------------------------------------- dry run / real run

  protected checkForProblems(): void {
    void this.runJob('dryRun');
  }

  protected confirmImport(): void {
    void this.runJob('real');
  }

  private async runJob(mode: RunMode): Promise<void> {
    const file = this.selectedFile();
    if (!file || this.submitting()) {
      return;
    }
    this.mode.set(mode);
    this.submitting.set(true);
    this.submitError.set(null);
    this.rows.set([]);
    this.snapshot.set({
      status: 'QUEUED',
      counts: { ...ZERO_COUNTS, rowsTotal: this.preview().length },
      failureReason: null,
    });
    this.stage.set('running');
    try {
      const jobId = await this.adapter().submit(file, mode === 'dryRun');
      this.currentJobId = jobId;
      this.beginPolling(jobId);
    } catch (error) {
      this.submitError.set(this.describe(error));
      this.stage.set('preview');
    } finally {
      this.submitting.set(false);
    }
  }

  private beginPolling(jobId: string): void {
    this.stopPolling();
    this.pollHandle = setInterval(() => void this.pollOnce(jobId), POLL_INTERVAL_MS);
    void this.pollOnce(jobId);
  }

  private async pollOnce(jobId: string): Promise<void> {
    if (this.currentJobId !== jobId) {
      return;
    }
    try {
      const next = await this.adapter().poll(jobId);
      if (this.currentJobId !== jobId) {
        return;
      }
      this.snapshot.set(next);
      if (
        next.status === 'COMPLETE' ||
        next.status === 'DRY_RUN_COMPLETE' ||
        next.status === 'FAILED'
      ) {
        this.stopPolling();
        this.rows.set(await this.adapter().rows(jobId));
        this.stage.set('result');
        if (next.status === 'COMPLETE') {
          this.completed.emit();
        }
      }
    } catch (error) {
      this.stopPolling();
      this.submitError.set(this.describe(error));
      this.stage.set('preview');
    }
  }

  private stopPolling(): void {
    if (this.pollHandle !== null) {
      clearInterval(this.pollHandle);
      this.pollHandle = null;
    }
  }

  private describe(error: unknown): string {
    return error instanceof Error ? error.message : this.i18n.t('ui.importWizard.error.generic');
  }
}
