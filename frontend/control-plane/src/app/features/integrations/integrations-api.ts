import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { SessionContextService } from '../../core/auth/session-context.service';

/**
 * The Integrations screen's API surface (ADR 0065).
 *
 * Three server surfaces, one service, because the screen shows one picture
 * ("what is connected") assembled from all three:
 *
 *   - `integration.web.SecretIngressController` — the write-only door.
 *     `/api/v1/control-plane/tenants/{tenantId}/integrations/secrets`. No
 *     method here ever reads one back, because the server has no endpoint
 *     that would let it.
 *   - `integration.web.ProviderInstallationController` — installations,
 *     connect-field declarations, and an installation's credential rotation.
 *     Same `control-plane` surface group.
 *   - `payments.web.MerchantBindingController` — merchant bindings and their
 *     own rotation. `/api/v1/operations/tenants/{tenantId}/merchant-bindings`.
 *     This is a deliberate crossing of ADR 0057's surface-group line: ADR 0065
 *     puts the *screen* in control-plane while the merchant-binding *API*
 *     still lives on the operations surface, and nothing in {@link ApiClient}
 *     restricts a call by group. Flagged here rather than silently relied on.
 */
@Injectable({ providedIn: 'root' })
export class IntegrationsApi {
  private readonly api = inject(ApiClient);
  private readonly session = inject(SessionContextService);

  // -------------------------------------------------------------- reads

  async listInstallations(): Promise<readonly InstallationView[]> {
    const page = await firstValueFrom(
      this.api.get<Page<InstallationView>>(`${this.installationsPath()}`),
    );
    return page.items;
  }

  async listConnectFields(): Promise<readonly ProviderConnectDeclaration[]> {
    return firstValueFrom(
      this.api.get<readonly ProviderConnectDeclaration[]>(`${this.installationsPath()}/connect-fields`),
    );
  }

  async listMerchantBindings(): Promise<readonly MerchantBindingView[]> {
    return firstValueFrom(this.api.get<readonly MerchantBindingView[]>(this.merchantBindingsPath()));
  }

  // -------------------------------------------------------------- the door

  /**
   * Writes a value through the write-only door and returns only the ADR 0028
   * reference it was written under. Never returns, and never itself stores,
   * the value it was called with.
   */
  async writeSecret(request: SecretIngressRequest): Promise<string> {
    const response = await firstValueFrom(
      this.api.post<SecretIngressResponse>(`${this.installationsPath()}/secrets`, request),
    );
    return response.reference;
  }

  // -------------------------------------------------------------- connect (installations)

  async install(request: InstallRequest): Promise<{ readonly installationId: string; readonly status: string }> {
    return firstValueFrom(
      this.api.post<{ installationId: string; status: string }>(this.installationsPath(), request),
    );
  }

  /** ADR 0065's generalization of wave 13's rotate endpoint: a VALUE, not a reference. */
  async rotateInstallationSecret(
    installationId: string,
    request: RotateSecretValueRequest,
  ): Promise<RotateSecretResponse> {
    return firstValueFrom(
      this.api.post<RotateSecretResponse>(
        `${this.installationsPath()}/${installationId}/secret-rotations/value`,
        request,
      ),
    );
  }

  // -------------------------------------------------------------- merchant bindings

  async registerMerchantBinding(request: RegisterMerchantBindingRequest): Promise<MerchantBindingView> {
    return firstValueFrom(this.api.post<MerchantBindingView>(this.merchantBindingsPath(), request));
  }

  async rotateMerchantBindingSecret(
    bindingId: string,
    expectedVersion: number,
    request: RotateSecretValueRequest,
  ): Promise<MerchantBindingView> {
    return firstValueFrom(
      this.api.post<MerchantBindingView>(`${this.merchantBindingsPath()}/${bindingId}/secret-rotations`, request, {
        query: { expectedVersion },
      }),
    );
  }

  async archiveMerchantBinding(bindingId: string, expectedVersion: number): Promise<MerchantBindingView> {
    return firstValueFrom(
      this.api.post<MerchantBindingView>(`${this.merchantBindingsPath()}/${bindingId}/archive`, null, {
        query: { expectedVersion },
      }),
    );
  }

