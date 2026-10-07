import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';

import { ApiError } from '../../../core/api/problem-details';
import { CurrentTenant } from '../../../core/auth/current-tenant';
import { I18n } from '../../../core/i18n/i18n';
import { MessageKey } from '../../../core/i18n/messages.en';
import { TPipe } from '../../../core/i18n/t.pipe';
import { describeApiError } from '../../orders/order-errors';
import { BrandProfileApi, BrandView } from '../brand-profile/brand-profile-api';
import { CatalogueEntry, StorefrontAppsApi } from './storefront-apps-api';

type PageState = 'loading' | 'ready' | 'denied' | 'error';

/** What the operator is in the middle of doing to one app. */
interface Pending {
  readonly appId: string;
  readonly kind: 'authorise' | 'revoke';
}

/**
 * 10.14 Storefront apps (ADR 0070) — the tenant's own choice of storefront.
 *
 * **Tenant-scoped with its own brand picker, not `CurrentLocation`.** The owner and the administrator
 * hold `STOREFRONT_APP_AUTHORISE` through a `TENANT`-scoped bundle, which carries no `BRAND` or `LOCATION`
 * grant row; `CurrentBrand` would resolve to nothing for exactly the principal this screen is for. It is
 * the same trap `terms-page.ts` documents and the same fix: the tenant from `CurrentTenant`, the brand
 * from a picker that is invisible while there is only one.
 *
 * **What authorising means, in the screen's own words.** The page says, before anything else, that
 * authorising lets a vendor's storefront take orders as this brand, and that revoking stops it on its next
 * request — the two facts a tenant needs to weigh the choice. A browser-only app is described as one the
 * platform can attribute and revoke but not authenticate; nothing here calls it secure.
 *
 * **Every change asks for a reason**, because both are recorded in the audit log under the operator's name.
 * Revoking sends the authorisation version the list was read at, and a stale one reloads rather than
 * overwriting somebody else's change.
 */
