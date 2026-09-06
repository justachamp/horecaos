import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../../core/api/api-client';
import { LocationScope } from '../../../core/api/operations-paths';
import { Page } from '../../../core/api/page';
import { settingsPaths } from '../../../core/api/settings-paths';
import { command } from '../../../core/api/idempotency';

/**
 * The Integrations screen's API surface (ADR 0065, moved to operations Settings
 * in wave 26 — see this app's `docs/adr/partial/0065-*.md` for the placement
 * decision).
 *
 * Three server surfaces, one service — kept visible here rather than behind a
 * single facade endpoint, the same shape the control-plane original this was
 * ported from used:
 *
 *   - `integration.web.OperationsSecretIngressController` — the write-only
 *     door, at `/api/v1/operations/tenants/{tenantId}/integrations/secrets`
 *     since wave 53. It forwards, unchanged, to
 *     `integration.web.SecretIngressController`'s original
 *     `/api/v1/control-plane/...` implementation, which stays published
 *     (`OpenApiContractTests` refuses a published path ever disappearing) but
 *     is no longer this screen's caller. No method below ever reads a value
 *     back, because the server has no endpoint that would let it.
 *   - `integration.web.OperationsProviderInstallationController` —
 *     installations, connect-field declarations, and an installation's
 *     credential rotation. Same wave-53 move, same forwarding relationship to
 *     `ProviderInstallationController`.
 *   - `payments.web.MerchantBindingController` — merchant bindings and their
 *     own rotation. Already on the operations surface
 *     (`/api/v1/operations/tenants/{tenantId}/merchant-bindings`) — this is
 *     the one cross-surface case wave 26 actually resolves: the screen and
 *     this API now agree on which app they belong to.
 */
@Injectable({ providedIn: 'root' })
export class IntegrationsApi {
  private readonly api = inject(ApiClient);

  // -------------------------------------------------------------- reads

  async listInstallations(scope: LocationScope): Promise<readonly InstallationView[]> {
    const result = await firstValueFrom(
      this.api.get<Page<InstallationView>>(settingsPaths.integrationInstallations(scope)),
    );
    return result.value?.items ?? [];
  }

  async listConnectFields(scope: LocationScope): Promise<readonly ProviderConnectDeclaration[]> {
    const result = await firstValueFrom(
      this.api.get<readonly ProviderConnectDeclaration[]>(
        settingsPaths.integrationConnectFields(scope),
      ),
    );
    return result.value ?? [];
  }

  async listMerchantBindings(scope: LocationScope): Promise<readonly MerchantBindingView[]> {
    const result = await firstValueFrom(
      this.api.get<readonly MerchantBindingView[]>(settingsPaths.merchantBindings(scope)),
    );
    return result.value ?? [];
  }

  // -------------------------------------------------------------- the door

  /**
   * Writes a value through the write-only door and returns only the ADR 0028
   * reference it was written under. Never returns, and never itself stores,
   * the value it was called with.
   */
  async writeSecret(scope: LocationScope, request: SecretIngressRequest): Promise<string> {
    const response = await firstValueFrom(
      this.api.post<SecretIngressRequest, SecretIngressResponse>(
        settingsPaths.integrationSecrets(scope),
        command(request),
      ),
    );
    return response.reference;
  }

  // -------------------------------------------------------------- connect (installations)

  async install(
    scope: LocationScope,
    request: InstallRequest,
  ): Promise<{ readonly installationId: string; readonly status: string }> {
    return firstValueFrom(
      this.api.post<InstallRequest, { installationId: string; status: string }>(
        settingsPaths.integrationInstallations(scope),
        command(request),
      ),
    );
  }

  /** ADR 0065's generalization of wave 13's rotate endpoint: a VALUE, not a reference. */
  async rotateInstallationSecret(
    scope: LocationScope,
    installationId: string,
    request: RotateSecretValueRequest,
  ): Promise<RotateSecretResponse> {
    return firstValueFrom(
      this.api.post<RotateSecretValueRequest, RotateSecretResponse>(
        settingsPaths.integrationInstallationRotate(scope, installationId),
        command(request),
      ),
    );
  }

  /**
   * Binds a just-connected installation to a brand or, narrower, one of its
   * locations (ADR 0026). Wired into the connect drawer's own second step in
   * wave 66 — the only caller of this endpoint anywhere in this app, because
   * before this wave the connect flow ended at {@link install} and nothing
   * ever bound the result to anything. `capabilities`/`primaryCapabilities`
   * travel empty: unlike POS (`PosCapability`) and delivery
   * (`DeliveryCapability`), the platform declares no capability catalogue for
   * a PAYMENT or NOTIFICATION installation, so there is nothing a picker could
   * render here yet — the same class of gap this wave's own report calls out
   * for the missing RETIRED transition, not something this wave invents a
   * backend catalogue to paper over.
   */
  async bindInstallation(
    scope: LocationScope,
    installationId: string,
    request: BindInstallationRequest,
  ): Promise<BindInstallationResponse> {
    return firstValueFrom(
      this.api.post<BindInstallationRequest, BindInstallationResponse>(
        settingsPaths.integrationInstallationBindings(scope, installationId),
        command(request),
      ),
    );
  }

  // -------------------------------------------------------------- merchant bindings

  async registerMerchantBinding(
    scope: LocationScope,
    request: RegisterMerchantBindingRequest,
  ): Promise<MerchantBindingView> {
    return firstValueFrom(
      this.api.post<RegisterMerchantBindingRequest, MerchantBindingView>(
        settingsPaths.merchantBindings(scope),
        command(request),
      ),
    );
  }

  async rotateMerchantBindingSecret(
    scope: LocationScope,
    bindingId: string,
    expectedVersion: number,
    request: RotateSecretValueRequest,
  ): Promise<MerchantBindingView> {
    return firstValueFrom(
      this.api.post<RotateSecretValueRequest, MerchantBindingView>(
        settingsPaths.merchantBindingRotate(scope, bindingId),
        command(request),
        { params: { expectedVersion } },
      ),
    );
  }

  async archiveMerchantBinding(
    scope: LocationScope,
    bindingId: string,
    expectedVersion: number,
  ): Promise<MerchantBindingView> {
    return firstValueFrom(
      this.api.post<null, MerchantBindingView>(
        settingsPaths.merchantBindingArchive(scope, bindingId),
        command(null),
        { params: { expectedVersion } },
      ),
    );
  }
}

// -------------------------------------------------------------- wire types

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

/** Mirrors uz.horecaos.platform.integration.web.ProviderInstallationController.BindRequest. */
export interface BindInstallationRequest {
  readonly brandId: string;
  readonly locationId?: string | null;
  readonly priority?: number;
  readonly capabilities: readonly string[];
  readonly primaryCapabilities: readonly string[];
}

/** The `bind` endpoint's response: a fresh SUSPENDED binding, awaiting activation. */
export interface BindInstallationResponse {
  readonly bindingId: string;
  readonly status: string;
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
