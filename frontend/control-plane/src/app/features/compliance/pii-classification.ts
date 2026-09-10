import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';

import { asDate } from '../../core/api/dates';
import { ApiError } from '../../core/api/problem';
import { I18nService } from '../../core/i18n/i18n.service';
import { MessageKey } from '../../core/i18n/messages.en';
import { TenantDirectory } from '../../shared/tenant-directory';
import { DataProtection, DataProtectionApi, EncryptedColumn } from './data-protection-api';

/**
 * IA 6.4 PII & data classification -- how the platform treats personal data,
 * read from what enforces it.
 *
 * The data classes and what each requires; every column stored encrypted,
 * read from the database itself; how long each kind of personal data is kept
 * and which job deletes it; the erasure requests customers have made; and how
 * often personal data was revealed or exported in the last month. Nothing
 * here names a customer.
 */
@Component({
  selector: 'app-pii-classification',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink],
  templateUrl: './pii-classification.html',
  styleUrls: ['./residency-hosting.css', './pii-classification.css'],
})
export class PiiClassification {
  protected readonly i18n = inject(I18nService);
  protected readonly asDate = asDate;
  protected readonly directory = inject(TenantDirectory);
  private readonly api = inject(DataProtectionApi);

  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly data = signal<DataProtection | null>(null);

  /** Encrypted columns grouped by the table that holds them. */
  protected readonly tables = computed(() => {
    const groups = new Map<string, EncryptedColumn[]>();
    for (const column of this.data()?.encryptedColumns ?? []) {
      const table = `${column.schema}.${column.table}`;
      groups.set(table, [...(groups.get(table) ?? []), column]);
    }
    return [...groups.entries()];
  });

  constructor() {
    void this.directory.load();
    void this.load();
  }

  private async load(): Promise<void> {
    try {
      this.data.set(await this.api.overview());
    } catch (error) {
      this.loadError.set(this.i18n.describe(error as ApiError));
    } finally {
      this.loading.set(false);
    }
  }

  protected classKey(code: string): MessageKey {
    return `piiClassification.class.${code}` as MessageKey;
  }

  protected ruleKey(code: string): MessageKey {
    return `piiClassification.rule.${code}` as MessageKey;
  }

  protected viaKey(via: string): MessageKey {
    return `piiClassification.via.${via}` as MessageKey;
  }

  /** The job's own class name, without its package. */
  protected shortName(className: string): string {
    return className.slice(className.lastIndexOf('.') + 1);
  }
}
