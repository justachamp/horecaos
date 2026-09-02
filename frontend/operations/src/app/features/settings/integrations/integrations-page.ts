import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';

import { ApiError } from '../../../core/api/problem-details';
import { CurrentLocation } from '../../../core/auth/current-location';
import { describeApiError } from '../../orders/order-errors';
import { I18n } from '../../../core/i18n/i18n';
import { MessageKey } from '../../../core/i18n/messages.en';
import { ConnectProviderPanel, ConnectSubmission } from './connect-provider-panel';
import {
  InstallationView,
  IntegrationsApi,
  MerchantBindingView,
  ProviderConnectDeclaration,
} from './integrations-api';
import {
  RegisterBindingSubmission,
  RegisterMerchantBindingPanel,
} from './register-merchant-binding-panel';
import { RotateSecretDialog, RotateSecretSubmission } from './rotate-secret-dialog';

/** The one provider type the platform can verify a rotated credential against (ADR 0065, wave 13). */
const VERIFIABLE_PROVIDER_TYPE = 'TELEGRAM_BOT_API';

const STATUS_KEYS: Readonly<Record<string, MessageKey>> = {
  DRAFT: 'settings.integrations.status.DRAFT',
  ACTIVE: 'settings.integrations.status.ACTIVE',
  SUSPENDED: 'settings.integrations.status.SUSPENDED',
  RETIRED: 'settings.integrations.status.RETIRED',
  UNVERIFIED: 'settings.integrations.status.UNVERIFIED',
  SUCCEEDED: 'settings.integrations.status.SUCCEEDED',
  FAILED: 'settings.integrations.status.FAILED',
};

type RotationTarget =
  | { readonly kind: 'installation'; readonly id: string; readonly verifiable: boolean }
  | { readonly kind: 'binding'; readonly id: string; readonly expectedVersion: number };

/**
 * 10.8 Integrations (ADR 0065) — moved here from `frontend/control-plane` in
 * wave 26, the owner's 2026-09-02 placement decision: merchant self-service
 * belongs to `apps/operations`, never to control-plane. A tenant admin
 * connects Click, Payme and Telegram, rotates their credentials through the
 * write-only door, and archives a merchant binding it no longer needs — with
 * no infrastructure access and without ever seeing a credential value come
 * back from the API.
 *
 * Two lists (installations, merchant bindings) assembled from three server
 * surfaces -- see {@link IntegrationsApi}'s own doc comment for why, and for
 * which of the three surface-crossings this move actually resolves.
 */
@Component({
  selector: 'q-integrations-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ConnectProviderPanel, RegisterMerchantBindingPanel, RotateSecretDialog],
  templateUrl: './integrations-page.html',
  styleUrl: './integrations-page.css',
})
export class IntegrationsPage {
  private readonly api = inject(IntegrationsApi);
  private readonly location = inject(CurrentLocation);
  protected readonly i18n = inject(I18n);

  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly denied = signal(false);
  protected readonly installations = signal<readonly InstallationView[]>([]);
  protected readonly merchantBindings = signal<readonly MerchantBindingView[]>([]);
  protected readonly connectFields = signal<readonly ProviderConnectDeclaration[]>([]);

  protected readonly showConnectPanel = signal(false);
  protected readonly connectSubmitting = signal(false);
  protected readonly connectError = signal<string | null>(null);

  protected readonly showRegisterBindingPanel = signal(false);
  protected readonly registerSubmitting = signal(false);
  protected readonly registerError = signal<string | null>(null);

  protected readonly rotating = signal<RotationTarget | null>(null);
  protected readonly rotateSubmitting = signal(false);
  protected readonly rotateError = signal<string | null>(null);

  constructor() {
    void this.load();
  }

  protected statusLabel(status: string): string {
    const key = STATUS_KEYS[status];
    return key === undefined ? status : this.i18n.t(key);
  }

  protected credentialLabel(secretReference: string | null): string {
    return secretReference === null || secretReference === ''
      ? this.i18n.t('settings.integrations.credential.none')
      : this.i18n.t('settings.integrations.credential.configured');
  }

  protected lastRotatedLabel(lastSecretRotatedAt: string | null): string {
    return lastSecretRotatedAt === null
      ? this.i18n.t('settings.integrations.lastRotated.never')
      : new Date(lastSecretRotatedAt).toLocaleDateString(this.i18n.locale());
  }

  protected openConnect(): void {
    this.connectError.set(null);
    this.showConnectPanel.set(true);
  }

  protected async onConnect(submission: ConnectSubmission): Promise<void> {
    const scope = this.location.scope();
    if (!scope) {
      return;
    }
    this.connectSubmitting.set(true);
    this.connectError.set(null);
    try {
      const reference = await this.api.writeSecret(scope, {
        category: `PROVIDER_${submission.category}`,
        providerType: submission.providerType,
        value: submission.secretValue,
      });
      await this.api.install(scope, {
        category: submission.category,
        providerType: submission.providerType,
        environmentCode: submission.environmentCode,
        displayName: submission.displayName,
        secretReference: reference,
        externalAccountReference: submission.reference === '' ? undefined : submission.reference,
      });
      this.showConnectPanel.set(false);
      await this.reloadInstallations(scope);
    } catch (failure) {
      this.connectError.set(this.describeError(failure));
    } finally {
      this.connectSubmitting.set(false);
    }
  }

