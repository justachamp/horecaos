import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  output,
  signal,
} from '@angular/core';

import { I18n } from '../../../core/i18n/i18n';
import { SecretInput } from '../../../shared/ui/secret-input/secret-input';
import { LegalEntityView } from '../fiscalization/fiscalization-api';
import { InstallationView } from './integrations-api';

/**
 * What the parent screen needs to write the merchant secret through the door
 * and then register the binding. `secretValue` is never persisted anywhere by
 * this component beyond this one emitted event.
 */
export interface RegisterBindingSubmission {
  readonly providerType: 'CLICK' | 'PAYME';
  readonly legalEntityId: string;
  readonly installationId: string;
  readonly integrationBindingId: string;
  readonly merchantAccountReference: string;
  readonly callbackPathSegment: string;
  readonly secretValue: string;
}

/**
 * One `integration.bindings` row (ADR 0026) the connect drawer's own bind
 * step created, for this panel's integration-binding picker. `label` is a
 * display string (`IntegrationsPage.describeBinding`) — provider, brand, and
 * location — never the raw id, which only ever appears as {@link id}'s value.
 */
export interface IntegrationBindingOption {
  readonly id: string;
  readonly installationId: string;
  readonly label: string;
}

/**
 * "Register a merchant binding" (ADR 0013, ADR 0026, ADR 0065): links a legal
 * entity to a Click or Payme account, through an installation the connect
 * panel already created above.
 *
 * <p><strong>Three real pickers, cascading.</strong> Wave 66 replaced the
 * three plain id fields this panel used to render (an operator copying UUIDs
 * from elsewhere in the console, unchanged from the control-plane original
 * this was ported from) with selects backed by data the parent already
 * fetched: {@link legalEntities} ({@link FiscalizationApi.listLegalEntities}),
 * {@link installations} (`IntegrationsApi.listInstallations`, already on this
 * page for the table above), and {@link bindings}. Choosing an installation
 * narrows the binding picker to bindings that installation actually has —
 * cheap enough here, but the point that matters: it makes it structurally
 * impossible to submit a binding that belongs to a different installation
 * than the one shown next to it, the exact "silently targets another record"
 * failure a free-typed id invites.
 *
 * <p><strong>The one field with no server list to draw from.</strong> The
 * platform has no endpoint that lists a tenant's existing
 * `integration.bindings` rows — only `POST .../bindings` to create one, never
 * a `GET` to read them back (`IntegrationsPage`'s own doc comment names this
 * as a backend gap, not a shortcut this wave took). So {@link bindings} only
 * ever holds what the connect drawer's bind step created since this page
 * loaded: an installation bound in an earlier session has no entry here yet,
 * and this form has nothing to fall back to for it beyond bind it again from
 * the connect flow. A free-text escape hatch was deliberately not added back
 * in for that case — it would reopen exactly the "type a UUID, silently hit
 * the wrong row" failure this wave exists to close.
 */
