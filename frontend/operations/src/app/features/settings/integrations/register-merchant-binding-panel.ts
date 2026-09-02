import { ChangeDetectionStrategy, Component, inject, input, output, signal } from '@angular/core';

import { I18n } from '../../../core/i18n/i18n';

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
 * "Register a merchant binding" (ADR 0013, ADR 0026, ADR 0065): links a legal
 * entity to a Click or Payme account, through an installation the connect
 * panel already created above.
 *
 * <strong>A known, deliberate limitation of this iteration:</strong> the
 * legal entity, installation and integration-binding pickers are plain id
 * fields rather than searchable selects, unchanged from the control-plane
 * original this was ported from — an operator copies the three ids from
 * elsewhere in the console for now.
 */
@Component({
  selector: 'app-register-merchant-binding-panel',
  changeDetection: ChangeDetectionStrategy.OnPush,
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
            (change)="providerType.set(inputValue($event) === 'PAYME' ? 'PAYME' : 'CLICK')"
            [disabled]="submitting()"
          >
            <option value="CLICK">CLICK</option>
            <option value="PAYME">PAYME</option>
          </select>

          <label class="q-caption field-label" for="rmb-legal-entity">{{
            i18n.t('settings.integrations.registerBinding.legalEntityId')
          }}</label>
          <input
            id="rmb-legal-entity"
            class="q-body field"
            type="text"
            [value]="legalEntityId()"
            (input)="legalEntityId.set(inputValue($event))"
            [disabled]="submitting()"
          />

          <label class="q-caption field-label" for="rmb-installation">{{
            i18n.t('settings.integrations.registerBinding.installationId')
          }}</label>
          <input
            id="rmb-installation"
            class="q-body field"
            type="text"
            [value]="installationId()"
            (input)="installationId.set(inputValue($event))"
            [disabled]="submitting()"
          />

          <label class="q-caption field-label" for="rmb-binding">{{
            i18n.t('settings.integrations.registerBinding.integrationBindingId')
          }}</label>
          <input
            id="rmb-binding"
            class="q-body field"
            type="text"
            [value]="integrationBindingId()"
            (input)="integrationBindingId.set(inputValue($event))"
            [disabled]="submitting()"
          />

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

          <label class="q-caption field-label" for="rmb-value">{{
            i18n.t('settings.integrations.registerBinding.value')
          }}</label>
          <input
            id="rmb-value"
            class="q-body field"
            type="password"
            autocomplete="off"
            [value]="secretValue()"
            (input)="secretValue.set(inputValue($event))"
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

  protected readonly canSubmit = () =>
    !this.submitting() &&
    this.legalEntityId().trim().length > 0 &&
    this.installationId().trim().length > 0 &&
    this.integrationBindingId().trim().length > 0 &&
    this.merchantAccountReference().trim().length > 0 &&
    this.callbackPathSegment().trim().length >= 8 &&
    this.secretValue().length > 0;

  protected inputValue(event: Event): string {
    return (event.target as HTMLInputElement).value;
  }

  protected submit(): void {
    if (!this.canSubmit()) {
      return;
    }
    this.register.emit({
      providerType: this.providerType(),
      legalEntityId: this.legalEntityId().trim(),
      installationId: this.installationId().trim(),
      integrationBindingId: this.integrationBindingId().trim(),
      merchantAccountReference: this.merchantAccountReference().trim(),
      callbackPathSegment: this.callbackPathSegment().trim(),
      secretValue: this.secretValue(),
    });
  }
}
