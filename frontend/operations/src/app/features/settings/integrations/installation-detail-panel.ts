import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
  untracked,
} from '@angular/core';

import { CurrentLocation } from '../../../core/auth/current-location';
import { ApiError } from '../../../core/api/problem-details';
import { I18n } from '../../../core/i18n/i18n';
import { describeApiError } from '../../orders/order-errors';
import { StatusPill } from '../../../shared/ui/status-pill';
import { SecretInput } from '../../../shared/ui/secret-input/secret-input';
import {
  BindingView,
  CloposSettingsView,
  InstallationView,
  IntegrationsApi,
  IssuedPartnerApiClient,
  PartnerApiClientView,
  ReconciliationView,
  RotatedPartnerApiClient,
} from './integrations-api';

/**
 * The one screen for the five `10.8a` endpoints that had a real backend and
 * no caller (ADR 0106): an installation's own bindings (list, activate,
 * suspend), a fresh capability-reconciliation preflight, and — for a `clopos`
 * installation only — the order-acceptance settings toggle. `MARKETPLACE`
 * installations additionally get their own partner API client section
 * (`10.8d`), since a partner credential is issued *against* an installation.
 *
 * <p>A side drawer rather than the installations table growing five more
 * columns: every one of these is an occasional, deliberate action ("bring
 * this binding live", "check the connection again"), not a glance-and-move-on
 * fact the list view needs to carry.
 *
 * <p>Self-contained rather than the "dumb panel, page does the async work"
 * shape this feature's other panels use: nothing here interacts with the
 * page's own installations/merchant-bindings state except through {@link
 * changed} (reload the table — a reconciliation can update
 * `lastConnectionStatus`/`secretLastUsedAt`, which the table itself shows)
 * and {@link rotateCredential} (open the page's existing `RotateSecretDialog`,
 * which several installations already share). Every reason this panel needs
 * is collected with a native `prompt()`, matching `IntegrationsPage.archiveBinding`'s
 * own precedent rather than inventing a fourth bespoke form for a one-line
 * audit note.
 */