  protected openRegisterBinding(): void {
    this.registerError.set(null);
    this.showRegisterBindingPanel.set(true);
  }

  protected async onRegisterBinding(submission: RegisterBindingSubmission): Promise<void> {
    const scope = this.location.scope();
    if (!scope) {
      return;
    }
    this.registerSubmitting.set(true);
    this.registerError.set(null);
    try {
      const reference = await this.api.writeSecret(scope, {
        category: 'PROVIDER_PAYMENT',
        providerType: submission.providerType,
        value: submission.secretValue,
      });
      await this.api.registerMerchantBinding(scope, {
        legalEntityId: submission.legalEntityId,
        providerType: submission.providerType,
        installationId: submission.installationId,
        integrationBindingId: submission.integrationBindingId,
        merchantAccountReference: submission.merchantAccountReference,
        secretReference: reference,
        callbackPathSegment: submission.callbackPathSegment,
        supportsReversal: submission.providerType === 'CLICK',
        supportsPartnerFiscalization: true,
        effectiveFrom: new Date().toISOString().slice(0, 10),
      });
      this.showRegisterBindingPanel.set(false);
      await this.reloadMerchantBindings(scope);
    } catch (failure) {
      this.registerError.set(this.describeError(failure));
    } finally {
      this.registerSubmitting.set(false);
    }
  }

  protected openRotateInstallation(installation: InstallationView): void {
    this.rotateError.set(null);
    this.rotating.set({
      kind: 'installation',
      id: installation.id,
      verifiable: installation.providerType === VERIFIABLE_PROVIDER_TYPE,
    });
  }

  protected openRotateBinding(binding: MerchantBindingView): void {
    this.rotateError.set(null);
    this.rotating.set({ kind: 'binding', id: binding.id, expectedVersion: binding.version });
  }

  protected async onRotateConfirm(submission: RotateSecretSubmission): Promise<void> {
    const scope = this.location.scope();
    const target = this.rotating();
    if (!scope || target === null) {
      return;
    }
    this.rotateSubmitting.set(true);
    this.rotateError.set(null);
    try {
      if (target.kind === 'installation') {
        await this.api.rotateInstallationSecret(scope, target.id, submission);
        await this.reloadInstallations(scope);
      } else {
        await this.api.rotateMerchantBindingSecret(
          scope,
          target.id,
          target.expectedVersion,
          submission,
        );
        await this.reloadMerchantBindings(scope);
      }
      this.rotating.set(null);
    } catch (failure) {
      this.rotateError.set(this.describeError(failure));
    } finally {
      this.rotateSubmitting.set(false);
    }
  }

  protected async archiveBinding(binding: MerchantBindingView): Promise<void> {
    const scope = this.location.scope();
    if (!scope) {
      return;
    }
    // A native confirm() rather than a shared dialog component: this app has
    // no confirm primitive yet, and archiving a DRAFT or SUSPENDED binding is
    // already reversible in every way that matters — the row survives, per
    // MerchantBindingController's own doc comment — so a lightweight
    // confirmation is proportionate rather than a placeholder to apologise for.
    if (!confirm(this.i18n.t('settings.integrations.archive.confirm'))) {
      return;
    }
    try {
      await this.api.archiveMerchantBinding(scope, binding.id, binding.version);
      await this.reloadMerchantBindings(scope);
    } catch (failure) {
      this.loadError.set(this.describeError(failure));
    }
  }

  private async load(): Promise<void> {
    this.loading.set(true);
    this.loadError.set(null);
    await this.location.ensureLoaded();
    const scope = this.location.scope();
    if (!scope) {
      this.denied.set(this.location.denied());
      this.loading.set(false);
      return;
    }
    try {
      const [installations, merchantBindings, connectFields] = await Promise.all([
        this.api.listInstallations(scope),
        this.api.listMerchantBindings(scope),
        this.api.listConnectFields(scope),
      ]);
      this.installations.set(installations);
      this.merchantBindings.set(merchantBindings);
      this.connectFields.set(connectFields);
    } catch (failure) {
      if (failure instanceof ApiError && failure.status === 403) {
        this.denied.set(true);
      } else {
        this.loadError.set(this.describeError(failure, 'settings.integrations.error.loadFailed'));
      }
    } finally {
      this.loading.set(false);
    }
  }

  private async reloadInstallations(
    scope: NonNullable<ReturnType<CurrentLocation['scope']>>,
  ): Promise<void> {
    this.installations.set(await this.api.listInstallations(scope));
  }

  private async reloadMerchantBindings(
    scope: NonNullable<ReturnType<CurrentLocation['scope']>>,
  ): Promise<void> {
    this.merchantBindings.set(await this.api.listMerchantBindings(scope));
  }

  private describeError(
    failure: unknown,
    fallback: MessageKey = 'error.unknown.noReference',
  ): string {
    if (failure instanceof ApiError) {
      return describeApiError(failure, (key, values) => this.i18n.t(key, values));
    }
    return this.i18n.t(fallback);
  }
}
