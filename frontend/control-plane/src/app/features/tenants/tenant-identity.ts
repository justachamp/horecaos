import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { ActivatedRoute } from '@angular/router';

import { ApiError } from '../../core/api/problem';
import { SessionContextService } from '../../core/auth/session-context.service';
import { I18nService } from '../../core/i18n/i18n.service';
import { MessageKey } from '../../core/i18n/messages.en';
import { TenantsApi, TenantView } from './tenants-api';

/**
 * IA 2.6 Identity & realm -- the tenant's sign-in organization, and linking
 * one by hand when onboarding did not.
 *
 * `TenantView.keycloakOrganizationId` is the one piece of this row that is
 * real and queryable: `TenantControlPlaneController` stores and returns it,
 * and `IdentityDriftReporter` reconciles it against Keycloak on a schedule,
 * writing every finding as an ADR 0027 audit fact -- reachable from 7.5
 * Audit log for this tenant, not duplicated here.
 *
 * Named gap: "staff client, courier client, break-glass" are Keycloak client
 * configuration (`StaffLoginKeycloakConfiguration`, `KeycloakConfiguration`),
 * fixed at deploy time and not modeled as a queryable, per-tenant entity
 * anywhere in this codebase. Nothing below invents one.
 */
@Component({
  selector: 'app-tenant-identity',
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './tenant-identity.html',
  styleUrl: './tenant-identity.css',
})
export class TenantIdentity {
  protected readonly i18n = inject(I18nService);
  private readonly tenantsApi = inject(TenantsApi);
  private readonly route = inject(ActivatedRoute);
  protected readonly session = inject(SessionContextService);

  protected readonly tenantId = this.route.snapshot.paramMap.get('tenantId')!;

  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly tenant = signal<TenantView | null>(null);
  protected readonly organizationId = signal('');
  protected readonly linking = signal(false);
  protected readonly linkError = signal<string | null>(null);
  protected readonly linked = signal(false);

  constructor() {
    void this.load();
  }

  private async load(): Promise<void> {
    this.loading.set(true);
    this.loadError.set(null);
    try {
      this.tenant.set(await this.tenantsApi.getTenant(this.tenantId));
    } catch (error) {
      this.loadError.set(this.i18n.describe(error as ApiError));
    } finally {
      this.loading.set(false);
    }
  }

  protected async link(event: Event): Promise<void> {
    event.preventDefault();
    const organizationId = this.organizationId().trim();
    if (organizationId.length === 0 || this.linking()) {
      return;
    }
    this.linking.set(true);
    this.linkError.set(null);
    try {
      this.tenant.set(await this.tenantsApi.linkKeycloakOrganization(this.tenantId, organizationId));
      this.linked.set(true);
    } catch (error) {
      this.linkError.set(this.i18n.describe(error as ApiError));
    } finally {
      this.linking.set(false);
    }
  }

  protected statusKey(status: TenantView['status']): MessageKey {
    return `tenants.status.${status}` as MessageKey;
  }
}
