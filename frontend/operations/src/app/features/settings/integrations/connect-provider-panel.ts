import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
} from '@angular/core';

import { I18n } from '../../../core/i18n/i18n';
import { BrandView } from '../brand-profile/brand-profile-api';
import { LocationView } from '../locations/locations-api';
import { ProviderConnectDeclaration } from './integrations-api';

/**
 * What the parent screen needs to write the secret through the door and then
 * install the provider. `secretValue` is the one field the selected provider
 * declared `secret: true` — never persisted anywhere by this component beyond
 * this one emitted event, and cleared from the form's own state immediately
 * after emitting.
 */
export interface ConnectSubmission {
  readonly providerType: string;
  readonly category: string;
  readonly displayName: string;
  readonly environmentCode: string;
  readonly reference: string;
  readonly secretValue: string;
}

/**
 * What the parent needs to bind the just-connected installation to a brand
 * or, narrower, one of its locations. `locationId` null means "the whole
 * brand" — `BindingRef`'s own nullable-location scoping (ADR 0026), not an
 * unset field.
 */
export interface BindSubmission {
  readonly brandId: string;
  readonly locationId: string | null;
}

/**
 * "Connect a provider" (ADR 0065), now two steps in one drawer instead of
 * one: renders the connect form entirely from the server's per-adapter field
 * declarations ({@link ProviderConnectDeclaration}), so a new provider
 * adapter costs a catalogue entry on the server, never a change here — the
 * same neutrality discipline the connect-fields endpoint's own doc comment
 * names — then, on a successful connect, continues straight into binding the
 * new installation to a brand or location rather than dropping the operator
 * back at the installations table to start a second, disconnected journey.
 *
 * <p>{@link phase} is parent-controlled, matching every other panel in this
 * feature (`RegisterMerchantBindingPanel`, `RotateSecretDialog`): this
 * component owns no async call of its own, so `IntegrationsPage` decides when
 * `connect` succeeded and flips {@link phase} to `'bind'` rather than this
 * component guessing at it. `brands`/`locations` are the same flat,
 * fan-out-fetched lists `StaffApi.scopeDirectory`'s own doc comment
 * describes: no single endpoint returns every location in the tenant.
 *
 * <p>Ported from `frontend/control-plane/src/app/features/integrations/` in
 * wave 26 (ADR 0065's placement move); the connect step's own behaviour is
 * unchanged from that port. The bind step is new in wave 66.
 */
