import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';

import { ApiError } from '../../core/api/problem';
import { I18nService } from '../../core/i18n/i18n.service';
import { MessageKey } from '../../core/i18n/messages.en';
import { ConnectProviderPanel, ConnectSubmission } from './connect-provider-panel';
import {
  InstallationView,
  IntegrationsApi,
  MerchantBindingView,
  ProviderConnectDeclaration,
} from './integrations-api';
import { RegisterBindingSubmission, RegisterMerchantBindingPanel } from './register-merchant-binding-panel';
import { RotateSecretDialog, RotateSecretSubmission } from './rotate-secret-dialog';

/** The one provider type the platform can verify a rotated credential against (ADR 0065, wave 13). */
const VERIFIABLE_PROVIDER_TYPE = 'TELEGRAM_BOT_API';

const STATUS_KEYS: Readonly<Record<string, MessageKey>> = {
  DRAFT: 'integrations.status.DRAFT',
  ACTIVE: 'integrations.status.ACTIVE',
  SUSPENDED: 'integrations.status.SUSPENDED',
  RETIRED: 'integrations.status.RETIRED',
  UNVERIFIED: 'integrations.status.UNVERIFIED',
  SUCCEEDED: 'integrations.status.SUCCEEDED',
  FAILED: 'integrations.status.FAILED',
};

type RotationTarget =
  | { readonly kind: 'installation'; readonly id: string; readonly verifiable: boolean }
  | { readonly kind: 'binding'; readonly id: string; readonly expectedVersion: number };

/**
 * "Integrations" (ADR 0065): a tenant admin connects Click, Payme and
 * Telegram, rotates their credentials through the write-only door, and
 * archives a merchant binding it no longer needs -- with no infrastructure
 * access and without ever seeing a credential value come back from the API.
 *
 * Two lists (installations, merchant bindings) assembled from three server
 * surfaces -- see {@link IntegrationsApi}'s own doc comment for why. Every
 * mutation here composes a door write with the existing installation or
 * merchant-binding call that follows it, the "two-step with the reference"
 * shape ADR 0065 offers as an alternative to one transactional endpoint: nothing
 * on the server had to change shape to gain a tenant-facing screen, only grow
 * a door in front of it.
 */
