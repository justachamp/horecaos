import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';

import { ApiError } from '../../core/api/problem';
import { I18nService } from '../../core/i18n/i18n.service';
import { MessageKey } from '../../core/i18n/messages.en';
import { FailureCategoryView, IntegrationOpsApi } from './integration-ops-api';

/**
 * IA 4.4 Error taxonomy -- the categories every failure is filed under, what
 * each means, what to do about it, and how many messages are in each now.
 *
 * The categories and whether the platform retries each by itself are the
 * ones the relay and every consumer already apply; this screen adds the
 * explanation an operator needs and the live counts.
 */
@Component({
  selector: 'app-error-taxonomy',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink],
  templateUrl: './error-taxonomy.html',
  styleUrl: './error-taxonomy.css',
})
export class ErrorTaxonomy {
  protected readonly i18n = inject(I18nService);
  private readonly api = inject(IntegrationOpsApi);

  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly categories = signal<readonly FailureCategoryView[]>([]);

  constructor() {
    void this.load();
  }

  private async load(): Promise<void> {
    try {
      this.categories.set(await this.api.failureTaxonomy());
    } catch (error) {
      this.loadError.set(this.i18n.describe(error as ApiError));
    } finally {
      this.loading.set(false);
    }
  }

  protected key(code: string, part: 'name' | 'meaning' | 'action'): MessageKey {
    return `errorTaxonomy.${code}.${part}` as MessageKey;
  }

  protected dead(category: FailureCategoryView): number {
    return category.outboxDeadLettered + category.inboxDeadLettered;
  }

  protected waiting(category: FailureCategoryView): number {
    return category.outboxWaiting + category.inboxWaiting;
  }
}
