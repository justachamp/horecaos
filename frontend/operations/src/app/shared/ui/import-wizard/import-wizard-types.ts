/** A job's status, verbatim from the backend it is polling. */
export type ImportWizardJobStatus =
  'QUEUED' | 'RUNNING' | 'DRY_RUN_COMPLETE' | 'COMPLETE' | 'FAILED';

/** One row of the client-side preview `FileDropzone` shows before anything is sent to the server. */
export interface ImportWizardRowPreview {
  readonly rowNumber: number;
  /** Already display-ready, in the order {@link ImportWizardAdapter.previewColumns} names them. */
  readonly cells: readonly string[];
}

/** Running totals `JobProgress` renders while a job is `QUEUED`/`RUNNING`, and `ResultSummary` renders once it settles. */
export interface ImportWizardCounts {
  readonly rowsTotal: number;
  readonly rowsProcessed: number;
  readonly created: number;
  readonly matched: number;
  readonly rejected: number;
}

/** One poll's answer. */
export interface ImportWizardJobSnapshot {
  readonly status: ImportWizardJobStatus;
  readonly counts: ImportWizardCounts;
  /** Set only when {@link status} is `FAILED`. Already a short, safe-to-show code — never raw row data. */
  readonly failureReason: string | null;
}

/** One row of the finished job's report — the dry-run diff and the real result summary, in the same shape either way. */
export interface ImportWizardRowOutcome {
  readonly rowNumber: number;
  /** Already translated — "Created", "Matched", "Rejected". */
  readonly outcome: string;
  /** A reject reason or other per-row note, already translated. `null` for a row nothing more needs to be said about. */
  readonly detail: string | null;
  readonly tone: 'success' | 'info' | 'danger';
}

/**
 * What one import backend supplies `q-import-wizard` (row `X.13`) — the
 * pilot blocker for `4.5`, `5.1b` and `3.6c`, one component with three
 * different backends behind it.
 *
 * The wizard owns the flow (dropzone → preview → dry-run diff → confirm →
 * progress → result); it never learns the shape of a phone number, a price,
 * or a geozone. Every one of these methods is already translated on the way
 * out — the wizard renders what it is handed and asks nothing about the
 * domain.
 */
export interface ImportWizardAdapter {
  /** Column headers, already translated, in the order {@link ImportWizardRowPreview.cells} lists them. */
  readonly previewColumns: readonly string[];

  /** Parses the chosen file into row-level preview cells, entirely client-side — no network call yet. */
  parsePreview(file: File): Promise<readonly ImportWizardRowPreview[]>;

  /** Starts a job (dry or real) and returns its id to poll. */
  submit(file: File, dryRun: boolean): Promise<string>;

  /** One status poll. */
  poll(jobId: string): Promise<ImportWizardJobSnapshot>;

  /** The finished job's per-row report. */
  rows(jobId: string): Promise<readonly ImportWizardRowOutcome[]>;
}
