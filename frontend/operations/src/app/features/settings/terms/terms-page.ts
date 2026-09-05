import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';

import { ApiError, ApiErrorCode } from '../../../core/api/problem-details';
import { CurrentTenant } from '../../../core/auth/current-tenant';
import { I18n } from '../../../core/i18n/i18n';
import { TPipe } from '../../../core/i18n/t.pipe';
import { describeApiError } from '../../orders/order-errors';
import { BrandProfileApi, BrandView } from '../brand-profile/brand-profile-api';
import {
  PublishTermsRequest,
  TermsApi,
  TermsVersionSummaryView,
  TermsVersionView,
} from './terms-api';

type PageState = 'loading' | 'ready' | 'denied' | 'error';

/**
 * 10.12 Terms of service (ADR 0067) — the tenant's own console for writing
 * and publishing its storefront terms-of-service text, replacing the
 * legacy-brand text the storefront used to ship hardcoded.
 *
 * **Tenant-scoped with its own brand picker, not `CurrentLocation` — read
 * this before touching scope resolution.** `TERMS_READ`/`TERMS_MANAGE` are
 * granted only to the `tenant-owner` role bundle, and that bundle is
 * `TENANT`-scoped: it carries no `BRAND`- or `LOCATION`-scoped grant row at
 * all. `CurrentBrand` and `CurrentLocation` both derive their answer by
 * scanning the session's `BRAND`/`LOCATION`-scoped grants (see
 * `current-tenant.ts`'s own doc comment), so both would resolve to
 * null/denied for exactly the principal this screen exists for — the same
 * trap `data-privacy-page.ts` documents and the same fix: resolve the
 * tenant from `CurrentTenant`, then let the operator pick which brand's
 * terms to author from `BrandProfileApi.list`. With one brand (the common
 * pilot case) the picker auto-selects it with no visible friction; with more
 * than one, a plain `<select>` appears.
 *
 * **What "never published" means, concretely.** `TermsApi.current` returns
 * `published: false` with empty `contentsByLocale` for a brand that has
 * never published — not an error. The storefront is, right now, serving the
 * platform's own neutral default terms text for that brand, and the screen
 * says so plainly rather than showing an empty state that looks broken.
 */
