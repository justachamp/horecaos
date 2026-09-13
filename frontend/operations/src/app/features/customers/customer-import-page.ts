import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { Router } from '@angular/router';

import { CurrentLocation } from '../../core/auth/current-location';
import { I18n } from '../../core/i18n/i18n';
import { MessageKey } from '../../core/i18n/messages.en';
import { TPipe } from '../../core/i18n/t.pipe';
import {
  ImportWizardAdapter,
  ImportWizardJobSnapshot,
  ImportWizardRowOutcome,
  ImportWizardRowPreview,
} from '../../shared/ui/import-wizard/import-wizard-types';
import { ImportWizard } from '../../shared/ui/import-wizard/import-wizard';
import { CustomerImportRow, CustomersApi } from './customers-api';

/**
 * Column-name aliases a "phone" cell might be filed under, mirroring {@code
 * CustomerCsvImportParser.PHONE_KEYS} on the platform side.
 *
 * Deliberately duplicated rather than shared: this is a client-side, no-
 * network-call preview of what the file looks like before anything is sent
 * — the dry-run job (this same wizard's next step) is the authoritative
 * answer, computed by the parser this list mirrors. A mismatch between the
 * two only ever under- or over-highlights a column in the preview table; it
 * never changes what actually gets imported.
 */
const PHONE_COLUMN_ALIASES = [
  'phone',
  'phone_number',
  'phonenumber',
  'msisdn',
  'tel',
  'telephone',
  'mobile',
  'contact_phone',
];

function normalizeHeader(raw: string): string {
  return raw
    .trim()
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '_')
    .replace(/^_+|_+$/g, '');
}

/** A single CSV line, tolerant of quoted fields containing a comma. */
function splitCsvLine(line: string): string[] {
  const cells: string[] = [];
  let current = '';
  let inQuotes = false;
  for (let i = 0; i < line.length; i++) {
    const char = line[i];
    if (inQuotes) {
      if (char === '"') {
        if (line[i + 1] === '"') {
          current += '"';
          i++;
        } else {
          inQuotes = false;
        }
      } else {
        current += char;
      }
    } else if (char === '"') {
      inQuotes = true;
    } else if (char === ',') {
      cells.push(current);
      current = '';
    } else {
      current += char;
    }
  }
  cells.push(current);
  return cells.map((cell) => cell.trim());
}

function readAsText(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result ?? ''));
    reader.onerror = () => reject(reader.error ?? new Error('Could not read the file'));
    reader.readAsText(file);
  });
}

function outcomeTone(outcome: CustomerImportRow['outcome']): ImportWizardRowOutcome['tone'] {
  switch (outcome) {
    case 'CREATED_CUSTOMER':
      return 'success';
    case 'MATCHED_CUSTOMER':
      return 'info';
    case 'REJECTED':
      return 'danger';
  }
}

const REJECT_REASON_KEYS: Record<string, MessageKey> = {
  MISSING_PHONE: 'customers.import.reject.MISSING_PHONE',
  MALFORMED_PHONE: 'customers.import.reject.MALFORMED_PHONE',
  AMBIGUOUS_PHONE_MATCH: 'customers.import.reject.AMBIGUOUS_PHONE_MATCH',
};

/**
 * Row `5.1b`: the customer CSV import, `q-import-wizard`'s first consumer.
 *
 * All the domain-specific work lives here, in the adapter this page builds
 * for the wizard — reading the file, a lightweight client-side preview
 * parse, and translating the server's outcome/reject codes into copy. The
 * wizard itself (`ImportWizard`) never learns any of that; see its own doc.
 */
@Component({
  selector: 'q-customer-import-page',
  imports: [TPipe, ImportWizard],
  templateUrl: './customer-import-page.html',
  styleUrl: './customer-import-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CustomerImportPage {
  private readonly api = inject(CustomersApi);
  private readonly location = inject(CurrentLocation);
  private readonly router = inject(Router);
  protected readonly i18n = inject(I18n);

  protected readonly denied = signal(false);

  protected readonly adapter: ImportWizardAdapter = {
    previewColumns: [this.i18n.t('customers.create.phone')],

    parsePreview: async (file: File): Promise<readonly ImportWizardRowPreview[]> => {
      const text = await readAsText(file);
      const lines = text.split(/\r?\n/).filter((line) => line.length > 0);
      if (lines.length === 0) {
        return [];
      }
      const header = splitCsvLine(lines[0]).map(normalizeHeader);
      const phoneIndex = header.findIndex((column) => PHONE_COLUMN_ALIASES.includes(column));
      return lines.slice(1).map((line, index) => {
        const cells = splitCsvLine(line);
        const phone = phoneIndex >= 0 ? (cells[phoneIndex] ?? '') : '';
        return { rowNumber: index + 1, cells: [phone] };
      });
    },

    submit: async (file: File, dryRun: boolean): Promise<string> => {
      const scope = this.location.scope();
      if (!scope) {
        throw new Error(this.i18n.t('customers.import.noLocation'));
      }
      const content = await readAsText(file);
      return this.api.submitImport(
        scope,
        { brandId: scope.brandId, fileName: file.name, content },
        dryRun,
      );
    },

    poll: async (jobId: string): Promise<ImportWizardJobSnapshot> => {
      const scope = this.location.scope();
      if (!scope) {
        throw new Error(this.i18n.t('customers.import.noLocation'));
      }
      const status = await this.api.importStatus(scope, jobId);
      return {
        status: status.status,
        counts: {
          rowsTotal: status.rowsTotal,
          rowsProcessed: status.rowsProcessed,
          created: status.rowsCreatedCustomer,
          matched: status.rowsMatchedCustomer,
          rejected: status.rowsRejected,
        },
        failureReason: status.failureReason,
      };
    },

    rows: async (jobId: string): Promise<readonly ImportWizardRowOutcome[]> => {
      const scope = this.location.scope();
      if (!scope) {
        throw new Error(this.i18n.t('customers.import.noLocation'));
      }
      const rows = await this.api.importRows(scope, jobId);
      return rows.map((row) => ({
        rowNumber: row.rowNumber,
        outcome: this.i18n.t(`customers.import.outcome.${row.outcome}` as MessageKey),
        detail: row.rejectReason
          ? this.i18n.t(REJECT_REASON_KEYS[row.rejectReason] ?? 'customers.import.reject.OTHER')
          : null,
        tone: outcomeTone(row.outcome),
      }));
    },
  };

  constructor() {
    this.denied.set(!this.location.scope());
  }

  protected close(): void {
    void this.router.navigate(['/customers']);
  }

  protected onCompleted(): void {
    // The list under this docked pane re-loads its own counts and rows once
    // the pane deactivates — see customers-page.ts#onOutletDeactivate, the
    // same convention the :accountId detail pane already relies on.
  }
}