@Component({
  selector: 'app-register-merchant-binding-panel',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [SecretInput],
  template: `
    <div class="backdrop" (click)="cancel.emit()">
      <div
        class="drawer"
        role="dialog"
        aria-modal="true"
        [attr.aria-label]="i18n.t('settings.integrations.registerBinding.title')"
        (click)="$event.stopPropagation()"
      >
        <div class="header">
          <h2 class="q-subhead">{{ i18n.t('settings.integrations.registerBinding.title') }}</h2>
          <button type="button" class="q-body close" (click)="cancel.emit()" aria-label="Close">
            ✕
          </button>
        </div>

        <div class="body">
          <p class="q-body-sm lead">{{ i18n.t('settings.integrations.registerBinding.lead') }}</p>

          <label class="q-caption field-label" for="rmb-provider">{{
            i18n.t('settings.integrations.registerBinding.provider')
          }}</label>
          <select
            id="rmb-provider"
            class="q-body field"
            [value]="providerType()"
            (change)="onProviderTypeChange($event)"
            [disabled]="submitting()"
          >
            <option value="CLICK">CLICK</option>
            <option value="PAYME">PAYME</option>
          </select>

          <label class="q-caption field-label" for="rmb-legal-entity">{{
            i18n.t('settings.integrations.registerBinding.legalEntityId')
          }}</label>
          <select
            id="rmb-legal-entity"
            class="q-body field"
            [value]="legalEntityId()"
            (change)="legalEntityId.set(inputValue($event))"
            [disabled]="submitting()"
          >
            <option value="" disabled>
              {{ i18n.t('settings.integrations.registerBinding.legalEntity.placeholder') }}
            </option>
            @for (entity of activeLegalEntities(); track entity.id) {
              <option [value]="entity.id">{{ entity.legalName }}</option>
            }
          </select>
          @if (activeLegalEntities().length === 0) {
            <p class="q-caption hint">
              {{ i18n.t('settings.integrations.registerBinding.legalEntity.empty') }}
            </p>
          }

          <label class="q-caption field-label" for="rmb-installation">{{
            i18n.t('settings.integrations.registerBinding.installationId')
          }}</label>
          <select
            id="rmb-installation"
            class="q-body field"
            [value]="installationId()"
            (change)="onInstallationChange($event)"
            [disabled]="submitting()"
          >
            <option value="" disabled>
              {{ i18n.t('settings.integrations.registerBinding.installation.placeholder') }}
            </option>
            @for (installation of matchingInstallations(); track installation.id) {
              <option [value]="installation.id">{{ installation.displayName }}</option>
            }
          </select>
          @if (matchingInstallations().length === 0) {
            <p class="q-caption hint">
              {{ i18n.t('settings.integrations.registerBinding.installation.empty') }}
            </p>
          }

          <label class="q-caption field-label" for="rmb-binding">{{
            i18n.t('settings.integrations.registerBinding.integrationBindingId')
          }}</label>
          <select
            id="rmb-binding"
            class="q-body field"
            [value]="integrationBindingId()"
            (change)="integrationBindingId.set(inputValue($event))"
            [disabled]="submitting() || installationId() === ''"
          >
            <option value="" disabled>
              {{ i18n.t('settings.integrations.registerBinding.binding.placeholder') }}
            </option>
            @for (binding of availableBindings(); track binding.id) {
              <option [value]="binding.id">{{ binding.label }}</option>
            }
          </select>
          @if (installationId() !== '' && availableBindings().length === 0) {
            <p class="q-caption hint">
              {{ i18n.t('settings.integrations.registerBinding.binding.empty') }}
            </p>
          }

          <label class="q-caption field-label" for="rmb-account">{{
            i18n.t('settings.integrations.registerBinding.merchantAccountReference')
          }}</label>
          <input
            id="rmb-account"
            class="q-body field"
            type="text"
            [value]="merchantAccountReference()"
            (input)="merchantAccountReference.set(inputValue($event))"
            [disabled]="submitting()"
          />

          <label class="q-caption field-label" for="rmb-callback">{{
            i18n.t('settings.integrations.registerBinding.callbackPathSegment')
          }}</label>
          <input
            id="rmb-callback"
            class="q-body field"
            type="text"
            [value]="callbackPathSegment()"
            (input)="callbackPathSegment.set(inputValue($event))"
            [disabled]="submitting()"
          />

          <q-secret-input
            fieldId="rmb-value"
            [label]="i18n.t('settings.integrations.registerBinding.value')"
            [value]="secretValue()"
            (valueChange)="secretValue.set($event)"
            [disabled]="submitting()"
          />

          @if (errorMessage(); as message) {
            <p class="q-body-sm error" role="alert">{{ message }}</p>
          }
        </div>

        <div class="actions">
          <button
            type="button"
            class="q-body secondary"
            (click)="cancel.emit()"
            [disabled]="submitting()"
          >
            {{ i18n.t('settings.integrations.registerBinding.cancel') }}
          </button>
          <button type="button" class="q-body primary" (click)="submit()" [disabled]="!canSubmit()">
            {{
              submitting()
                ? i18n.t('settings.integrations.registerBinding.submitting')
                : i18n.t('settings.integrations.registerBinding.submit')
            }}
          </button>
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
      width: 420px;
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
      font-size: 16px;
    }

    .body {
      padding: 24px;
      overflow-y: auto;
      flex: 1;
    }

    .lead {
      color: var(--q-ink-muted);
      margin: 0 0 8px;
    }

    .hint {
      color: var(--q-ink-subtle);
      margin: 4px 0 0;
    }

    .field-label {
      display: block;
      color: var(--q-ink-muted);
      margin-top: 16px;
    }

    .field {
      display: block;
      width: 100%;
      margin-top: 4px;
      height: 40px;
      padding: 0 12px;
      border: 1px solid var(--q-surface-2);
      border-radius: var(--q-radius);
      background: var(--q-canvas);
      color: var(--q-ink);
      box-sizing: border-box;
    }

    .field:disabled {
      background: var(--q-surface-1);
    }

    .error {
      margin: 16px 0 0;
      color: var(--q-error-text);
      background: var(--q-error-tint);
      padding: 8px 12px;
      border-radius: var(--q-radius);
    }

    .actions {
      padding: 16px 24px;
      border-top: 1px solid var(--q-hairline);
      display: flex;
      justify-content: flex-end;
      gap: 8px;
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

    .primary:disabled {
      background: var(--q-surface-2);
      color: var(--q-ink-subtle);
      cursor: default;
    }

    .secondary {
      height: 36px;
      padding: 0 16px;
      border: 1px solid var(--q-hairline);
      border-radius: var(--q-radius);
      background: var(--q-canvas);
      color: var(--q-ink);
      cursor: pointer;
    }
  `,
})
export class RegisterMerchantBindingPanel {
  protected readonly i18n = inject(I18n);

