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

  /**
   * ADR 0106, gap-map row 10.8a: the bindings-list read whose path helper
   * existed with only a POST caller. Before this wave a tenant admin had no
   * way to see a binding created in an earlier session — only ones this page
   * had itself created since it loaded ({@link createdBindings} in the page,
   * kept for the reason its own doc comment gives).
   */
  async listBindings(
    scope: LocationScope,
    installationId: string,
  ): Promise<readonly BindingView[]> {
    const result = await firstValueFrom(
      this.api.get<readonly BindingView[]>(
        settingsPaths.integrationInstallationBindings(scope, installationId),
      ),
    );
    return result.value ?? [];
  }

  async activateBinding(
    scope: LocationScope,
    installationId: string,
    bindingId: string,
    reason: string,
  ): Promise<{ readonly changed: boolean; readonly outcome: string }> {
    return firstValueFrom(
      this.api.post<{ reason: string }, { changed: boolean; outcome: string }>(
        settingsPaths.integrationInstallationBindingActivate(scope, installationId, bindingId),
        command({ reason }),
      ),
    );
  }

  async suspendBinding(
    scope: LocationScope,
    installationId: string,
    bindingId: string,
    reason: string,
  ): Promise<{ readonly changed: boolean; readonly outcome: string }> {
    return firstValueFrom(
      this.api.post<{ reason: string }, { changed: boolean; outcome: string }>(
        settingsPaths.integrationInstallationBindingSuspend(scope, installationId, bindingId),
        command({ reason }),
      ),
    );
  }

  /**
   * ADR 0106, gap-map row 10.8a / X.14: records a fresh preflight (the secret
   * still resolves, the wired adapter declares each capability) and, as of
   * this wave, stamps the installation's `secretLastUsedAt` on success.
   */
  async reconcileCapabilities(
    scope: LocationScope,
    installationId: string,
  ): Promise<ReconciliationView> {
    return firstValueFrom(
      this.api.post<null, ReconciliationView>(
        settingsPaths.integrationInstallationCapabilityReconciliation(scope, installationId),
        command(null),
      ),
    );
  }

  /** Refused server-side for any installation whose `providerType` is not `clopos`. */
  async getInstallationSettings(
    scope: LocationScope,
    installationId: string,
  ): Promise<CloposSettingsView> {
    const result = await firstValueFrom(
      this.api.get<CloposSettingsView>(
        settingsPaths.integrationInstallationSettings(scope, installationId),
      ),
    );
    return result.value;
  }

  async updateInstallationSettings(
    scope: LocationScope,
    installationId: string,
    requireClerkApproval: boolean,
  ): Promise<CloposSettingsView> {
    return firstValueFrom(
      this.api.post<{ requireClerkApproval: boolean }, CloposSettingsView>(
        settingsPaths.integrationInstallationSettings(scope, installationId),
        command({ requireClerkApproval }),
      ),
    );
  }

  // -------------------------------------------------------------- marketplace liveness

  /** ADR 0106, gap-map row 10.8c: built and capability-gated, never rendered until this wave. */
  async marketplaceLiveness(scope: LocationScope): Promise<readonly LivenessView[]> {
    const result = await firstValueFrom(
      this.api.get<readonly LivenessView[]>(settingsPaths.marketplaceLiveness(scope)),
    );
    return result.value ?? [];
  }

  // -------------------------------------------------------------- tenant failure surface

  async failureTaxonomy(scope: LocationScope): Promise<readonly TenantCategoryCount[]> {
    const result = await firstValueFrom(
      this.api.get<readonly TenantCategoryCount[]>(settingsPaths.integrationFailureTaxonomy(scope)),
    );
    return result.value ?? [];
  }

  async failureInbox(scope: LocationScope): Promise<readonly InboxFailureSummary[]> {
    const result = await firstValueFrom(
      this.api.get<Page<InboxFailureSummary>>(settingsPaths.integrationFailureInbox(scope)),
    );
    return result.value?.items ?? [];
  }

  async replayInboxMessage(
    scope: LocationScope,
    consumerName: string,
    eventId: string,
    reason: string,
  ): Promise<{ readonly changed: boolean; readonly outcome: string }> {
    return firstValueFrom(
      this.api.post<{ reason: string }, { changed: boolean; outcome: string }>(
        settingsPaths.integrationFailureReplay(scope, consumerName, eventId),
        command({ reason }),
      ),
    );
  }

  // -------------------------------------------------------------- partner API clients

  async listPartnerApiClients(
    scope: LocationScope,
    installationId: string,
  ): Promise<readonly PartnerApiClientView[]> {
    const result = await firstValueFrom(
      this.api.get<readonly PartnerApiClientView[]>(
        settingsPaths.partnerApiClients(scope, installationId),
      ),
    );
    return result.value ?? [];
  }

  async issuePartnerApiClient(
    scope: LocationScope,
    installationId: string,
    displayLabel: string,
    reason: string,
  ): Promise<IssuedPartnerApiClient> {
    return firstValueFrom(
      this.api.post<{ displayLabel: string; reason: string }, IssuedPartnerApiClient>(
        settingsPaths.partnerApiClients(scope, installationId),
        command({ displayLabel, reason }),
      ),
    );
  }

  async rotatePartnerApiClient(
    scope: LocationScope,
    installationId: string,
    clientId: string,
    expectedVersion: number,
    reason: string,
  ): Promise<RotatedPartnerApiClient> {
    return firstValueFrom(
      this.api.post<{ reason: string }, RotatedPartnerApiClient>(
        settingsPaths.partnerApiClientRotate(scope, installationId, clientId),
        command({ reason }),
        { params: { expectedVersion } },
      ),
    );
  }

  async revokePartnerApiClient(
    scope: LocationScope,
    installationId: string,
    clientId: string,
    expectedVersion: number,
    reason: string,
  ): Promise<{ readonly changed: boolean; readonly outcome: string }> {
    const response = await firstValueFrom(
      this.api.send<{ reason: string }, { changed: boolean; outcome: string }>(
        'DELETE',
        settingsPaths.partnerApiClientRevoke(scope, installationId, clientId),
        command({ reason }),
        { params: { expectedVersion } },
      ),
    );
    return response.body as { changed: boolean; outcome: string };
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
  /** ADR 0106, gap-map row X.14: when the secret last resolved during a capability-reconciliation preflight. */
  readonly secretLastUsedAt: string | null;
  /** ADR 0106: raw jsonb text, e.g. `{"gtmContainerId":"GTM-ABC1234"}`. Never a secret. */
  readonly nonSensitiveConfig: string | null;
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

/** Mirrors ...ProviderInstallationController.BindingView. Where an installation applies: a brand, or one location of it. */
export interface BindingView {
  readonly id: string;
  readonly brandId: string | null;
  readonly locationId: string | null;
  readonly status: string;
  readonly priority: number;
  readonly effectiveFrom: string;
  readonly effectiveUntil: string | null;
}

/** Mirrors ProviderCapabilityReconciliationService.Reconciliation. */
export interface ReconciliationView {
  readonly connectionStatus: string;
  readonly adapterVersion: string;
  readonly capabilities: Readonly<Record<string, string>>;
}

/** Mirrors ...ProviderInstallationController.CloposSettingsView. Refused for any non-`clopos` provider type. */
export interface CloposSettingsView {
  readonly requireClerkApproval: boolean;
}

/** Mirrors MarketplaceOperationsController.LivenessResponse. `silenceSeconds` null means nothing has ever arrived. */
export interface LivenessView {
  readonly bindingId: string;
  readonly locationId: string;
  readonly providerName: string;
  readonly direction: string;
  readonly lastSuccessAt: string | null;
  readonly lastSuccessReference: string | null;
  readonly lastFailureAt: string | null;
  readonly lastFailureCode: string | null;
  readonly staleAfterSeconds: number;
  readonly observedMedianIntervalSeconds: number | null;
  readonly alertState: string;
  readonly silenceSeconds: number | null;
}

/** Mirrors FailureOperationsService.TenantCategoryCount. */
export interface TenantCategoryCount {
  readonly code: string;
  readonly retryableByTimer: boolean;
  readonly requiresReconciliation: boolean;
  readonly securityRelevant: boolean;
  readonly outboxDeadLettered: number;
  readonly outboxWaiting: number;
  readonly inboxDeadLettered: number;
  readonly inboxWaiting: number;
}

/** Mirrors FailureOperationsService.InboxFailureSummary — never the payload itself (ADR 0029). */
export interface InboxFailureSummary {
  readonly consumerName: string;
  readonly id: string;
  readonly tenantId: string;
  readonly eventType: string;
  readonly status: string;
  readonly attemptCount: number;
  readonly errorCode: string;
  readonly lastError: string;
}

/** Mirrors PartnerApiClientController.PartnerClientResponse. Never the secret value or its ADR 0028 reference. */
export interface PartnerApiClientView {
  readonly id: string;
  readonly clientId: string;
  readonly status: string;
  readonly secretConfigured: boolean;
  readonly secretRotatedAt: string | null;
  readonly secretExpiresAt: string | null;
  readonly lastAuthenticatedAt: string | null;
  readonly version: number;
}

/** Mirrors PartnerApiClientController.IssuedClientResponse. Carries the plaintext secret exactly once. */
export interface IssuedPartnerApiClient {
  readonly id: string;
  readonly clientId: string;
  readonly secretValue: string;
  readonly secretExpiresAt: string | null;
  readonly version: number;
}

/** Mirrors PartnerApiClientController.RotatedClientResponse. Carries the plaintext secret exactly once. */
export interface RotatedPartnerApiClient {
  readonly id: string;
  readonly secretValue: string;
  readonly secretExpiresAt: string | null;
  readonly version: number;
}