@Component({
  selector: 'app-installation-detail-panel',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [StatusPill, SecretInput],
  template: `
    <div class="backdrop" (click)="dismiss.emit()">
      <div
        class="drawer"
        role="dialog"
        aria-modal="true"
        [attr.aria-label]="i18n.t('settings.integrations.detail.title')"
        (click)="$event.stopPropagation()"
      >
        <div class="header">
          <h2 class="q-subhead">
            {{ installation().category }} · {{ installation().providerType }}
          </h2>
          <button type="button" class="q-body close" (click)="dismiss.emit()" aria-label="Close">
            ✕
          </button>
        </div>

        <div class="body">
          <q-secret-input
            fieldId="detail-credential"
            mode="configured"
            [label]="i18n.t('settings.integrations.detail.credential')"
            [configured]="
              installation().secretReference !== null && installation().secretReference !== ''
            "
            [reference]="installation().secretReference"
            [lastRotatedLabel]="lastRotatedLabel()"
            [lastUsedLabel]="lastUsedLabel()"
            (rotate)="rotateCredential.emit()"
          />

          <section class="block">
            <div class="block-header">
              <h3 class="q-body-sm strong">
                {{ i18n.t('settings.integrations.detail.reconcile.title') }}
              </h3>
              <button
                type="button"
                class="q-body-sm secondary"
                (click)="onReconcile()"
                [disabled]="reconciling()"
              >
                {{
                  reconciling()
                    ? i18n.t('settings.integrations.detail.reconcile.running')
                    : i18n.t('settings.integrations.detail.reconcile.action')
                }}
              </button>
            </div>
            @if (reconcileError(); as message) {
              <p class="q-body-sm error" role="alert">{{ message }}</p>
            } @else if (reconciliation(); as result) {
              <p class="q-body-sm">
                {{ i18n.t('settings.integrations.detail.reconcile.status') }}:
                <strong>{{ result.connectionStatus }}</strong>
                · {{ i18n.t('settings.integrations.detail.reconcile.adapter') }}:
                {{ result.adapterVersion }}
              </p>
              @if (capabilityEntries(result).length > 0) {
                <ul class="capability-list">
                  @for (entry of capabilityEntries(result); track entry[0]) {
                    <li class="q-caption">{{ entry[0] }}: {{ entry[1] }}</li>
                  }
                </ul>
              }
            }
          </section>

          <section class="block">
            <h3 class="q-body-sm strong">
              {{ i18n.t('settings.integrations.detail.bindings.title') }}
            </h3>
            @if (bindingActionError(); as message) {
              <p class="q-body-sm error" role="alert">{{ message }}</p>
            }
            @if (bindingsLoading()) {
              <p class="q-body-sm">{{ i18n.t('settings.integrations.detail.bindings.loading') }}</p>
            } @else {
              <table class="table">
                <thead>
                  <tr>
                    <th class="q-caption">
                      {{ i18n.t('settings.integrations.detail.bindings.column.scope') }}
                    </th>
                    <th class="q-caption">
                      {{ i18n.t('settings.integrations.detail.bindings.column.status') }}
                    </th>
                    <th class="q-caption">
                      {{ i18n.t('settings.integrations.detail.bindings.column.actions') }}
                    </th>
                  </tr>
                </thead>
                <tbody>
                  @for (binding of bindings(); track binding.id) {
                    <tr>
                      <td class="q-body-sm">{{ scopeLabel(binding) }}</td>
                      <td class="q-body-sm">
                        <q-status-pill
                          [label]="binding.status"
                          [tone]="statusTone(binding.status)"
                        />
                      </td>
                      <td class="q-body-sm actions">
                        @if (binding.status === 'SUSPENDED' || binding.status === 'UNVERIFIED') {
                          <button
                            type="button"
                            class="q-body-sm link"
                            [disabled]="bindingActionPending() === binding.id"
                            (click)="promptActivate(binding.id)"
                          >
                            {{ i18n.t('settings.integrations.detail.bindings.activate') }}
                          </button>
                        }
                        @if (binding.status === 'ACTIVE') {
                          <button
                            type="button"
                            class="q-body-sm link"
                            [disabled]="bindingActionPending() === binding.id"
                            (click)="promptSuspend(binding.id)"
                          >
                            {{ i18n.t('settings.integrations.detail.bindings.suspend') }}
                          </button>
                        }
                      </td>
                    </tr>
                  } @empty {
                    <tr>
                      <td class="q-body-sm empty" colspan="3">
                        {{ i18n.t('settings.integrations.detail.bindings.empty') }}
                      </td>
                    </tr>
                  }
                </tbody>
              </table>
            }
          </section>

          @if (settingsApplicable()) {
            <section class="block">
              <h3 class="q-body-sm strong">
                {{ i18n.t('settings.integrations.detail.cloposSettings.title') }}
              </h3>
              @if (settingsError(); as message) {
                <p class="q-body-sm error" role="alert">{{ message }}</p>
              } @else if (settingsLoading()) {
                <p class="q-body-sm">
                  {{ i18n.t('settings.integrations.detail.bindings.loading') }}
                </p>
              } @else if (settings(); as current) {
                <label class="q-body-sm checkbox-row">
                  <input
                    type="checkbox"
                    [checked]="current.requireClerkApproval"
                    [disabled]="settingsSubmitting()"
                    (change)="onToggleSettings(!current.requireClerkApproval)"
                  />
                  {{ i18n.t('settings.integrations.detail.cloposSettings.requireClerkApproval') }}
                </label>
                <p class="q-caption hint">
                  {{ i18n.t('settings.integrations.detail.cloposSettings.hint') }}
                </p>
              }
            </section>
          }

          @if (marketplaceApplicable()) {
            <section class="block">
              <div class="block-header">
                <h3 class="q-body-sm strong">
                  {{ i18n.t('settings.integrations.detail.partnerClients.title') }}
                </h3>
                <button
                  type="button"
                  class="q-body-sm secondary"
                  [disabled]="issuingPartnerClient()"
                  (click)="promptIssue()"
                >
                  {{ i18n.t('settings.integrations.detail.partnerClients.issue') }}
                </button>
              </div>
              @if (partnerClientActionError(); as message) {
                <p class="q-body-sm error" role="alert">{{ message }}</p>
              }
              @if (issuedSecret(); as issued) {
                <p class="q-body-sm issued-secret" role="alert">
                  {{ i18n.t('settings.integrations.detail.partnerClients.issuedOnce') }}
                  <code class="q-mono">{{ issued.secretValue }}</code>
                  <button type="button" class="q-body-sm link" (click)="issuedSecret.set(null)">
                    {{ i18n.t('settings.integrations.detail.partnerClients.issuedDismiss') }}
                  </button>
                </p>
              }
              @if (partnerClientsLoading()) {
                <p class="q-body-sm">
                  {{ i18n.t('settings.integrations.detail.bindings.loading') }}
                </p>
              } @else {
                <table class="table">
                  <thead>
                    <tr>
                      <th class="q-caption">
                        {{ i18n.t('settings.integrations.detail.partnerClients.column.clientId') }}
                      </th>
                      <th class="q-caption">
                        {{ i18n.t('settings.integrations.detail.bindings.column.status') }}
                      </th>
                      <th class="q-caption">
                        {{ i18n.t('settings.integrations.detail.bindings.column.actions') }}
                      </th>
                    </tr>
                  </thead>
                  <tbody>
                    @for (client of partnerClients(); track client.id) {
                      <tr>
                        <td class="q-body-sm">{{ client.clientId }}</td>
                        <td class="q-body-sm">
                          <q-status-pill
                            [label]="client.status"
                            [tone]="statusTone(client.status)"
                          />
                        </td>
                        <td class="q-body-sm actions">
                          @if (client.status !== 'RETIRED') {
                            <button
                              type="button"
                              class="q-body-sm link"
                              (click)="promptRotate(client.id, client.version)"
                            >
                              {{ i18n.t('settings.integrations.rotate.installationAction') }}
                            </button>
                            <button
                              type="button"
                              class="q-body-sm link"
                              (click)="promptRevoke(client.id, client.version)"
                            >
                              {{ i18n.t('settings.integrations.detail.partnerClients.revoke') }}
                            </button>
                          }
                        </td>
                      </tr>
                    } @empty {
                      <tr>
                        <td class="q-body-sm empty" colspan="3">
                          {{ i18n.t('settings.integrations.detail.partnerClients.empty') }}
                        </td>
                      </tr>
                    }
                  </tbody>
                </table>
              }
            </section>
          }
        </div>
      </div>
    </div>
  `,
  styles: `
    .backdrop {
      position: fixed;
      inset: 0;
      background: rgba(22, 22, 22, 0.5);
      z-index: 50;
      display: flex;
      justify-content: flex-end;
    }

    .drawer {
      width: 480px;
      max-width: calc(100vw - 32px);
      height: 100%;
      background: var(--q-canvas);
      border-left: 1px solid var(--q-hairline);
      display: flex;
      flex-direction: column;
    }

    .header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      padding: 20px 24px;
      border-bottom: 1px solid var(--q-hairline);
    }

    .header h2 {
      margin: 0;
    }

    .close {
      background: none;
      border: none;
      color: var(--q-ink-muted);
      cursor: pointer;
      font-size: var(--q-type-body);
    }

    .body {
      padding: 24px;
      overflow-y: auto;
      flex: 1;
      display: flex;
      flex-direction: column;
      gap: 24px;
    }

    .block-header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 8px;
    }

    .strong {
      font-weight: 600;
      margin: 0 0 8px;
    }

    .table {
      width: 100%;
      border-collapse: collapse;
      margin-top: 8px;
    }

    .table th,
    .table td {
      text-align: start;
      padding: 6px 4px;
      border-bottom: 1px solid var(--q-hairline);
    }

    .actions {
      display: flex;
      gap: 12px;
    }

    .link {
      border: none;
      background: transparent;
      color: var(--q-primary);
      cursor: pointer;
      padding: 0;
    }

    .link:disabled {
      color: var(--q-ink-subtle);
      cursor: default;
    }

    .secondary {
      height: 32px;
      padding: 0 12px;
      border: 1px solid var(--q-hairline);
      border-radius: var(--q-radius);
      background: var(--q-canvas);
      color: var(--q-ink);
      cursor: pointer;
    }

    .secondary:disabled {
      color: var(--q-ink-subtle);
      cursor: default;
    }

    .checkbox-row {
      display: flex;
      align-items: center;
      gap: 8px;
    }

    .hint {
      color: var(--q-ink-subtle);
      margin: 4px 0 0;
    }

    .empty {
      color: var(--q-ink-subtle);
    }

    .capability-list {
      margin: 8px 0 0;
      padding-left: 16px;
      color: var(--q-ink-muted);
    }

    .issued-secret {
      display: flex;
      align-items: center;
      gap: 8px;
      flex-wrap: wrap;
      background: var(--q-warning-tint);
      color: var(--q-warning-text);
      padding: 8px 12px;
      border-radius: var(--q-radius);
    }

    .error {
      color: var(--q-error-text);
      background: var(--q-error-tint);
      padding: 8px 12px;
      border-radius: var(--q-radius);
    }
  `,
})
export class InstallationDetailPanel {
  protected readonly i18n = inject(I18n);
  private readonly api = inject(IntegrationsApi);
  private readonly location = inject(CurrentLocation);