@Component({
  selector: 'q-terms-page',
  imports: [TPipe],
  templateUrl: './terms-page.html',
  styleUrl: './terms-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class TermsPage {
  private readonly tenant = inject(CurrentTenant);
  private readonly brandsApi = inject(BrandProfileApi);
  private readonly api = inject(TermsApi);
  protected readonly i18n = inject(I18n);

  protected readonly state = signal<PageState>('loading');
  protected readonly loadErrorText = signal<string | null>(null);

  protected readonly brands = signal<readonly BrandView[]>([]);
  protected readonly selectedBrandId = signal<string | null>(null);

  protected readonly current = signal<TermsVersionView | null>(null);
  protected readonly history = signal<readonly TermsVersionSummaryView[]>([]);

  protected readonly editRu = signal('');
  protected readonly editUz = signal('');
  protected readonly editEn = signal('');
  protected readonly note = signal('');

  protected readonly publishSubmitting = signal(false);
  protected readonly publishError = signal<string | null>(null);
  protected readonly publishedNotice = signal<string | null>(null);

  protected readonly expandedVersion = signal<number | null>(null);
  protected readonly previewContent = signal<TermsVersionView | null>(null);
  protected readonly previewLoading = signal(false);
  protected readonly previewError = signal<string | null>(null);

  private tenantId: string | null = null;

  constructor() {
    void this.load();
  }

  protected retry(): void {
    void this.load();
  }

  protected canPublish(): boolean {
    return (
      !this.publishSubmitting() &&
      (this.editRu().trim().length > 0 ||
        this.editUz().trim().length > 0 ||
        this.editEn().trim().length > 0)
    );
  }

  protected async selectBrand(brandId: string): Promise<void> {
    if (brandId === this.selectedBrandId()) {
      return;
    }
    this.selectedBrandId.set(brandId);
    this.expandedVersion.set(null);
    this.previewContent.set(null);
    this.previewError.set(null);
    this.publishedNotice.set(null);
    this.publishError.set(null);
    this.loadErrorText.set(null);
    try {
      await this.loadBrandData(brandId);
    } catch (error) {
      this.handleLoadFailure(error);
    }
  }

  protected async submitPublish(): Promise<void> {
    const tenantId = this.tenantId;
    const brandId = this.selectedBrandId();
    if (!tenantId || !brandId || !this.canPublish()) {
      return;
    }

    const contentsByLocale: Record<string, string> = {};
    if (this.editRu().trim()) {
      contentsByLocale['ru'] = this.editRu().trim();
    }
    if (this.editUz().trim()) {
      contentsByLocale['uz-Latn'] = this.editUz().trim();
    }
    if (this.editEn().trim()) {
      contentsByLocale['en'] = this.editEn().trim();
    }
    const request: PublishTermsRequest = {
      contentsByLocale,
      note: this.note().trim() || undefined,
    };

    this.publishSubmitting.set(true);
    this.publishError.set(null);
    this.publishedNotice.set(null);
    try {
      const published = await this.api.publish(tenantId, brandId, request);
      this.current.set(published);
      this.history.set(await this.api.list(tenantId, brandId));
      this.note.set('');
      this.publishedNotice.set(
        this.i18n.t('settings.terms.publish.success', { version: published.version ?? 0 }),
      );
    } catch (error) {
      this.publishError.set(this.describe(error));
    } finally {
      this.publishSubmitting.set(false);
    }
  }

  protected async togglePreview(entry: TermsVersionSummaryView): Promise<void> {
    if (this.expandedVersion() === entry.version) {
      this.expandedVersion.set(null);
      this.previewContent.set(null);
      this.previewError.set(null);
      return;
    }
    const tenantId = this.tenantId;
    const brandId = this.selectedBrandId();
    if (!tenantId || !brandId) {
      return;
    }
    this.expandedVersion.set(entry.version);
    this.previewContent.set(null);
    this.previewError.set(null);
    this.previewLoading.set(true);
    try {
      this.previewContent.set(await this.api.version(tenantId, brandId, entry.version));
    } catch (error) {
      this.previewError.set(this.describe(error));
    } finally {
      this.previewLoading.set(false);
    }
  }

  private async load(): Promise<void> {
    this.state.set('loading');
    await this.tenant.ensureLoaded();
    const tenantId = this.tenant.tenantId();
    if (!tenantId) {
      this.state.set(this.tenant.denied() ? 'denied' : 'error');
      return;
    }
    this.tenantId = tenantId;
    try {
      const brands = await this.brandsApi.list(tenantId);
      this.brands.set(brands);
      const firstBrand = brands[0];
      if (!firstBrand) {
        this.loadErrorText.set(this.i18n.t('settings.terms.noBrands'));
        this.state.set('error');
        return;
      }
      this.selectedBrandId.set(firstBrand.id);
      await this.loadBrandData(firstBrand.id);
      this.state.set('ready');
    } catch (error) {
      this.handleLoadFailure(error);
    }
  }

  private async loadBrandData(brandId: string): Promise<void> {
    const tenantId = this.tenantId;
    if (!tenantId) {
      return;
    }
    const [current, history] = await Promise.all([
      this.api.current(tenantId, brandId),
      this.api.list(tenantId, brandId),
    ]);
    this.current.set(current);
    this.history.set(history);
    this.editRu.set(current.contentsByLocale['ru'] ?? '');
    this.editUz.set(current.contentsByLocale['uz-Latn'] ?? '');
    this.editEn.set(current.contentsByLocale['en'] ?? '');
  }

  private handleLoadFailure(error: unknown): void {
    if (error instanceof ApiError && error.status === 403) {
      this.state.set('denied');
    } else {
      this.loadErrorText.set(this.describe(error));
      this.state.set('error');
    }
  }

  /**
   * `describeApiError`'s own mapping has no entry for VALIDATION_FAILED — by
   * design, per that helper's doc, since most callers should never render
   * `problem.detail` verbatim (it is English, developer-facing prose). This
   * screen is the deliberate exception: the server's own validation message
   * for a publish with no non-blank locale ("at least one locale is
   * required") is short, accurate, and not worth re-authoring as a bespoke
   * translated copy that could drift from the server's actual rule.
   */
  private describe(error: unknown): string {
    if (error instanceof ApiError) {
      if (error.code === ApiErrorCode.VALIDATION_FAILED && error.problem?.detail) {
        return error.problem.detail;
      }
      return describeApiError(error, (key, values) => this.i18n.t(key, values));
    }
    return this.i18n.t('error.unknown.noReference');
  }
}
