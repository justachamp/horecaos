import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';

import { CurrentTenant } from '../../core/auth/current-tenant';
import { SessionCapabilities } from '../../core/auth/session-capabilities';
import { I18n } from '../../core/i18n/i18n';
import { MessageKey } from '../../core/i18n/messages.en';
import { TPipe } from '../../core/i18n/t.pipe';
import { ConfigurationApi } from '../settings/configuration-api';

/** ADR 0030 key `iam.staff_mfa_requirement` (ADR 0148): whom the tenant asks for an authenticator code. */
export const MFA_REQUIREMENT_KEY = 'iam.staff_mfa_requirement';

export const MFA_REQUIREMENT_MODES = ['OFF', 'SENSITIVE_ROLES', 'ALL_STAFF'] as const;
export type MfaRequirementMode = (typeof MFA_REQUIREMENT_MODES)[number];

/**
 * «Вход в два шага» — the tenant's own switch for whom it asks for a code at sign-in (ADR 0148,
 * Decision 4), on the People screen where an owner already decides who has access.
 *
 * Three modes and no more: nobody (the default, which every tenant has until it opens this),
 * the owner, the administrator, finance and the brand manager, or everybody. Platform staff are
 * outside it — they are always asked, on a setting of the platform's own — and so are customers
 * and kitchen devices. It writes the ADR 0030 value at tenant scope through the same endpoint
 * every other setting uses (`TENANT_CONFIGURATION_WRITE`), so the platform refuses a value it does
 * not know and records who changed it.
 *
 * **Turning it on reaches sessions only at their next sign-in.** An already-open session is not
 * ended by the setting; the card says so after a save, because a control that quietly protects
 * nothing for the people already signed in is worse than one that says what it does.
 */
@Component({
  selector: 'q-staff-mfa-policy-card',
  imports: [TPipe],
  template: `
    @if (visible()) {
      <section class="policy" data-testid="mfa-policy-card">
        <h2 class="q-emphasis">{{ 'staff.mfa.policy.title' | t }}</h2>
        <p class="q-body-sm muted">{{ 'staff.mfa.policy.body' | t }}</p>
        @if (mode() !== null) {
          <div class="options" role="radiogroup" [attr.aria-label]="'staff.mfa.policy.title' | t">
            @for (option of modes; track option) {
              <label class="option">
                <input
                  type="radio"
                  name="mfa-policy"
                  [value]="option"
                  [checked]="selected() === option"
                  [disabled]="busy()"
                  (change)="select(option)"
                />
                <span class="q-body-sm">{{ modeKey[option] | t }}</span>
              </label>
            }
          </div>
          <button
            type="button"
            class="save"
            [disabled]="busy() || selected() === mode()"
            (click)="save()"
            data-testid="mfa-policy-save"
          >
            {{ 'staff.mfa.policy.save' | t }}
          </button>
        }
        @if (message(); as text) {
          <p
            class="q-body-sm"
            [class.error]="failed()"
            role="status"
            data-testid="mfa-policy-message"
          >
            {{ text }}
          </p>
        }
      </section>
    }
  `,
  styles: `
    .policy {
      margin: 16px 0;
      padding: 16px;
      border: 1px solid var(--q-hairline);
      border-radius: var(--q-radius);
    }

    .muted {
      color: var(--q-ink-muted);
    }

    .options {
      display: flex;
      flex-direction: column;
      gap: 8px;
      margin: 12px 0;
    }

    .option {
      display: flex;
      align-items: center;
      gap: 8px;
    }

    .save {
      height: 36px;
      padding: 0 16px;
      border: none;
      border-radius: var(--q-radius);
      background: var(--q-primary);
      color: var(--q-inverse-ink);
      cursor: pointer;
    }

    .save:disabled {
      background: var(--q-surface-2);
      color: var(--q-ink-subtle);
      cursor: default;
    }

    .error {
      color: var(--q-error-text);
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class StaffMfaPolicyCard implements OnInit {
  private readonly config = inject(ConfigurationApi);
  private readonly tenant = inject(CurrentTenant);
  private readonly capabilities = inject(SessionCapabilities);
  private readonly i18n = inject(I18n);

  protected readonly modes = MFA_REQUIREMENT_MODES;
  protected readonly modeKey: Readonly<Record<MfaRequirementMode, MessageKey>> = {
    OFF: 'staff.mfa.policy.OFF',
    SENSITIVE_ROLES: 'staff.mfa.policy.SENSITIVE_ROLES',
    ALL_STAFF: 'staff.mfa.policy.ALL_STAFF',
  };
  protected readonly visible = computed(() => this.capabilities.has('TENANT_CONFIGURATION_WRITE'));

  /** What the platform has stored (or defaults to) at tenant scope. */
  protected readonly mode = signal<MfaRequirementMode | null>(null);
  protected readonly selected = signal<MfaRequirementMode>('OFF');
  protected readonly busy = signal(false);
  protected readonly message = signal<string | null>(null);
  protected readonly failed = signal(false);

  private version: number | null = null;

  ngOnInit(): void {
    void this.load();
  }

  private async load(): Promise<void> {
    if (!this.visible()) {
      return;
    }
    await this.tenant.ensureLoaded();
    const tenantId = this.tenant.tenantId();
    if (!tenantId) {
      return;
    }
    try {
      const resolved = await this.config.resolution(tenantId, MFA_REQUIREMENT_KEY, 'TENANT');
      const current = MFA_REQUIREMENT_MODES.find((mode) => mode === resolved.value) ?? 'OFF';
      this.mode.set(current);
      this.selected.set(current);
      this.version = resolved.currentVersionAtScope;
    } catch {
      // Unreadable: the card stays hidden rather than offering a switch it cannot report the state of.
      this.mode.set(null);
    }
  }

  protected select(mode: MfaRequirementMode): void {
    this.selected.set(mode);
    this.message.set(null);
  }

  protected async save(): Promise<void> {
    const tenantId = this.tenant.tenantId();
    const chosen = this.selected();
    if (!tenantId || this.busy() || chosen === this.mode()) {
      return;
    }
    this.busy.set(true);
    this.message.set(null);
    this.failed.set(false);
    try {
      const saved = await this.config.setValue(tenantId, MFA_REQUIREMENT_KEY, {
        scopeType: 'TENANT',
        explicitNull: false,
        stringValue: chosen,
        expectedVersion: this.version,
        reason: 'Staff two-step sign-in setting changed from the People screen',
      });
      this.mode.set(chosen);
      this.version = saved.version;
      this.message.set(this.i18n.t('staff.mfa.policy.saved'));
    } catch {
      this.failed.set(true);
      this.message.set(this.i18n.t('staff.mfa.policy.failed'));
    } finally {
      this.busy.set(false);
    }
  }
}
