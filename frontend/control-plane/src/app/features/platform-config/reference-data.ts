import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { ApiError } from '../../core/api/problem';
import { I18nService } from '../../core/i18n/i18n.service';

interface Country {
  readonly code: string;
  readonly name: string;
  readonly defaultCurrency: string;
  readonly defaultTimezone: string;
}

interface Locale {
  readonly code: string;
  readonly displayName: string;
}

interface ReferenceData {
  readonly countries: readonly Country[];
  readonly locales: readonly Locale[];
}

/**
 * IA 8.3 Reference data -- countries, currencies, locales, timezones (ADR
 * 0034), read from the new `ReferenceDataController`.
 *
 * Named gap, not a silent one: national holiday seeds and default SLA
 * buckets (also in the IA row) are not modeled anywhere in this codebase --
 * no table, no endpoint -- and this screen does not invent them.
 */
@Component({
  selector: 'app-reference-data-screen',
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './reference-data.html',
  styleUrl: './reference-data.css',
})
export class ReferenceDataScreen {
  protected readonly i18n = inject(I18nService);
  private readonly api = inject(ApiClient);

  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly data = signal<ReferenceData | null>(null);

  constructor() {
    void this.load();
  }

  private async load(): Promise<void> {
    this.loading.set(true);
    this.loadError.set(null);
    try {
      this.data.set(
        await firstValueFrom(this.api.get<ReferenceData>('/api/v1/control-plane/reference-data')),
      );
    } catch (error) {
      this.loadError.set(this.i18n.describe(error as ApiError));
    } finally {
      this.loading.set(false);
    }
  }
}
