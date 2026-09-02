import { ChangeDetectionStrategy, Component, inject, input, output, signal } from '@angular/core';

import { I18n } from '../../../core/i18n/i18n';

/** What the parent screen needs to call one of the two rotate-by-value endpoints. */
export interface RotateSecretSubmission {
  readonly value: string;
  readonly reason: string;
}

/**
 * The rotate-through-the-door dialog (ADR 0065), shared by an installation's
 * credential and a merchant binding's credential — both endpoints take the
 * same `{value, reason}` shape, and this is the one form for it.
 *
 * A centred modal rather than a side drawer, following `order-reason-dialog`
 * in this app: a short form fits it, where the longer connect flow wants the
 * drawer instead.
 */
@Component({
  selector: 'app-rotate-secret-dialog',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="backdrop" (click)="cancel.emit()">
      <div
        class="dialog"
        role="dialog"
        aria-modal="true"
        [attr.aria-label]="i18n.t('settings.integrations.rotate.title')"
        (click)="$event.stopPropagation()"
      >
        <h2 class="q-subhead title">{{ i18n.t('settings.integrations.rotate.title') }}</h2>
        <p class="q-body-sm lead">{{ i18n.t('settings.integrations.rotate.lead') }}</p>

        @if (unverifiable()) {
          <p class="q-body-sm notice">
            {{ i18n.t('settings.integrations.rotate.unverifiedNotice') }}
          </p>
        }

        <label class="q-caption field-label" for="rotate-value">{{
          i18n.t('settings.integrations.rotate.value')
        }}</label>
        <input
          id="rotate-value"
          class="q-body field"
          type="password"
          autocomplete="off"
          [value]="value()"
          (input)="onValueInput($event)"
          [disabled]="submitting()"
        />

        <label class="q-caption field-label" for="rotate-reason">{{
          i18n.t('settings.integrations.rotate.reason')
        }}</label>
        <input
          id="rotate-reason"
          class="q-body field"
          type="text"
          [value]="reason()"
          (input)="onReasonInput($event)"
          [disabled]="submitting()"
        />

        @if (errorMessage(); as message) {
          <p class="q-body-sm error" role="alert">{{ message }}</p>
        }

        <div class="actions">
          <button
            type="button"
            class="q-body secondary"
            (click)="cancel.emit()"
            [disabled]="submitting()"
          >
            {{ i18n.t('settings.integrations.rotate.cancel') }}
          </button>
          <button type="button" class="q-body primary" (click)="submit()" [disabled]="!canSubmit()">
            {{
              submitting()
                ? i18n.t('settings.integrations.rotate.submitting')
                : i18n.t('settings.integrations.rotate.submit')
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
      align-items: center;
      justify-content: center;
    }

    .dialog {
      width: 380px;
      max-width: calc(100vw - 32px);
      background: var(--q-canvas);
      border: 1px solid var(--q-hairline);
      border-radius: var(--q-radius);
      padding: 24px;
      display: flex;
      flex-direction: column;
    }

    .title {
      margin: 0;
    }

    .lead {
      color: var(--q-ink-muted);
      margin: 8px 0 0;
    }

    .notice {
      margin: 12px 0 0;
      color: var(--q-warning-text);
      background: var(--q-warning-tint);
      padding: 8px 12px;
      border-radius: var(--q-radius);
    }

    .field-label {
      color: var(--q-ink-muted);
      margin-top: 16px;
    }

    .field {
      margin-top: 4px;
      height: 40px;
      padding: 0 12px;
      border: 1px solid var(--q-surface-2);
      border-radius: var(--q-radius);
      background: var(--q-canvas);
      color: var(--q-ink);
    }

    .field:disabled {
      background: var(--q-surface-1);
    }

    .error {
      margin: 12px 0 0;
      color: var(--q-error-text);
      background: var(--q-error-tint);
      padding: 8px 12px;
      border-radius: var(--q-radius);
    }

    .actions {
      margin-top: 24px;
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
export class RotateSecretDialog {
  protected readonly i18n = inject(I18n);

  /** True for a provider with no harmless call to verify the value before it is swapped in. */
  readonly unverifiable = input(false);
  readonly submitting = input(false);
  readonly errorMessage = input<string | null>(null);

  readonly confirm = output<RotateSecretSubmission>();
  readonly cancel = output<void>();

  protected readonly value = signal('');
  protected readonly reason = signal('');

  protected readonly canSubmit = () =>
    !this.submitting() && this.value().length > 0 && this.reason().trim().length > 0;

  protected onValueInput(event: Event): void {
    this.value.set((event.target as HTMLInputElement).value);
  }

  protected onReasonInput(event: Event): void {
    this.reason.set((event.target as HTMLInputElement).value);
  }

  protected submit(): void {
    if (!this.canSubmit()) {
      return;
    }
    this.confirm.emit({ value: this.value(), reason: this.reason().trim() });
  }
}