  readonly installation = input.required<InstallationView>();
  /** Display names for a binding's `brandId`/`locationId`, keyed by id — falls back to the raw id. */
  readonly scopeNames = input<ReadonlyMap<string, string>>(new Map());

  readonly dismiss = output<void>();
  /** Bubbles to `IntegrationsPage`'s own `RotateSecretDialog`, shared with the installations table. */
  readonly rotateCredential = output<void>();
  /** The installations table can go stale under this drawer (reconciliation changes what it shows). */
  readonly changed = output<void>();

  protected readonly reconciling = signal(false);
  protected readonly reconciliation = signal<ReconciliationView | null>(null);
  protected readonly reconcileError = signal<string | null>(null);

  protected readonly bindings = signal<readonly BindingView[]>([]);
  protected readonly bindingsLoading = signal(true);
  protected readonly bindingActionPending = signal<string | null>(null);
  protected readonly bindingActionError = signal<string | null>(null);

  protected readonly settingsApplicable = computed(
    () => this.installation().providerType === 'clopos',
  );
  protected readonly settingsLoading = signal(false);
  protected readonly settings = signal<CloposSettingsView | null>(null);
  protected readonly settingsSubmitting = signal(false);
  protected readonly settingsError = signal<string | null>(null);

  protected readonly marketplaceApplicable = computed(
    () => this.installation().category === 'MARKETPLACE',
  );
  protected readonly partnerClients = signal<readonly PartnerApiClientView[]>([]);
  protected readonly partnerClientsLoading = signal(false);
  protected readonly partnerClientActionError = signal<string | null>(null);
  protected readonly issuingPartnerClient = signal(false);
  protected readonly issuedSecret = signal<IssuedPartnerApiClient | RotatedPartnerApiClient | null>(
    null,
  );

