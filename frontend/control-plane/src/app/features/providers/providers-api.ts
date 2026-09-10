import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { Page } from '../../core/api/page';

/** ConnectFieldCatalog.ConnectField. */
export interface ConnectField {
  readonly key: string;
  readonly secret: boolean;
}

/** ConnectFieldCatalog.ProviderConnectDeclaration -- PlatformIntegrationAdminController.providers(). */
export interface ProviderConnectDeclaration {
  readonly providerType: string;
  readonly category: 'POS' | 'PAYMENT' | 'DELIVERY' | 'NOTIFICATION' | 'GEOCODING' | 'OTHER';
  readonly fields: readonly ConnectField[];
}

/** PosCapabilityMatrixController.AdapterCapabilities. */
export interface AdapterCapabilities {
  readonly providerType: string;
  readonly declaredCapabilities: readonly string[];
}

/** PlatformIntegrationAdminController.PlatformInstallationView. */
export interface PlatformInstallationView {
  readonly id: string;
  readonly tenantId: string;
  readonly tenantSlug: string;
  readonly tenantDisplayName: string;
  readonly category: string;
  readonly providerType: string;
  readonly environmentCode: string;
  readonly displayName: string;
  readonly status: string;
  readonly secretReference: string | null;
  readonly lastConnectionStatus: string | null;
  readonly adapterVersion: string | null;
  readonly lastSecretRotatedAt: string | null;
}

/** ProviderInstallationController.BindingView: where one installation applies. */
export interface BindingView {
  readonly id: string;
  readonly brandId: string | null;
  readonly locationId: string | null;
  readonly status: 'DRAFT' | 'ACTIVE' | 'SUSPENDED' | (string & {});
  readonly priority: number;
  readonly effectiveFrom: string;
  readonly effectiveUntil: string | null;
}

/** What a connection check found. POS answers through its own discovery run, in a different shape. */
export interface ConnectionCheck {
  readonly connectionStatus?: string;
  readonly capabilities?: Readonly<Record<string, unknown>>;
}

/** An approved provider endpoint a tenant can be installed against; tenants never supply a URL. */
export interface ProviderEnvironment {
  readonly code: string;
  readonly category: string;
  readonly providerType: string;
  readonly production: boolean;
  readonly notes: string | null;
}

/** Where a credential is filed in the secrets manager, by provider category. */
const SECRET_CATEGORY: Readonly<Record<string, string>> = {
  POS: 'PROVIDER_POS',
  PAYMENT: 'PROVIDER_PAYMENT',
  DELIVERY: 'PROVIDER_DELIVERY',
  NOTIFICATION: 'PROVIDER_NOTIFICATION',
  VOICE: 'PROVIDER_VOICE',
};

/** Whether a credential can be written for this provider category at all. */
export function acceptsCredential(category: string): boolean {
  return category in SECRET_CATEGORY;
}

/** EventContractController.EventContractResponse. */
export interface EventContractView {
  readonly eventType: string;
  readonly eventVersion: number;
  readonly producingModule: string;
  readonly topic: string;
  readonly partitionKey: string;
  readonly retention: 'BUSINESS_FACT' | 'COMMAND' | 'DIAGNOSTIC' | 'SIGNAL' | (string & {});
  readonly classification: 'PUBLIC' | 'INTERNAL' | (string & {});
  readonly description: string;
}

/**
 * The platform-scope, cross-tenant provider reads IA §3 needs
 * (`PlatformIntegrationAdminController`, `PosCapabilityMatrixController`,
 * `EventContractController`).
 */
@Injectable({ providedIn: 'root' })
export class ProvidersApi {
  private readonly api = inject(ApiClient);

  async listProviders(): Promise<ProviderConnectDeclaration[]> {
    return firstValueFrom(
      this.api.get<ProviderConnectDeclaration[]>('/api/v1/control-plane/providers'),
    );
  }

  async capabilityMatrix(): Promise<AdapterCapabilities[]> {
    return firstValueFrom(
      this.api.get<AdapterCapabilities[]>('/api/v1/control-plane/pos-capability-matrix'),
    );
  }

  async listInstallations(cursor: string | null = null, limit = 50): Promise<Page<PlatformInstallationView>> {
    return firstValueFrom(
      this.api.getPage<PlatformInstallationView>('/api/v1/control-plane/installations', {
        cursor,
        limit,
      }),
    );
  }

  /** IA 3.4 Contracts & versions -- the ADR 0032 event/schema contract half only; see the screen's own doc comment. */
  async listEventContracts(): Promise<EventContractView[]> {
    return firstValueFrom(
      this.api.get<EventContractView[]>('/api/v1/control-plane/event-contracts'),
    );
  }

