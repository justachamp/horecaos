import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  signal,
  untracked,
} from '@angular/core';

import { LocationScope } from '../../../core/api/operations-paths';
import { formatDateTime } from '../../../core/format/datetime';
import { I18n } from '../../../core/i18n/i18n';
import { MessageKey } from '../../../core/i18n/messages.en';
import { TPipe } from '../../../core/i18n/t.pipe';
import { BrowserPrinter } from './browser-print';
import {
  JOB_STATUSES,
  JobStatus,
  PRINT_REASONS,
  PrintJobView,
  PrintReason,
  PrinterView,
  PrintingApi,
} from './printing-api';
import { describePrintError } from './printing-errors';
import {
  DOCUMENT_TYPE_KEYS,
  FAILURE_KEYS,
  JOB_STATUS_KEYS,
  REASON_KEYS,
  REPRINTABLE,
} from './printing-labels';

/**
 * Settings · Printing and receipts · Print queue (ADR 0154 decision 12).
 *
 * Every job at the branch, newest first, so a manager can see what is waiting, what printed, and what did not.
 * A job that died, expired or was cancelled is the reason this list exists: it says what went wrong in words
 * and offers to print the document again. A reprint is a new job, regenerated from the facts as they are now and
 * marked as a copy on paper; it asks why, and the activity log keeps who asked and why. A queued job can be
 * cancelled; a claimed one is already on its way to paper and cannot.
 *
 * The stored document is never shown as text here: the platform returns identifiers and outcomes, and a picture
 * only on request, because a line's note can hold whatever a customer typed.
 */
@Component({
  selector: 'q-print-queue-card',
  imports: [TPipe],
  templateUrl: './print-queue-card.html',
  styleUrl: './printing-shared.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PrintQueueCard {
  private readonly api = inject(PrintingApi);
  private readonly browser = inject(BrowserPrinter);
  protected readonly i18n = inject(I18n);

  readonly scope = input.required<LocationScope>();
  readonly printers = input.required<readonly PrinterView[]>();

  protected readonly statuses = JOB_STATUSES;
  protected readonly statusKeys = JOB_STATUS_KEYS;
  protected readonly documentKeys = DOCUMENT_TYPE_KEYS;
  protected readonly reasons = PRINT_REASONS;
  protected readonly reasonKeys = REASON_KEYS;

  protected readonly filter = signal<JobStatus | null>(null);
  protected readonly jobs = signal<readonly PrintJobView[]>([]);
  protected readonly nextCursor = signal<string | null>(null);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly notice = signal<string | null>(null);

  protected readonly reprinting = signal<string | null>(null);
  protected readonly reprintReason = signal<PrintReason>('PRINTER_FAULT');
  protected readonly reprintPrinter = signal('');
  protected readonly previewing = signal<{ jobId: string; url: string } | null>(null);

  protected readonly activePrinters = computed(() =>
    this.printers().filter((printer) => printer.status === 'ACTIVE'),
  );

  /** A ticket for the latest read: a reply under an older filter or branch belongs to a list already left. */
  private seq = 0;

  constructor() {
    effect(() => {
      const scope = this.scope();
      const filter = this.filter();
      untracked(() => void this.load(scope, filter, null));
    });
  }

  protected setFilter(value: string): void {
    this.filter.set(value === '' ? null : (value as JobStatus));
    this.closePreview();
  }

  protected refresh(): void {
    void this.load(this.scope(), this.filter(), null);
  }

  protected more(): void {
    const cursor = this.nextCursor();
    if (cursor !== null) {
      void this.load(this.scope(), this.filter(), cursor);
    }
  }

  protected when(iso: string): string {
    return formatDateTime(new Date(iso), 'Asia/Tashkent');
  }

  protected printerName(id: string): string {
    return this.printers().find((printer) => printer.id === id)?.displayName ?? '—';
  }

  protected failureKey(job: PrintJobView): MessageKey | null {
    if (job.failureCode === null) {
      return null;
    }
    return FAILURE_KEYS[job.failureCode] ?? 'settings.printing.job.failure.unknown';
  }

  protected canCancel(job: PrintJobView): boolean {
    return job.status === 'QUEUED';
  }

  protected canReprint(job: PrintJobView): boolean {
    return REPRINTABLE.has(job.status) && job.documentType !== 'TEST_PAGE';
  }

  protected canPreview(job: PrintJobView): boolean {
    return !job.documentErased;
  }

  protected async cancel(job: PrintJobView): Promise<void> {
    this.busy.set(true);
    this.error.set(null);
    this.notice.set(null);
    try {
      await this.api.cancelJob(this.scope(), job.id);
      this.notice.set(this.i18n.t('settings.printing.queue.cancelled'));
      await this.load(this.scope(), this.filter(), null);
    } catch (error) {
      this.error.set(this.describe(error));
      await this.load(this.scope(), this.filter(), null);
    } finally {
      this.busy.set(false);
    }
  }

  protected openReprint(job: PrintJobView): void {
    if (this.reprinting() === job.id) {
      this.reprinting.set(null);
      return;
    }
    this.reprinting.set(job.id);
    this.reprintReason.set('PRINTER_FAULT');
    this.reprintPrinter.set(job.printerId);
    this.error.set(null);
    this.notice.set(null);
  }

  protected async reprint(job: PrintJobView): Promise<void> {
    if (this.busy()) {
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    try {
      const made = await this.api.reprintJob(
        this.scope(),
        job.id,
        this.reprintReason(),
        this.reprintPrinter() === '' ? null : this.reprintPrinter(),
      );
      this.reprinting.set(null);
      if (made.status === 'HANDED_OFF') {
        const picture = await this.api.preview(this.scope(), { jobId: made.id });
        await this.browser.print(picture, made.tapeWidthMm);
      }
      this.notice.set(this.i18n.t('settings.printing.queue.reprinted'));
      await this.load(this.scope(), this.filter(), null);
    } catch (error) {
      this.error.set(this.describe(error));
    } finally {
      this.busy.set(false);
    }
  }

  protected async togglePreview(job: PrintJobView): Promise<void> {
    if (this.previewing()?.jobId === job.id) {
      this.closePreview();
      return;
    }
    this.closePreview();
    this.error.set(null);
    try {
      const picture = await this.api.preview(this.scope(), { jobId: job.id });
      this.previewing.set({ jobId: job.id, url: URL.createObjectURL(picture) });
    } catch (error) {
      this.error.set(this.describe(error));
    }
  }

  protected closePreview(): void {
    const open = this.previewing();
    if (open !== null) {
      URL.revokeObjectURL(open.url);
      this.previewing.set(null);
    }
  }

  private async load(
    scope: LocationScope,
    filter: JobStatus | null,
    cursor: string | null,
  ): Promise<void> {
    const ticket = ++this.seq;
    this.loading.set(true);
    this.loadError.set(null);
    try {
      const page = await this.api.jobs(scope, filter, cursor);
      if (ticket !== this.seq) {
        return;
      }
      this.jobs.set(cursor === null ? page.items : [...this.jobs(), ...page.items]);
      this.nextCursor.set(page.nextCursor);
    } catch (error) {
      if (ticket === this.seq) {
        this.loadError.set(this.describe(error));
      }
    } finally {
      if (ticket === this.seq) {
        this.loading.set(false);
      }
    }
  }

  private describe(error: unknown): string {
    return describePrintError(error, this.i18n, {
      off: 'settings.printing.error.off',
      noRoute: 'settings.printing.error.noRoute',
    });
  }
}