@Component({
  selector: 'app-connect-provider-panel',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="backdrop" (click)="cancel.emit()">
      <div
        class="drawer"
        role="dialog"
        aria-modal="true"
        [attr.aria-label]="
          phase() === 'connect'
            ? i18n.t('settings.integrations.connect.title')
            : i18n.t('settings.integrations.connect.bind.title')
        "
        (click)="$event.stopPropagation()"
      >
        <div class="header">
          <h2 class="q-subhead">
            {{
              phase() === 'connect'
                ? i18n.t('settings.integrations.connect.title')
                : i18n.t('settings.integrations.connect.bind.title')
            }}
          </h2>
          <button type="button" class="q-body close" (click)="cancel.emit()" aria-label="Close">
            ✕
          </button>
        </div>

        @if (phase() === 'connect') {
          <div class="body">
            <label class="q-caption field-label" for="connect-provider">{{
              i18n.t('settings.integrations.connect.provider')
            }}</label>
            <select
              id="connect-provider"
              class="q-body field"
              [value]="providerType()"
              (change)="onProviderChange($event)"
              [disabled]="submitting()"
            >
              @for (declaration of providers(); track declaration.providerType) {
                <option [value]="declaration.providerType">{{ declaration.providerType }}</option>
              }
            </select>

            <label class="q-caption field-label" for="connect-display-name">{{
              i18n.t('settings.integrations.connect.displayName')
            }}</label>
            <input
              id="connect-display-name"
              class="q-body field"
              type="text"
              [value]="displayName()"
              (input)="displayName.set(inputValue($event))"
              [disabled]="submitting()"
            />

            <label class="q-caption field-label" for="connect-environment">{{
              i18n.t('settings.integrations.connect.environmentCode')
            }}</label>
            <input
              id="connect-environment"
              class="q-body field"
              type="text"
              [value]="environmentCode()"
              (input)="environmentCode.set(inputValue($event))"
              [disabled]="submitting()"
            />
            <p class="q-caption hint">
              {{ i18n.t('settings.integrations.connect.environmentCode.hint') }}
            </p>

            @for (field of selectedDeclaration()?.fields ?? []; track field.key) {
              <label class="q-caption field-label" [for]="'connect-field-' + field.key">{{
                label(field.key)
              }}</label>
              <input
                [id]="'connect-field-' + field.key"
                class="q-body field"
                [type]="field.secret ? 'password' : 'text'"
                [autocomplete]="field.secret ? 'off' : 'on'"
                [value]="fieldValue(field.key)"
                (input)="onFieldInput(field.key, $event)"
                [disabled]="submitting()"
              />
            }

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
              {{ i18n.t('settings.integrations.connect.cancel') }}
            </button>
            <button
              type="button"
              class="q-body primary"
              (click)="submit()"
              [disabled]="!canSubmit()"
            >
              {{
                submitting()
                  ? i18n.t('settings.integrations.connect.submitting')
                  : i18n.t('settings.integrations.connect.submit')
              }}
            </button>
          </div>
        } @else {
          <div class="body">
            <p class="q-body-sm lead">{{ i18n.t('settings.integrations.connect.success') }}</p>

            <label class="q-caption field-label" for="connect-bind-brand">{{
              i18n.t('settings.integrations.connect.bind.brand')
            }}</label>
            <select
              id="connect-bind-brand"
              class="q-body field"
              [value]="bindBrandId() ?? ''"
              (change)="onBindBrandChange($event)"
              [disabled]="bindSubmitting()"
            >
              @for (brand of brands(); track brand.id) {
                <option [value]="brand.id">{{ brand.displayName }}</option>
              }
            </select>
            @if (brands().length === 0) {
              <p class="q-caption hint">{{ i18n.t('settings.integrations.connect.bind.noBrands') }}</p>
            }

            <label class="q-caption field-label" for="connect-bind-location">{{
              i18n.t('settings.integrations.connect.bind.location')
            }}</label>
            <select
              id="connect-bind-location"
              class="q-body field"
              [value]="bindLocationId() ?? ''"
              (change)="onBindLocationChange($event)"
              [disabled]="bindSubmitting()"
            >
              <option value="">{{ i18n.t('settings.integrations.connect.bind.locationAny') }}</option>
              @for (location of bindLocationsForBrand(); track location.id) {
                <option [value]="location.id">{{ location.displayName }}</option>
              }
            </select>
            <p class="q-caption hint">
              {{ i18n.t('settings.integrations.connect.bind.location.hint') }}
            </p>

            @if (bindErrorMessage(); as message) {
              <p class="q-body-sm error" role="alert">{{ message }}</p>
            }
          </div>

          <div class="actions">
            <button
              type="button"
              class="q-body secondary"
              (click)="cancel.emit()"
              [disabled]="bindSubmitting()"
            >
              {{ i18n.t('settings.integrations.connect.bind.skip') }}
            </button>
            <button
              type="button"
              class="q-body primary"
              (click)="submitBind()"
              [disabled]="!canSubmitBind()"
            >
              {{
                bindSubmitting()
                  ? i18n.t('settings.integrations.connect.bind.submitting')
                  : i18n.t('settings.integrations.connect.bind.submit')
              }}
            </button>
          </div>
        }
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

    .field-label {
      display: block;
      color: var(--q-ink-muted);
      margin-top: 16px;
    }

    .field-label:first-of-type {
      margin-top: 0;
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

    .hint {
      color: var(--q-ink-subtle);
      margin: 4px 0 0;
    }

    .lead {
      color: var(--q-ink-muted);
      margin: 0 0 8px;
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
export class ConnectProviderPanel {
  protected readonly i18n = inject(I18n);

  readonly providers = input.required<readonly ProviderConnectDeclaration[]>();
  readonly submitting = input(false);
  readonly errorMessage = input<string | null>(null);

  /** Parent-controlled — see this file's own doc comment for why. */
  readonly phase = input<'connect' | 'bind'>('connect');
  readonly brands = input<readonly BrandView[]>([]);
  /** Every location across every brand, flat — filtered per-brand by {@link bindLocationsForBrand}. */
  readonly locations = input<readonly LocationView[]>([]);
  readonly bindSubmitting = input(false);
  readonly bindErrorMessage = input<string | null>(null);

  readonly connect = output<ConnectSubmission>();
  readonly bind = output<BindSubmission>();
  readonly cancel = output<void>();

  protected readonly providerType = signal('');
  protected readonly displayName = signal('');
  protected readonly environmentCode = signal('');
  protected readonly fieldValues = signal<Record<string, string>>({});

  protected readonly bindBrandId = signal<string | null>(null);
  protected readonly bindLocationId = signal<string | null>(null);

  protected readonly selectedDeclaration = computed(() =>
    this.providers().find((declaration) => declaration.providerType === this.providerType()),
  );

  protected readonly bindLocationsForBrand = computed(() =>
    this.locations().filter((location) => location.brandId === this.bindBrandId()),
  );

  constructor() {
    // The select's first option is chosen by the browser before any (change)
    // fires, so the signal starts empty without this and the first field set
    // never renders until the operator touches the dropdown themselves.
    queueMicrotask(() => {
      const first = this.providers()[0];
      if (first !== undefined && this.providerType() === '') {
        this.providerType.set(first.providerType);
      }
    });

    // Same reasoning as the provider default above, for the bind step's own
    // brand select: `brands` typically arrives already populated (fetched at
    // page load, before this drawer ever opens), but an `effect` — rather
    // than another `queueMicrotask` — also covers the drawer opening before
    // that fetch settles.
    effect(
      () => {
        const list = this.brands();
        if (list.length > 0 && this.bindBrandId() === null) {
          this.bindBrandId.set(list[0].id);
        }
      },
      { allowSignalWrites: true },
    );
  }

  protected readonly canSubmit = () => {
    const declaration = this.selectedDeclaration();
    if (this.submitting() || declaration === undefined) {
      return false;
    }
    if (this.displayName().trim().length === 0 || this.environmentCode().trim().length === 0) {
      return false;
    }
    const secretField = declaration.fields.find((field) => field.secret);
    return secretField === undefined || (this.fieldValues()[secretField.key] ?? '').length > 0;
  };

  protected onProviderChange(event: Event): void {
    this.providerType.set((event.target as HTMLSelectElement).value);
    this.fieldValues.set({});
  }

  /**
   * A method rather than an inline template lookup: a `Record` index access
   * types as non-nullable under this app's `tsconfig`, and the extended
   * template diagnostic (NG8102) flags an inline `?? ''` after it as dead
   * code even though a genuinely missing key returns `undefined` at runtime.
   */
  protected fieldValue(key: string): string {
    return this.fieldValues()[key] ?? '';
  }

  protected onFieldInput(key: string, event: Event): void {
    const value = this.inputValue(event);
    this.fieldValues.update((current) => ({ ...current, [key]: value }));
  }

  protected inputValue(event: Event): string {
    return (event.target as HTMLInputElement).value;
  }

  /** `merchantId` -> `Merchant Id`. Generic on purpose: see this file's own doc comment. */
  protected label(key: string): string {
    return key.replace(/([a-z0-9])([A-Z])/g, '$1 $2').replace(/^./, (first) => first.toUpperCase());
  }

  protected submit(): void {
    const declaration = this.selectedDeclaration();
    if (!this.canSubmit() || declaration === undefined) {
      return;
    }
    const values = this.fieldValues();
    const secretField = declaration.fields.find((field) => field.secret);
    const nonSecretValues = declaration.fields
      .filter((field) => !field.secret)
      .map((field) => values[field.key])
      .filter((value): value is string => value !== undefined && value.trim().length > 0);

    this.connect.emit({
      providerType: declaration.providerType,
      category: declaration.category,
      displayName: this.displayName().trim(),
      environmentCode: this.environmentCode().trim(),
      reference: nonSecretValues.join('/'),
      secretValue: secretField === undefined ? '' : (values[secretField.key] ?? ''),
    });
  }

  protected readonly canSubmitBind = () => !this.bindSubmitting() && this.bindBrandId() !== null;

  protected onBindBrandChange(event: Event): void {
    this.bindBrandId.set(this.inputValue(event) || null);
    this.bindLocationId.set(null);
  }

  protected onBindLocationChange(event: Event): void {
    const value = this.inputValue(event);
    this.bindLocationId.set(value === '' ? null : value);
  }

  protected submitBind(): void {
    const brandId = this.bindBrandId();
    if (!this.canSubmitBind() || brandId === null) {
      return;
    }
    this.bind.emit({ brandId, locationId: this.bindLocationId() });
  }
}