  constructor() {
    // A required signal input has no value yet at construction time —
    // Angular applies a template's input bindings only once every directive
    // on the node has already been constructed, so reading `installation()`
    // directly in the constructor body throws NG0950. `effect()`, keyed on
    // the input, is the same fix `location-detail-pane.ts` and
    // `customer-detail-pane.ts` already use for the identical shape; the
    // rest of the body runs `untracked` so this fires once per installation
    // bound in, not on every unrelated signal the load methods happen to read.
    effect(() => {
      const installation = this.installation();
      untracked(() => {
        void this.loadBindings();
        if (installation.providerType === 'clopos') {
          void this.loadSettings();
        }
        if (installation.category === 'MARKETPLACE') {
          void this.loadPartnerClients();
        }
      });
    });
  }

  protected lastRotatedLabel(): string {
    const value = this.installation().lastSecretRotatedAt;
    return value === null
      ? this.i18n.t('settings.integrations.lastRotated.never')
      : new Date(value).toLocaleDateString(this.i18n.locale());
  }

  protected lastUsedLabel(): string {
    const value = this.installation().secretLastUsedAt;
    return value === null
      ? this.i18n.t('secretInput.neverUsed')
      : new Date(value).toLocaleString(this.i18n.locale());
  }

  protected capabilityEntries(result: ReconciliationView): readonly (readonly [string, string])[] {
    return Object.entries(result.capabilities);
  }