  private installationsPath(): string {
    return `/api/v1/control-plane/tenants/${this.tenantId()}/integrations`;
  }

  private merchantBindingsPath(): string {
    return `/api/v1/operations/tenants/${this.tenantId()}/merchant-bindings`;
  }

  private tenantId(): string {
    const tenantId = this.session.current()?.activeTenantId;
    if (tenantId === null || tenantId === undefined) {
      // The route is capability-guarded but not tenant-guarded; this is the
      // one precondition every call in this service actually needs.
      throw new Error('No active tenant in the session context');
    }
    return tenantId;
  }
}

// -------------------------------------------------------------- wire types

/** Mirrors uz.horecaos.platform.web.api.Page. */
interface Page<T> {
  readonly items: readonly T[];
  readonly nextCursor: string | null;
}

/** Mirrors uz.horecaos.platform.integration.web.ProviderInstallationController.InstallationView. */
export interface InstallationView {
  readonly id: string;
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

/** Mirrors uz.horecaos.platform.payments.web.MerchantBindingController.MerchantBindingView. */
export interface MerchantBindingView {
  readonly id: string;
  readonly legalEntityId: string;
  readonly providerType: string;
  readonly installationId: string;
  readonly integrationBindingId: string;
  readonly merchantAccountReference: string;
  readonly merchantUserReference: string | null;
  readonly merchantIdReference: string | null;
  readonly secretReference: string;
  readonly callbackPathSegment: string;
  readonly supportsReversal: boolean;
  readonly supportsPartnerFiscalization: boolean;
  readonly status: string;
  readonly effectiveFrom: string;
  readonly effectiveUntil: string | null;
  readonly version: number;
  readonly lastSecretRotatedAt: string | null;
}

/** Mirrors uz.horecaos.platform.integration.api.provider.ConnectFieldCatalog.ProviderConnectDeclaration. */
export interface ProviderConnectDeclaration {
  readonly providerType: string;
  readonly category: string;
  readonly fields: readonly ConnectField[];
}

/** Mirrors uz.horecaos.platform.integration.api.provider.ConnectFieldCatalog.ConnectField. */
export interface ConnectField {
  readonly key: string;
  readonly secret: boolean;
}

/**
 * Mirrors uz.horecaos.platform.integration.web.SecretIngressController.SecretIngressRequest.
 * `value` never persists anywhere in this service beyond this one call.
 */
export interface SecretIngressRequest {
  readonly category: string;
  readonly providerType: string;
  readonly value: string;
}

/** Mirrors ...SecretIngressController.SecretIngressResponse. Only ever a reference. */
interface SecretIngressResponse {
  readonly reference: string;
}

/** Mirrors uz.horecaos.platform.integration.web.ProviderInstallationController.InstallRequest. */
export interface InstallRequest {
  readonly category: string;
  readonly providerType: string;
  readonly environmentCode: string;
  readonly displayName: string;
  readonly secretReference?: string;
  readonly externalAccountReference?: string;
}

/** Mirrors ...ProviderInstallationController.RotateSecretValueRequest and MerchantBindingController's own. */
export interface RotateSecretValueRequest {
  readonly value: string;
  readonly reason: string;
}

/** Mirrors ...ProviderInstallationController.RotateSecretResponse. Reference strings only, never a value. */
export interface RotateSecretResponse {
  readonly installationId: string;
  readonly oldSecretReference: string;
  readonly newSecretReference: string;
  readonly botUsername: string | null;
}

/** Mirrors uz.horecaos.platform.payments.web.MerchantBindingController.RegisterMerchantBindingRequest. */
export interface RegisterMerchantBindingRequest {
  readonly legalEntityId: string;
  readonly providerType: string;
  readonly installationId: string;
  readonly integrationBindingId: string;
  readonly merchantAccountReference: string;
  readonly merchantUserReference?: string | null;
  readonly merchantIdReference?: string | null;
  readonly secretReference: string;
  readonly callbackPathSegment: string;
  readonly supportsReversal: boolean;
  readonly supportsPartnerFiscalization: boolean;
  readonly effectiveFrom: string;
  readonly effectiveUntil?: string | null;
}
