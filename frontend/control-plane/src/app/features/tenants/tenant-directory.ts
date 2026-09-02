import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';

import { asDate } from '../../core/api/dates';
import { ApiError } from '../../core/api/problem';
import { I18nService } from '../../core/i18n/i18n.service';
import { MessageKey } from '../../core/i18n/messages.en';
import {
  CreateTenantRequest,
  CustomerIdentityMode,
  TenantSummaryView,
  TenantsApi,
} from './tenants-api';

/**
 * IA 2.1 Tenant directory -- every tenant, and the bootstrap creation flow
 * (ADR 0055 phase 5's own proving run: `POST /control-plane/tenants` is the
 * first call of the maker-checker journey this wave's exit criterion names).
 *
 * The directory itself has an honest gap: `TenantSummaryView` carries no
 * plan, business type, or health score, because none of the three exists
 * anywhere in the tenancy schema yet (see the server javadoc this screen's
 * DTO mirrors). What it shows is what is real: slug, legal and display name,
 * currency, timezone, status, and when it was created.
 */
@Component({
  selector: 'app-tenant-directory',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink],
  templateUrl: './tenant-directory.html',
  styleUrl: './tenant-directory.css',
})
export class TenantDirectory {
  protected readonly i18n = inject(I18nService);
  protected readonly asDate = asDate;
  private readonly tenantsApi = inject(TenantsApi);
  private readonly router = inject(Router);

  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly tenants = signal<readonly TenantSummaryView[]>([]);
  protected readonly nextCursor = signal<string | null>(null);
  protected readonly loadingMore = signal(false);

  protected readonly creating = signal(false);
  protected readonly createSubmitting = signal(false);
  protected readonly createError = signal<string | null>(null);

  protected readonly slug = signal('');
  protected readonly legalName = signal('');
  protected readonly displayName = signal('');
  protected readonly defaultCurrency = signal('UZS');
  protected readonly defaultTimezone = signal('Asia/Tashkent');
  protected readonly customerIdentityMode = signal<CustomerIdentityMode>('TENANT_SHARED');

  constructor() {
    void this.load();
  }

  private async load(): Promise<void> {
    this.loading.set(true);
    this.loadError.set(null);
    try {
      const page = await this.tenantsApi.listTenants();
      this.tenants.set(page.items);
      this.nextCursor.set(page.nextCursor);
    } catch (error) {
      this.loadError.set(this.i18n.describe(error as ApiError));
    } finally {
      this.loading.set(false);
    }
  }

  protected async loadMore(): Promise<void> {
    const cursor = this.nextCursor();
    if (cursor === null || this.loadingMore()) {
      return;
    }
    this.loadingMore.set(true);
    try {
      const page = await this.tenantsApi.listTenants(cursor);
      this.tenants.update((existing) => [...existing, ...page.items]);
      this.nextCursor.set(page.nextCursor);
    } catch (error) {
      this.loadError.set(this.i18n.describe(error as ApiError));
    } finally {
      this.loadingMore.set(false);
    }
  }

  protected openCreate(): void {
    this.creating.set(true);
    this.createError.set(null);
  }

  protected closeCreate(): void {
    this.creating.set(false);
  }

  protected canSubmitCreate(): boolean {
    return (
      !this.createSubmitting() &&
      this.slug().trim().length > 0 &&
      this.legalName().trim().length > 0 &&
      this.displayName().trim().length > 0 &&
      this.defaultCurrency().trim().length === 3 &&
      this.defaultTimezone().trim().length > 0
    );
  }

  protected async submitCreate(event: Event): Promise<void> {
    event.preventDefault();
    if (!this.canSubmitCreate()) {
      return;
    }
    this.createSubmitting.set(true);
    this.createError.set(null);
    const request: CreateTenantRequest = {
      slug: this.slug().trim(),
      legalName: this.legalName().trim(),
      displayName: this.displayName().trim(),
      defaultCurrency: this.defaultCurrency().trim().toUpperCase(),
      defaultTimezone: this.defaultTimezone().trim(),
      customerIdentityMode: this.customerIdentityMode(),
    };
    try {
      const tenant = await this.tenantsApi.createTenant(request);
      // Created tenants go straight to their own detail page: onboarding a
      // tenant is the very next thing a platform admin does after creating
      // one, and returning to a list they would just leave again serves
      // nobody.
      await this.router.navigate(['/tenants', tenant.id]);
    } catch (error) {
      this.createError.set(this.i18n.describe(error as ApiError));
    } finally {
      this.createSubmitting.set(false);
    }
  }

  protected statusKey(status: TenantSummaryView['status']): MessageKey {
    return `tenants.status.${status}` as MessageKey;
  }
}