  async bindings(tenantId: string, installationId: string): Promise<BindingView[]> {
    return firstValueFrom(
      this.api.get<BindingView[]>(`/api/v1/control-plane/tenants/${tenantId}/integrations/${installationId}/bindings`),
    );
  }

  /** Refused until the installation has passed a connection check. */
  async activateBinding(tenantId: string, installationId: string, bindingId: string, reason: string): Promise<{ changed: boolean }> {
    return firstValueFrom(
      this.api.post<{ changed: boolean }>(
        `/api/v1/control-plane/tenants/${tenantId}/integrations/${installationId}/bindings/${bindingId}/activate`,
        { reason },
      ),
    );
  }

  /** The rollback path: the place returns to a manual process; mappings and evidence stay. */
  async suspendBinding(tenantId: string, installationId: string, bindingId: string, reason: string): Promise<{ changed: boolean }> {
    return firstValueFrom(
      this.api.post<{ changed: boolean }>(
        `/api/v1/control-plane/tenants/${tenantId}/integrations/${installationId}/bindings/${bindingId}/suspend`,
        { reason },
      ),
    );
  }

  /**
   * Probes the provider with the restaurant's own credential. POS discovers
   * through its sync-run path, because what a POS credential can do depends on
   * the staff user it acts as.
   */
  async checkConnection(tenantId: string, installation: Pick<PlatformInstallationView, 'id' | 'category' | 'providerType'>): Promise<ConnectionCheck> {
    return firstValueFrom(
      installation.category === 'POS'
        ? this.api.post<ConnectionCheck>(`/api/v1/control-plane/tenants/${tenantId}/pos-sync-runs/capability-reconciliation`, {
            installationId: installation.id,
            providerType: installation.providerType,
          })
        : this.api.post<ConnectionCheck>(
            `/api/v1/control-plane/tenants/${tenantId}/integrations/${installation.id}/capability-reconciliation`,
            {},
          ),
    );
  }

  // ------------------------------------------------------------ installing on a tenant's behalf

  async environments(): Promise<ProviderEnvironment[]> {
    return firstValueFrom(this.api.get<ProviderEnvironment[]>('/api/v1/control-plane/provider-environments'));
  }

  /**
   * Writes a credential through the write-only door and returns only the
   * reference it was filed under. The value is never stored or returned.
   */
  async writeCredential(tenantId: string, category: string, providerType: string, value: string): Promise<string> {
    const response = await firstValueFrom(
      this.api.post<{ reference: string }>(`/api/v1/control-plane/tenants/${tenantId}/integrations/secrets`, {
        category: SECRET_CATEGORY[category],
        providerType,
        value,
      }),
    );
    return response.reference;
  }

  async install(
    tenantId: string,
    request: {
      readonly category: string;
      readonly providerType: string;
      readonly environmentCode: string;
      readonly displayName: string;
      readonly secretReference?: string;
      readonly externalAccountReference?: string;
    },
  ): Promise<{ installationId: string; status: string }> {
    return firstValueFrom(
      this.api.post<{ installationId: string; status: string }>(
        `/api/v1/control-plane/tenants/${tenantId}/integrations`,
        request,
      ),
    );
  }

  /** Created suspended: someone confirms it points at the right place, then activates it. */
  async bind(
    tenantId: string,
    installationId: string,
    request: {
      readonly brandId: string;
      readonly locationId?: string;
      readonly capabilities: readonly string[];
      readonly primaryCapabilities: readonly string[];
    },
  ): Promise<{ bindingId: string }> {
    return firstValueFrom(
      this.api.post<{ bindingId: string }>(
        `/api/v1/control-plane/tenants/${tenantId}/integrations/${installationId}/bindings`,
        { ...request, priority: 100 },
      ),
    );
  }

  /** Replaces the credential through the write-only door; only references come back. */
  async rotateCredential(tenantId: string, installationId: string, value: string, reason: string): Promise<void> {
    await firstValueFrom(
      this.api.post<unknown>(
        `/api/v1/control-plane/tenants/${tenantId}/integrations/${installationId}/secret-rotations/value`,
        { value, reason },
      ),
    );
  }

  /** Clopos only: whether a clerk still accepts each exported order at the till. */
  async cloposSettings(tenantId: string, installationId: string): Promise<{ requireClerkApproval: boolean }> {
    return firstValueFrom(
      this.api.get<{ requireClerkApproval: boolean }>(
        `/api/v1/control-plane/tenants/${tenantId}/integrations/${installationId}/settings`,
      ),
    );
  }

  async setCloposSettings(tenantId: string, installationId: string, requireClerkApproval: boolean): Promise<void> {
    await firstValueFrom(
      this.api.post<unknown>(`/api/v1/control-plane/tenants/${tenantId}/integrations/${installationId}/settings`, {
        requireClerkApproval,
      }),
    );
  }
}
