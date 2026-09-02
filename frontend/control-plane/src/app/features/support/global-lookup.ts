import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { RouterLink } from '@angular/router';

import { ApiClient } from '../../core/api/api-client';
import { ApiError } from '../../core/api/problem';
import { I18nService } from '../../core/i18n/i18n.service';
import { TenantView } from '../tenants/tenants-api';

/**
 * IA 10.1 Global lookup -- find an order, customer, courier or device across
 * tenants by any identifier, including provider external IDs.
 *
 * What is real today: `GET /control-plane/tenants/by-slug/{slug}`, a
 * platform-admin tenant-by-slug lookup. What is not: any cross-tenant index
 * over orders, customers, couriers, or devices -- confirmed absent by
 * exhaustive grep across the backend, and building one that respects tenant
 * isolation and ADR 0029 PII gating is a genuine new subsystem, not a small
 * read-model addition. This screen offers the one real lookup and says so
 * about the rest, rather than a stub that does nothing at all.
 */
@Component({
  selector: 'app-global-lookup',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink],
  templateUrl: './global-lookup.html',
  styleUrl: './global-lookup.css',
})
export class GlobalLookup {
  protected readonly i18n = inject(I18nService);
  private readonly api = inject(ApiClient);

  protected readonly slug = signal('');
  protected readonly searching = signal(false);
  protected readonly searched = signal(false);
  protected readonly result = signal<TenantView | null>(null);
  protected readonly notFound = signal(false);
  protected readonly error = signal<string | null>(null);

  protected async search(event: Event): Promise<void> {
    event.preventDefault();
    const slug = this.slug().trim();
    if (slug.length === 0) {
      return;
    }
    this.searching.set(true);
    this.searched.set(true);
    this.notFound.set(false);
    this.error.set(null);
    this.result.set(null);
    try {
      const tenant = await firstValueFrom(
        this.api.get<TenantView>(`/api/v1/control-plane/tenants/by-slug/${encodeURIComponent(slug)}`),
      );
      this.result.set(tenant);
    } catch (thrown) {
      const apiError = thrown as ApiError;
      if (apiError.code === 'RESOURCE_NOT_FOUND') {
        this.notFound.set(true);
      } else {
        this.error.set(this.i18n.describe(apiError));
      }
    } finally {
      this.searching.set(false);
    }
  }
}