@Component({
  selector: 'app-integrations',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ConnectProviderPanel, RegisterMerchantBindingPanel, RotateSecretDialog],
  template: `
    <h1 class="q-title">{{ i18n.t('integrations.title') }}</h1>
    <p class="q-body-sm lead">{{ i18n.t('integrations.lead') }}</p>

    @if (loading()) {
      <p class="q-body-sm">{{ i18n.t('integrations.loading') }}</p>
    } @else if (loadError(); as message) {
      <p class="q-body-sm error" role="alert">{{ message }}</p>
    } @else {
      <section class="panel">
        <div class="panel-header">
          <h2 class="q-subhead">{{ i18n.t('integrations.installations.title') }}</h2>
          <button type="button" class="q-body primary" (click)="openConnect()">
            {{ i18n.t('integrations.connect.action') }}
          </button>
        </div>

        <table class="table">
          <thead>
            <tr>
              <th class="q-caption">{{ i18n.t('integrations.installations.column.provider') }}</th>
              <th class="q-caption">{{ i18n.t('integrations.installations.column.environment') }}</th>
              <th class="q-caption">{{ i18n.t('integrations.installations.column.status') }}</th>
              <th class="q-caption">{{ i18n.t('integrations.installations.column.credential') }}</th>
              <th class="q-caption">{{ i18n.t('integrations.installations.column.lastRotated') }}</th>
              <th class="q-caption">{{ i18n.t('integrations.installations.column.actions') }}</th>
            </tr>
          </thead>
          <tbody>
            @for (installation of installations(); track installation.id) {
              <tr>
                <td class="q-body-sm">{{ installation.category }} · {{ installation.providerType }}</td>
                <td class="q-body-sm">{{ installation.environmentCode }}</td>
                <td class="q-body-sm"><span class="status-badge">{{ statusLabel(installation.status) }}</span></td>
                <td class="q-body-sm">{{ credentialLabel(installation.secretReference) }}</td>
                <td class="q-body-sm">{{ lastRotatedLabel(installation.lastSecretRotatedAt) }}</td>
                <td class="q-body-sm">
                  <button type="button" class="q-body-sm link" (click)="openRotateInstallation(installation)">
                    {{ i18n.t('integrations.rotate.installationAction') }}
                  </button>
                </td>
              </tr>
            } @empty {
              <tr><td class="q-body-sm empty" colspan="6">{{ i18n.t('integrations.installations.empty') }}</td></tr>
            }
          </tbody>
        </table>
      </section>

      <section class="panel">
        <div class="panel-header">
          <h2 class="q-subhead">{{ i18n.t('integrations.merchantBindings.title') }}</h2>
          <button type="button" class="q-body primary" (click)="openRegisterBinding()">
            {{ i18n.t('integrations.registerBinding.action') }}
          </button>
        </div>

        <table class="table">
          <thead>
            <tr>
              <th class="q-caption">{{ i18n.t('integrations.merchantBindings.column.provider') }}</th>
              <th class="q-caption">{{ i18n.t('integrations.merchantBindings.column.account') }}</th>
              <th class="q-caption">{{ i18n.t('integrations.merchantBindings.column.status') }}</th>
              <th class="q-caption">{{ i18n.t('integrations.merchantBindings.column.credential') }}</th>
              <th class="q-caption">{{ i18n.t('integrations.merchantBindings.column.lastRotated') }}</th>
              <th class="q-caption">{{ i18n.t('integrations.merchantBindings.column.actions') }}</th>
            </tr>
          </thead>
          <tbody>
            @for (binding of merchantBindings(); track binding.id) {
              <tr>
                <td class="q-body-sm">{{ binding.providerType }}</td>
                <td class="q-body-sm">{{ binding.merchantAccountReference }}</td>
                <td class="q-body-sm"><span class="status-badge">{{ statusLabel(binding.status) }}</span></td>
                <td class="q-body-sm">{{ credentialLabel(binding.secretReference) }}</td>
                <td class="q-body-sm">{{ lastRotatedLabel(binding.lastSecretRotatedAt) }}</td>
                <td class="q-body-sm actions">
                  <button type="button" class="q-body-sm link" (click)="openRotateBinding(binding)">
                    {{ i18n.t('integrations.rotate.bindingAction') }}
                  </button>
                  @if (binding.status === 'DRAFT' || binding.status === 'SUSPENDED') {
                    <button type="button" class="q-body-sm link" (click)="archiveBinding(binding)">
                      {{ i18n.t('integrations.archive.action') }}
                    </button>
                  }
                </td>
              </tr>
            } @empty {
              <tr><td class="q-body-sm empty" colspan="6">{{ i18n.t('integrations.merchantBindings.empty') }}</td></tr>
            }
          </tbody>
        </table>
      </section>
    }

    @if (showConnectPanel()) {
      <app-connect-provider-panel
        [providers]="connectFields()"
        [submitting]="connectSubmitting()"
        [errorMessage]="connectError()"
        (connect)="onConnect($event)"
        (cancel)="showConnectPanel.set(false)"
      />
    }

    @if (showRegisterBindingPanel()) {
      <app-register-merchant-binding-panel
        [submitting]="registerSubmitting()"
        [errorMessage]="registerError()"
        (register)="onRegisterBinding($event)"
        (cancel)="showRegisterBindingPanel.set(false)"
      />
    }

    @if (rotating(); as target) {
      <app-rotate-secret-dialog
        [unverifiable]="target.kind === 'installation' ? !target.verifiable : true"
        [submitting]="rotateSubmitting()"
        [errorMessage]="rotateError()"
        (confirm)="onRotateConfirm($event)"
        (cancel)="rotating.set(null)"
      />
    }
  `,
  styles: `
    .lead {
      color: var(--q-ink-muted);
      margin-top: 4px;
      max-width: 720px;
    }

    .panel {
      margin-top: 24px;
      background: var(--q-canvas);
      border: 1px solid var(--q-hairline);
      border-radius: var(--q-radius);
    }

    .panel-header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      padding: 16px 20px;
      border-bottom: 1px solid var(--q-hairline);
    }

    .panel-header h2 {
      margin: 0;
    }

    .table {
      width: 100%;
      border-collapse: collapse;
      overflow-x: auto;
      display: block;
    }

    .table thead th {
      text-align: left;
      color: var(--q-ink-muted);
      padding: 10px 20px;
      border-bottom: 1px solid var(--q-hairline);
      white-space: nowrap;
    }

    .table tbody td {
      padding: 10px 20px;
      border-bottom: 1px solid var(--q-hairline);
      white-space: nowrap;
    }

    .table tbody tr:last-child td {
      border-bottom: none;
    }

    .empty {
      color: var(--q-ink-subtle);
      text-align: center;
    }

    .status-badge {
      display: inline-block;
      padding: 2px 8px;
      border: 1px solid var(--q-hairline);
      border-radius: var(--q-radius);
      color: var(--q-ink-muted);
      font-size: 12px;
    }

    .actions {
      display: flex;
      gap: 12px;
    }

    .link {
      background: none;
      border: none;
      color: var(--q-primary);
      cursor: pointer;
      padding: 0;
    }

    .primary {
      height: 36px;
      padding: 0 16px;
      border: none;
      border-radius: var(--q-radius);
      background: var(--q-primary);
      color: var(--q-inverse-ink);
      cursor: pointer;
    }

    .error {
      margin-top: 16px;
      color: var(--q-error-text);
      background: var(--q-error-tint);
      padding: 8px 12px;
      border-radius: var(--q-radius);
      max-width: 720px;
    }
  `,
})
export class Integrations {
  private readonly api = inject(IntegrationsApi);
  protected readonly i18n = inject(I18nService);

  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
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
      ? this.i18n.t('integrations.credential.none')
      : this.i18n.t('integrations.credential.configured');
  }

  protected lastRotatedLabel(lastSecretRotatedAt: string | null): string {
    return lastSecretRotatedAt === null
      ? this.i18n.t('integrations.lastRotated.never')
      : this.i18n.day(new Date(lastSecretRotatedAt));
  }

  protected openConnect(): void {
    this.connectError.set(null);
    this.showConnectPanel.set(true);
  }

  protected async onConnect(submission: ConnectSubmission): Promise<void> {
    this.connectSubmitting.set(true);
    this.connectError.set(null);
    try {
      const reference = await this.api.writeSecret({
        category: `PROVIDER_${submission.category}`,
        providerType: submission.providerType,
        value: submission.secretValue,
      });
      await this.api.install({
        category: submission.category,
        providerType: submission.providerType,
        environmentCode: submission.environmentCode,
        displayName: submission.displayName,
        secretReference: reference,
        externalAccountReference: submission.reference === '' ? undefined : submission.reference,
      });
      this.showConnectPanel.set(false);
      await this.reloadInstallations();
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
    this.registerSubmitting.set(true);
    this.registerError.set(null);
    try {
      const reference = await this.api.writeSecret({
        category: 'PROVIDER_PAYMENT',
        providerType: submission.providerType,
        value: submission.secretValue,
      });
      await this.api.registerMerchantBinding({
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
      await this.reloadMerchantBindings();
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
    const target = this.rotating();
    if (target === null) {
      return;
    }
    this.rotateSubmitting.set(true);
    this.rotateError.set(null);
    try {
      if (target.kind === 'installation') {
        await this.api.rotateInstallationSecret(target.id, submission);
        await this.reloadInstallations();
      } else {
        await this.api.rotateMerchantBindingSecret(target.id, target.expectedVersion, submission);
        await this.reloadMerchantBindings();
      }
      this.rotating.set(null);
    } catch (failure) {
      this.rotateError.set(this.describeError(failure));
    } finally {
      this.rotateSubmitting.set(false);
    }
  }

  protected async archiveBinding(binding: MerchantBindingView): Promise<void> {
    // A native confirm() rather than a shared dialog component: this app has
    // no confirm primitive yet (the drawer and modal above are this feature's
    // own first two), and archiving a DRAFT or SUSPENDED binding is already
    // reversible in every way that matters -- the row survives, per
    // MerchantBindingController's own doc comment -- so a lightweight
    // confirmation is proportionate rather than a placeholder to apologise for.
    if (!confirm(this.i18n.t('integrations.archive.confirm'))) {
      return;
    }
    try {
      await this.api.archiveMerchantBinding(binding.id, binding.version);
      await this.reloadMerchantBindings();
    } catch (failure) {
      this.loadError.set(this.describeError(failure));
    }
  }

  private async load(): Promise<void> {
    this.loading.set(true);
    this.loadError.set(null);
    try {
      const [installations, merchantBindings, connectFields] = await Promise.all([
        this.api.listInstallations(),
        this.api.listMerchantBindings(),
        this.api.listConnectFields(),
      ]);
      this.installations.set(installations);
      this.merchantBindings.set(merchantBindings);
      this.connectFields.set(connectFields);
    } catch (failure) {
      this.loadError.set(this.describeError(failure, 'integrations.error.loadFailed'));
    } finally {
      this.loading.set(false);
    }
  }

  private async reloadInstallations(): Promise<void> {
    this.installations.set(await this.api.listInstallations());
  }

  private async reloadMerchantBindings(): Promise<void> {
    this.merchantBindings.set(await this.api.listMerchantBindings());
  }

  private describeError(failure: unknown, fallback: MessageKey = 'error.UNKNOWN'): string {
    if (failure instanceof ApiError) {
      return this.i18n.describe(failure);
    }
    return this.i18n.t(fallback);
  }
}
