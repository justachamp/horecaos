import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { ApiError } from '../../core/api/problem';
import { I18nService } from '../../core/i18n/i18n.service';
import { MessageKey } from '../../core/i18n/messages.en';

/** One thing an identifier turned out to be. No personal data: customers and couriers come back by id. */
export interface LookupHit {
  readonly type: string;
  readonly id: string;
  readonly tenantId: string | null;
  readonly tenantName: string | null;
  readonly matchedOn: string;
  readonly label: string;
}

/**
 * IA 10.1 Global lookup -- paste any identifier, find what it is and whose.
 *
 * An id is checked against every kind of record it could be. Anything else is
 * matched exactly as a tenant's slug or name, an order number, a courier's
 * reference, a provider's order or transaction id, or a partner's order
 * reference. Customers and couriers are shown by id only; their names and
 * phones stay behind the tenant's own screens.
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
  private readonly router = inject(Router);

  protected readonly query = signal(inject(ActivatedRoute).snapshot.queryParamMap.get('q') ?? '');
  protected readonly searching = signal(false);
  protected readonly searched = signal(false);
  protected readonly hits = signal<readonly LookupHit[]>([]);
  protected readonly error = signal<string | null>(null);

  constructor() {
    if (this.query().trim().length > 0) {
      void this.run();
    }
  }

  protected async search(event: Event): Promise<void> {
    event.preventDefault();
    if (this.query().trim().length === 0) {
      return;
    }
    // Kept in the address, so a result can be sent to a colleague as a link.
    void this.router.navigate([], { queryParams: { q: this.query().trim() }, replaceUrl: true });
    await this.run();
  }

  private async run(): Promise<void> {
    this.searching.set(true);
    this.searched.set(true);
    this.error.set(null);
    try {
      this.hits.set(
        await firstValueFrom(
          this.api.get<LookupHit[]>('/api/v1/control-plane/lookup', { query: { q: this.query().trim() } }),
        ),
      );
    } catch (thrown) {
      this.hits.set([]);
      this.error.set(this.i18n.describe(thrown as ApiError));
    } finally {
      this.searching.set(false);
    }
  }

  protected typeKey(type: string): MessageKey {
    return `globalLookup.type.${type}` as MessageKey;
  }

  protected matchedKey(matchedOn: string): MessageKey {
    return `globalLookup.matched.${matchedOn}` as MessageKey;
  }

  /** Where a hit opens in this console: the tenant itself, or the part of the tenant it belongs to. */
  protected link(hit: LookupHit): readonly string[] | null {
    switch (hit.type) {
      case 'TENANT':
        return ['/tenants', hit.id];
      case 'BRAND':
      case 'LOCATION':
        return hit.tenantId ? ['/tenants', hit.tenantId, 'brands'] : null;
      default:
        return hit.tenantId ? ['/tenants', hit.tenantId] : null;
    }
  }
}