  readonly legalEntities = input<readonly LegalEntityView[]>([]);
  readonly installations = input<readonly InstallationView[]>([]);
  readonly bindings = input<readonly IntegrationBindingOption[]>([]);
  readonly submitting = input(false);
  readonly errorMessage = input<string | null>(null);

  readonly register = output<RegisterBindingSubmission>();
  readonly cancel = output<void>();

  protected readonly providerType = signal<'CLICK' | 'PAYME'>('CLICK');
  protected readonly legalEntityId = signal('');
  protected readonly installationId = signal('');
  protected readonly integrationBindingId = signal('');
  protected readonly merchantAccountReference = signal('');
  protected readonly callbackPathSegment = signal('');
  protected readonly secretValue = signal('');

  /** A merchant binding must reference an active legal entity — `MerchantBindingService` enforces it. */
  protected readonly activeLegalEntities = computed(() =>
    this.legalEntities().filter((entity) => entity.status === 'ACTIVE'),
  );

  protected readonly matchingInstallations = computed(() =>
    this.installations().filter(
      (installation) => installation.providerType === this.providerType(),
    ),
  );

  /** Narrowed to the chosen installation, so a binding for a different one is never selectable. */
  protected readonly availableBindings = computed(() => {
    const installationId = this.installationId();
    if (installationId === '') {
      return [];
    }
    return this.bindings().filter((binding) => binding.installationId === installationId);
  });

  protected readonly canSubmit = () =>
    !this.submitting() &&
    this.legalEntityId() !== '' &&
    this.installationId() !== '' &&
    this.integrationBindingId() !== '' &&
    this.merchantAccountReference().trim().length > 0 &&
    this.callbackPathSegment().trim().length >= 8 &&
    this.secretValue().length > 0;

  protected inputValue(event: Event): string {
    return (event.target as HTMLInputElement).value;
  }

  protected onProviderTypeChange(event: Event): void {
    this.providerType.set(this.inputValue(event) === 'PAYME' ? 'PAYME' : 'CLICK');
    // A CLICK installation is never a valid choice once PAYME is picked (and
    // vice versa), so any installation — and, downstream, any binding —
    // already chosen under the old provider must not silently ride along.
    this.installationId.set('');
    this.integrationBindingId.set('');
  }

  protected onInstallationChange(event: Event): void {
    this.installationId.set(this.inputValue(event));
    // Same reasoning: a binding belongs to exactly one installation.
    this.integrationBindingId.set('');
  }

  protected submit(): void {
    if (!this.canSubmit()) {
      return;
    }
    this.register.emit({
      providerType: this.providerType(),
      legalEntityId: this.legalEntityId(),
      installationId: this.installationId(),
      integrationBindingId: this.integrationBindingId(),
      merchantAccountReference: this.merchantAccountReference().trim(),
      callbackPathSegment: this.callbackPathSegment().trim(),
      secretValue: this.secretValue(),
    });
  }
}