@Component({
  selector: 'q-storefront-apps-page',
  imports: [TPipe],
  templateUrl: './storefront-apps-page.html',
  styleUrl: './storefront-apps-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class StorefrontAppsPage {
  private readonly tenant = inject(CurrentTenant);
  private readonly brandsApi = inject(BrandProfileApi);
  private readonly api = inject(StorefrontAppsApi);
  protected readonly i18n = inject(I18n);

  protected readonly state = signal<PageState>('loading');
  protected readonly loadErrorText = signal<string | null>(null);

  protected readonly brands = signal<readonly BrandView[]>([]);
  protected readonly selectedBrandId = signal<string | null>(null);
  protected readonly listLoading = signal(false);

  protected readonly entries = signal<readonly CatalogueEntry[]>([]);

  protected readonly pending = signal<Pending | null>(null);
  protected readonly reason = signal('');
  protected readonly submitting = signal(false);
  protected readonly actionError = signal<string | null>(null);
  protected readonly notice = signal<string | null>(null);

  /** A ticket for the latest brand load: a reply under an older one belongs to a brand already left. */
  private loadSeq = 0;
  private tenantId: string | null = null;

  constructor() {
    void this.load();
  }

  protected retry(): void {
    void this.load();
  }

  protected async selectBrand(brandId: string): Promise<void> {
    if (brandId === this.selectedBrandId()) {
      return;
    }
    this.selectedBrandId.set(brandId);
    this.cancel();
    this.notice.set(null);
    // The previous brand's standings are not this brand's: out of the list now, not when the new ones arrive.
    this.entries.set([]);
    await this.loadEntries(brandId);
  }

  protected begin(entry: CatalogueEntry, kind: 'authorise' | 'revoke'): void {
    this.pending.set({ appId: entry.appId, kind });
    this.reason.set('');
    this.actionError.set(null);
    this.notice.set(null);
  }

  protected cancel(): void {
    this.pending.set(null);
    this.reason.set('');
    this.actionError.set(null);
  }

  protected isPending(entry: CatalogueEntry, kind: 'authorise' | 'revoke'): boolean {
    const current = this.pending();
    return current?.appId === entry.appId && current.kind === kind;
  }

  protected canConfirm(): boolean {
    return !this.submitting() && this.reason().trim().length > 0;
  }

  /** An app can be authorised while it is active and the brand is not already using it. */
  protected canAuthorise(entry: CatalogueEntry): boolean {
    return entry.appStatus === 'ACTIVE' && entry.standing !== 'AUTHORISED';
  }

  protected standingKey(entry: CatalogueEntry): MessageKey {
    switch (entry.standing) {
      case 'AUTHORISED':
        return 'settings.storefrontApps.standing.authorised';
      case 'REVOKED':
        return 'settings.storefrontApps.standing.revoked';
      default:
        return 'settings.storefrontApps.standing.notAuthorised';
    }
  }

  protected clientTypeKey(entry: CatalogueEntry): MessageKey {
    return entry.clientType === 'PUBLIC'
      ? 'settings.storefrontApps.clientType.public'
      : 'settings.storefrontApps.clientType.confidential';
  }

  protected clientTypeNoteKey(entry: CatalogueEntry): MessageKey {
    return entry.clientType === 'PUBLIC'
      ? 'settings.storefrontApps.clientType.publicNote'
      : 'settings.storefrontApps.clientType.confidentialNote';
  }

  protected conformanceKey(entry: CatalogueEntry): MessageKey {
    switch (entry.conformance.status) {
      case 'PASSED':
        return 'settings.storefrontApps.conformance.passed';
      case 'FAILED':
        return 'settings.storefrontApps.conformance.failed';
      case 'EXPIRED':
        return 'settings.storefrontApps.conformance.expired';
      default:
        return 'settings.storefrontApps.conformance.notRun';
    }
  }

  protected async confirm(entry: CatalogueEntry): Promise<void> {
    const tenantId = this.tenantId;
    const brandId = this.selectedBrandId();
    const pending = this.pending();
    if (!tenantId || !brandId || pending?.appId !== entry.appId || !this.canConfirm()) {
      return;
    }
    const reason = this.reason().trim();
    this.submitting.set(true);
    this.actionError.set(null);
    this.notice.set(null);
    try {
      if (pending.kind === 'authorise') {
        await this.api.authorise(tenantId, brandId, entry.appId, reason);
        this.notice.set(this.i18n.t('settings.storefrontApps.authorised', { name: entry.name }));
      } else {
        await this.api.revoke(
          tenantId,
          brandId,
          entry.appId,
          entry.authorisationVersion ?? 0,
          reason,
        );
        this.notice.set(this.i18n.t('settings.storefrontApps.revoked', { name: entry.name }));
      }
      this.pending.set(null);
      this.reason.set('');
      if (this.selectedBrandId() === brandId) {
        await this.loadEntries(brandId);
      }
    } catch (error) {
      this.actionError.set(this.describe(error));
      if (error instanceof ApiError && error.status === 409) {
        // Somebody else changed this authorisation first: show what is true now rather than the stale row.
        await this.loadEntries(brandId);
      }
    } finally {
      this.submitting.set(false);
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
        this.loadErrorText.set(this.i18n.t('settings.storefrontApps.noBrands'));
        this.state.set('error');
        return;
      }
      this.selectedBrandId.set(firstBrand.id);
      await this.loadEntries(firstBrand.id);
      this.state.set(this.state() === 'loading' ? 'ready' : this.state());
    } catch (error) {
      this.handleLoadFailure(error);
    }
  }

  private async loadEntries(brandId: string): Promise<void> {
    const tenantId = this.tenantId;
    if (!tenantId) {
      return;
    }
    const ticket = ++this.loadSeq;
    this.listLoading.set(true);
    try {
      const entries = await this.api.catalogue(tenantId, brandId);
      if (ticket === this.loadSeq && this.selectedBrandId() === brandId) {
        this.entries.set(entries);
      }
    } catch (error) {
      if (ticket === this.loadSeq) {
        this.handleLoadFailure(error);
      }
    } finally {
      if (ticket === this.loadSeq) {
        this.listLoading.set(false);
      }
    }
  }

  private handleLoadFailure(error: unknown): void {
    if (error instanceof ApiError && error.status === 403) {
      this.state.set('denied');
    } else {
      this.loadErrorText.set(this.describe(error));
      this.state.set('error');
    }
  }

  private describe(error: unknown): string {
    return error instanceof ApiError
      ? describeApiError(error, (key, values) => this.i18n.t(key, values))
      : this.i18n.t('error.unknown.noReference');
  }
}