  protected scopeLabel(binding: BindingView): string {
    if (binding.locationId !== null) {
      return this.scopeNames().get(binding.locationId) ?? binding.locationId;
    }
    if (binding.brandId !== null) {
      return this.scopeNames().get(binding.brandId) ?? binding.brandId;
    }
    return this.i18n.t('settings.integrations.detail.bindings.wholeTenant');
  }

  protected statusTone(status: string): 'success' | 'warning' | 'danger' | 'none' {
    switch (status) {
      case 'ACTIVE':
        return 'success';
      case 'SUSPENDED':
      case 'RETIRED':
        return 'danger';
      case 'UNVERIFIED':
      case 'DRAFT':
      case 'PENDING':
        return 'warning';
      default:
        return 'none';
    }
  }

  protected async onReconcile(): Promise<void> {
    const scope = this.location.scope();
    if (!scope) {
      return;
    }
    this.reconciling.set(true);
    this.reconcileError.set(null);
    try {
      const result = await this.api.reconcileCapabilities(scope, this.installation().id);
      this.reconciliation.set(result);
      this.changed.emit();
    } catch (failure) {
      this.reconcileError.set(this.describeError(failure));
    } finally {
      this.reconciling.set(false);
    }
  }

  private async loadBindings(): Promise<void> {
    const scope = this.location.scope();
    if (!scope) {
      this.bindingsLoading.set(false);
      return;
    }
    this.bindingsLoading.set(true);
    this.bindingActionError.set(null);
    try {
      this.bindings.set(await this.api.listBindings(scope, this.installation().id));
    } catch (failure) {
      this.bindingActionError.set(this.describeError(failure));
    } finally {
      this.bindingsLoading.set(false);
    }
  }

  // Reasons are collected with a native prompt(), matching
  // `IntegrationsPage.archiveBinding`'s own precedent — this feature has no
  // shared confirm/reason primitive yet, and a fourth bespoke form for a
  // one-line audit note would be disproportionate.
  protected promptActivate(bindingId: string): void {
    const reason = window.prompt(
      this.i18n.t('settings.integrations.detail.bindings.activate.reasonPrompt'),
    );
    if (reason === null || reason.trim().length === 0) {
      return;
    }
    void this.runBindingAction(bindingId, (scope, installationId) =>
      this.api.activateBinding(scope, installationId, bindingId, reason.trim()),
    );
  }

  protected promptSuspend(bindingId: string): void {
    const reason = window.prompt(
      this.i18n.t('settings.integrations.detail.bindings.suspend.reasonPrompt'),
    );
    if (reason === null || reason.trim().length === 0) {
      return;
    }
    void this.runBindingAction(bindingId, (scope, installationId) =>
      this.api.suspendBinding(scope, installationId, bindingId, reason.trim()),
    );
  }

  private async runBindingAction(
    bindingId: string,
    call: (
      scope: NonNullable<ReturnType<CurrentLocation['scope']>>,
      installationId: string,
    ) => Promise<unknown>,
  ): Promise<void> {
    const scope = this.location.scope();
    if (!scope) {
      return;
    }
    this.bindingActionPending.set(bindingId);
    this.bindingActionError.set(null);
    try {
      await call(scope, this.installation().id);
      await this.loadBindings();
    } catch (failure) {
      this.bindingActionError.set(this.describeError(failure));
    } finally {
      this.bindingActionPending.set(null);
    }
  }

  private async loadSettings(): Promise<void> {
    const scope = this.location.scope();
    if (!scope) {
      return;
    }
    this.settingsLoading.set(true);
    this.settingsError.set(null);
    try {
      this.settings.set(await this.api.getInstallationSettings(scope, this.installation().id));
    } catch (failure) {
      this.settingsError.set(this.describeError(failure));
    } finally {
      this.settingsLoading.set(false);
    }
  }

