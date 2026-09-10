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
}
