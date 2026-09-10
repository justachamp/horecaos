import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';

import { CommerceApi, EntitlementSnapshot } from '../commerce/commerce-api';
import { ApiError } from '../../core/api/problem';
import { SessionContextService } from '../../core/auth/session-context.service';
import { I18nService } from '../../core/i18n/i18n.service';
import { MessageKey } from '../../core/i18n/messages.en';
import { BrandView, TenantView, TenantsApi } from './tenants-api';

/**
 * IA 2.2 Tenant overview -- one tenant at a glance, with the quick actions
 * that reach every other tenant sub-screen (2.3-2.5, 2.8).
 *
 * Deliberately not one aggregate call: no single endpoint composes tenant +
 * brands + entitlements (the server javadoc for `TenantControlPlaneController`
 * says so explicitly), so this screen makes three and tolerates the
 * entitlements one failing on its own -- a tenant with no subscription yet
 * is not a reason to hide the tenant record itself.
 */
@Component({
  selector: 'app-tenant-detail',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink],
  templateUrl: './tenant-detail.html',
  styleUrl: './tenant-detail.css',
})
export class TenantDetail {
  protected readonly i18n = inject(I18nService);
  private readonly tenantsApi = inject(TenantsApi);
  private readonly commerceApi = inject(CommerceApi);
  private readonly route = inject(ActivatedRoute);
  protected readonly session = inject(SessionContextService);

  protected readonly tenantId = this.route.snapshot.paramMap.get('tenantId')!;

  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly tenant = signal<TenantView | null>(null);
  protected readonly brands = signal<readonly BrandView[]>([]);
  protected readonly entitlements = signal<EntitlementSnapshot | null>(null);

  /** Which status change is being confirmed, if any. */
  protected readonly changing = signal<'suspend' | 'reactivate' | null>(null);
  protected readonly reason = signal('');
  protected readonly working = signal(false);
  protected readonly statusError = signal<string | null>(null);
  protected readonly statusDone = signal<string | null>(null);

  constructor() {
    void this.load();
  }

  private async load(): Promise<void> {
    this.loading.set(true);
    this.loadError.set(null);
    try {
      const [tenant, brands] = await Promise.all([
        this.tenantsApi.getTenant(this.tenantId),
        this.tenantsApi.getBrands(this.tenantId),
      ]);
      this.tenant.set(tenant);
      this.brands.set(brands);
    } catch (error) {
      this.loadError.set(this.i18n.describe(error as ApiError));
      this.loading.set(false);
      return;
    }
    this.loading.set(false);

    try {
      this.entitlements.set(await this.commerceApi.getEntitlements(this.tenantId));
    } catch {
      // A tenant that has never subscribed has no entitlements row yet; the
      // panel shows its own not-yet-subscribed state rather than failing
      // the whole screen over it.
      this.entitlements.set(null);
    }
  }

  protected openStatusChange(change: 'suspend' | 'reactivate'): void {
    this.changing.set(change);
    this.reason.set('');
    this.statusError.set(null);
    this.statusDone.set(null);
  }

  protected cancelStatusChange(): void {
    this.changing.set(null);
  }

  /**
   * Suspend or reactivate, with the reason the server records in the audit
   * log. The tenant returned is the one shown from then on, so the panel
   * turns into the opposite action without a reload.
   */
  protected async confirmStatusChange(event: Event): Promise<void> {
    event.preventDefault();
    const change = this.changing();
    const reason = this.reason().trim();
    if (change === null || reason.length === 0 || this.working()) {
      return;
    }
    this.working.set(true);
    this.statusError.set(null);
    try {
      const updated =
        change === 'suspend'
          ? await this.tenantsApi.suspendTenant(this.tenantId, reason)
          : await this.tenantsApi.reactivateTenant(this.tenantId, reason);
      this.tenant.set(updated);
      this.changing.set(null);
      this.statusDone.set(
        this.i18n.t(change === 'suspend' ? 'tenantDetail.status.suspended' : 'tenantDetail.status.reactivated'),
      );
    } catch (error) {
      this.statusError.set(this.i18n.describe(error as ApiError));
    } finally {
      this.working.set(false);
    }
  }

  protected statusKey(status: string): MessageKey {
    return `tenants.status.${status}` as MessageKey;
  }
}