  protected async onToggleSettings(requireClerkApproval: boolean): Promise<void> {
    const scope = this.location.scope();
    if (!scope) {
      return;
    }
    this.settingsSubmitting.set(true);
    this.settingsError.set(null);
    try {
      this.settings.set(
        await this.api.updateInstallationSettings(
          scope,
          this.installation().id,
          requireClerkApproval,
        ),
      );
    } catch (failure) {
      this.settingsError.set(this.describeError(failure));
    } finally {
      this.settingsSubmitting.set(false);
    }
  }

  private async loadPartnerClients(): Promise<void> {
    const scope = this.location.scope();
    if (!scope) {
      return;
    }
    this.partnerClientsLoading.set(true);
    this.partnerClientActionError.set(null);
    try {
      this.partnerClients.set(await this.api.listPartnerApiClients(scope, this.installation().id));
    } catch (failure) {
      this.partnerClientActionError.set(this.describeError(failure));
    } finally {
      this.partnerClientsLoading.set(false);
    }
  }

  protected promptIssue(): void {
    const displayLabel = window.prompt(
      this.i18n.t('settings.integrations.detail.partnerClients.issue.labelPrompt'),
    );
    if (displayLabel === null || displayLabel.trim().length === 0) {
      return;
    }
    const reason = window.prompt(
      this.i18n.t('settings.integrations.detail.partnerClients.issue.reasonPrompt'),
    );
    if (reason === null || reason.trim().length === 0) {
      return;
    }
    void this.issue(displayLabel.trim(), reason.trim());
  }

  private async issue(displayLabel: string, reason: string): Promise<void> {
    const scope = this.location.scope();
    if (!scope) {
      return;
    }
    this.issuingPartnerClient.set(true);
    this.partnerClientActionError.set(null);
    try {
      const issued = await this.api.issuePartnerApiClient(
        scope,
        this.installation().id,
        displayLabel,
        reason,
      );
      this.issuedSecret.set(issued);
      await this.loadPartnerClients();
    } catch (failure) {
      this.partnerClientActionError.set(this.describeError(failure));
    } finally {
      this.issuingPartnerClient.set(false);
    }
  }

  protected promptRotate(clientId: string, expectedVersion: number): void {
    const reason = window.prompt(this.i18n.t('settings.integrations.rotate.reason'));
    if (reason === null || reason.trim().length === 0) {
      return;
    }
    void this.rotate(clientId, expectedVersion, reason.trim());
  }

  private async rotate(clientId: string, expectedVersion: number, reason: string): Promise<void> {
    const scope = this.location.scope();
    if (!scope) {
      return;
    }
    this.partnerClientActionError.set(null);
    try {
      const rotated = await this.api.rotatePartnerApiClient(
        scope,
        this.installation().id,
        clientId,
        expectedVersion,
        reason,
      );
      this.issuedSecret.set(rotated);
      await this.loadPartnerClients();
    } catch (failure) {
      this.partnerClientActionError.set(this.describeError(failure));
    }
  }

  protected promptRevoke(clientId: string, expectedVersion: number): void {
    const reason = window.prompt(
      this.i18n.t('settings.integrations.detail.partnerClients.revoke.reasonPrompt'),
    );
    if (reason === null || reason.trim().length === 0) {
      return;
    }
    void this.revoke(clientId, expectedVersion, reason.trim());
  }

  private async revoke(clientId: string, expectedVersion: number, reason: string): Promise<void> {
    const scope = this.location.scope();
    if (!scope) {
      return;
    }
    this.partnerClientActionError.set(null);
    try {
      await this.api.revokePartnerApiClient(
        scope,
        this.installation().id,
        clientId,
        expectedVersion,
        reason,
      );
      await this.loadPartnerClients();
    } catch (failure) {
      this.partnerClientActionError.set(this.describeError(failure));
    }
  }

  private describeError(failure: unknown): string {
    if (failure instanceof ApiError) {
      return describeApiError(failure, (key, values) => this.i18n.t(key, values));
    }
    return this.i18n.t('error.unknown.noReference');
  }
}
