import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';

import { ApiError } from '../../../core/api/problem-details';
import { CurrentLocation } from '../../../core/auth/current-location';
import { I18n } from '../../../core/i18n/i18n';
import { TPipe } from '../../../core/i18n/t.pipe';
import { describeApiError } from '../../orders/order-errors';
import {
  CustomerRefund,
  LiabilityParty,
  OutcomeReasonKind,
  ReasonRequest,
  ReasonResponse,
  ReferenceDataApi,
  StockDisposition,
} from './reference-data-api';

/**
 * 10.10 Reference data — `docs/operations-spec/settings.md` §10.10.
 *
 * **Cancellation and completion reasons are real**, `OrderOutcomeReasonController`
 * — full CRUD, versioned rather than edited in place, exactly the "renaming
 * a reason must not rewrite last year's funnel" contract the spec insists
 * on. Simplified relative to the spec: no drag-reorder `display_order` (the
 * column does not exist — a small, named gap), and a reason's customer text
 * covers three locales without `LocalizedFieldGroup`'s completeness chips.
 *
 * **Business calendar, SLA buckets and branch tags are not built**, each
 * rendered as its own honest card so a person scanning this page does not
 * have to read the whole not-built page to learn that two of five cards on
 * it are real.
 */
@Component({
  selector: 'q-reference-data-page',
  imports: [TPipe],
  templateUrl: './reference-data-page.html',
  styleUrl: './reference-data-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ReferenceDataPage {
  private readonly api = inject(ReferenceDataApi);
  private readonly location = inject(CurrentLocation);
  protected readonly i18n = inject(I18n);

  protected readonly loading = signal(true);
  protected readonly denied = signal(false);
  protected readonly loadError = signal<string | null>(null);
  protected readonly cancellationReasons = signal<readonly ReasonResponse[]>([]);
  protected readonly completionReasons = signal<readonly ReasonResponse[]>([]);
  protected readonly cancellationCategories = signal<readonly string[]>([]);
  protected readonly completionCategories = signal<readonly string[]>([]);

  protected readonly showCreateForm = signal<OutcomeReasonKind | null>(null);
  protected readonly createSubmitting = signal(false);
  protected readonly createError = signal<string | null>(null);
  protected readonly newInternalName = signal('');
  protected readonly newSystemCategory = signal('');
  protected readonly newTextRu = signal('');
  protected readonly newTextUz = signal('');
  protected readonly newTextEn = signal('');
  protected readonly newStockDisposition = signal<StockDisposition>('RELEASE');
  protected readonly newLiabilityParty = signal<LiabilityParty>('TENANT');
  protected readonly newCustomerRefund = signal<CustomerRefund>('NONE');

  constructor() {
    void this.load();
  }

  protected openCreateForm(kind: OutcomeReasonKind): void {
    this.newInternalName.set('');
    this.newSystemCategory.set(
      (kind === 'CANCELLATION' ? this.cancellationCategories() : this.completionCategories())[0] ??
        '',
    );
    this.newTextRu.set('');
    this.newTextUz.set('');
    this.newTextEn.set('');
    this.createError.set(null);
    this.showCreateForm.set(kind);
  }

  protected canCreate(): boolean {
    return (
      !this.createSubmitting() &&
      this.newInternalName().trim().length > 0 &&
      this.newSystemCategory().trim().length > 0 &&
      this.newTextRu().trim().length > 0 &&
      this.newTextUz().trim().length > 0 &&
      this.newTextEn().trim().length > 0
    );
  }

  protected async submitCreate(): Promise<void> {
    const scope = this.location.scope();
    const kind = this.showCreateForm();
    if (!scope || !kind || !this.canCreate()) {
      return;
    }
    this.createSubmitting.set(true);
    this.createError.set(null);
    const request: ReasonRequest = {
      kind,
      systemCategory: this.newSystemCategory(),
      internalName: this.newInternalName().trim(),
      customerTexts: {
        ru: this.newTextRu().trim(),
        'uz-Latn': this.newTextUz().trim(),
        en: this.newTextEn().trim(),
      },
      ...(kind === 'CANCELLATION'
        ? {
            stockDisposition: this.newStockDisposition(),
            liabilityParty: this.newLiabilityParty(),
            customerRefund: this.newCustomerRefund(),
          }
        : {}),
    };
    try {
      await this.api.create(scope, request);
      this.showCreateForm.set(null);
      await this.reload(scope);
    } catch (error) {
      this.createError.set(this.describe(error));
    } finally {
      this.createSubmitting.set(false);
    }
  }

  protected async archive(reason: ReasonResponse): Promise<void> {
    const scope = this.location.scope();
    if (!scope) {
      return;
    }
    if (
      !confirm(this.i18n.t('settings.referenceData.archive.confirm', { name: reason.internalName }))
    ) {
      return;
    }
    try {
      await this.api.archive(scope, reason.id, reason.version);
      await this.reload(scope);
    } catch (error) {
      this.loadError.set(this.describe(error));
    }
  }

  private async load(): Promise<void> {
    this.loading.set(true);
    await this.location.ensureLoaded();
    const scope = this.location.scope();
    if (!scope) {
      this.denied.set(this.location.denied());
      this.loading.set(false);
      return;
    }
    try {
      const [cancellation, completion, cancellationCategories, completionCategories] =
        await Promise.all([
          this.api.list(scope, 'CANCELLATION'),
          this.api.list(scope, 'COMPLETION'),
          this.api.categories(scope, 'CANCELLATION'),
          this.api.categories(scope, 'COMPLETION'),
        ]);
      this.cancellationReasons.set(cancellation);
      this.completionReasons.set(completion);
      this.cancellationCategories.set(cancellationCategories);
      this.completionCategories.set(completionCategories);
    } catch (error) {
      if (error instanceof ApiError && error.status === 403) {
        this.denied.set(true);
      } else {
        this.loadError.set(this.describe(error));
      }
    } finally {
      this.loading.set(false);
    }
  }

  private async reload(scope: NonNullable<ReturnType<CurrentLocation['scope']>>): Promise<void> {
    this.cancellationReasons.set(await this.api.list(scope, 'CANCELLATION'));
    this.completionReasons.set(await this.api.list(scope, 'COMPLETION'));
  }

  private describe(error: unknown): string {
    if (error instanceof ApiError) {
      return describeApiError(error, (key, values) => this.i18n.t(key, values));
    }
    return this.i18n.t('error.unknown.noReference');
  }
}
