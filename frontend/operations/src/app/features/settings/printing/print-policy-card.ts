import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  signal,
  untracked,
} from '@angular/core';

import {
  ConfigurationResolutionView,
  ConfigurationScopeType,
} from '../../../core/api/configuration';
import { LocationScope } from '../../../core/api/operations-paths';
import { I18n } from '../../../core/i18n/i18n';
import { MessageKey } from '../../../core/i18n/messages.en';
import { TPipe } from '../../../core/i18n/t.pipe';
import { InheritedField } from '../../../shared/ui/inherited-field/inherited-field';
import { ConfigurationApi } from '../configuration-api';
import { describePrintError } from './printing-errors';
import { FOOTER_LOCALES } from './receipt-profile-card';

interface PolicyField {
  readonly code: string;
  readonly kind: 'locale' | 'minutes';
  readonly labelKey: MessageKey;
  readonly hintKey: MessageKey;
  readonly min?: number;
  readonly max?: number;
}

/** The three branch-level policy keys ADR 0154 names (ADR 0030): the language, the ticket's expiry and the delay line. */
const FIELDS: readonly PolicyField[] = [
  {
    code: 'printing.document_locale',
    kind: 'locale',
    labelKey: 'settings.printing.policy.locale.label',
    hintKey: 'settings.printing.policy.locale.hint',
  },
  {
    code: 'printing.kitchen_ticket.expire_minutes',
    kind: 'minutes',
    labelKey: 'settings.printing.policy.expire.label',
    hintKey: 'settings.printing.policy.expire.hint',
    min: 5,
    max: 240,
  },
  {
    code: 'printing.late_marker_minutes',
    kind: 'minutes',
    labelKey: 'settings.printing.policy.late.label',
    hintKey: 'settings.printing.policy.late.hint',
    min: 1,
    max: 1440,
  },
];

const SETTABLE: readonly ConfigurationScopeType[] = ['TENANT', 'BRAND', 'LOCATION'];

/**
 * Settings · Printing and receipts · Language and timing (ADR 0154, "Policy keys").
 *
 * Three ADR 0030 keys, each written at the chosen branch and each showing where its value comes from, so a
 * manager can tell "this branch says Uzbek" from "the brand says Uzbek": the language a document is printed in
 * (one per document, the branch's, never the customer's), how long a kitchen ticket waits for a printer before
 * it is dropped instead of printing stale, and how late a document must be before the paper says «delayed».
 * The platform checks the ranges; this card does not duplicate them beyond the input's own limits.
 */
@Component({
  selector: 'q-print-policy-card',
  imports: [TPipe, InheritedField],
  templateUrl: './print-policy-card.html',
  styleUrl: './printing-shared.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PrintPolicyCard {
  private readonly config = inject(ConfigurationApi);
  protected readonly i18n = inject(I18n);

  readonly scope = input.required<LocationScope>();

  protected readonly fields = FIELDS;
  protected readonly locales = FOOTER_LOCALES;
  protected readonly settable = SETTABLE;

  protected readonly resolutions = signal<ReadonlyMap<string, ConfigurationResolutionView>>(
    new Map(),
  );
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly editing = signal<string | null>(null);
  protected readonly draft = signal('');
  protected readonly reason = signal('');
  protected readonly saving = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly notice = signal<string | null>(null);

  protected readonly canSave = computed(() => {
    const field = FIELDS.find((candidate) => candidate.code === this.editing());
    if (!field || this.saving() || this.reason().trim().length === 0) {
      return false;
    }
    return field.kind === 'locale' ? this.draft() !== '' : this.minutesDraft(field) !== null;
  });

  /** A ticket for the latest read: a reply under an older branch belongs to one already left. */
  private seq = 0;

  constructor() {
    effect(() => {
      const scope = this.scope();
      untracked(() => void this.load(scope));
    });
  }

  protected resolutionFor(code: string): ConfigurationResolutionView | null {
    return this.resolutions().get(code) ?? null;
  }

  protected formatter(field: PolicyField): (value: unknown) => string {
    return (value) => {
      if (value === null || value === undefined) {
        return '—';
      }
      if (field.kind === 'minutes') {
        return this.i18n.t('settings.printing.policy.minutes', { n: String(value) });
      }
      const known = FOOTER_LOCALES.find((locale) => locale.code === value);
      return known ? this.i18n.t(known.labelKey) : String(value);
    };
  }

  protected startEditing(field: PolicyField): void {
    const current = this.resolutionFor(field.code)?.value;
    this.draft.set(current === null || current === undefined ? '' : String(current));
    this.reason.set('');
    this.error.set(null);
    this.notice.set(null);
    this.editing.set(field.code);
  }

  protected cancel(): void {
    this.editing.set(null);
    this.error.set(null);
  }

  protected async save(field: PolicyField): Promise<void> {
    if (!this.canSave()) {
      return;
    }
    const scope = this.scope();
    const minutes = field.kind === 'minutes' ? this.minutesDraft(field) : null;
    this.saving.set(true);
    this.error.set(null);
    try {
      await this.config.setValue(scope.tenantId, field.code, {
        scopeType: 'LOCATION',
        brandId: scope.brandId,
        locationId: scope.locationId,
        explicitNull: false,
        expectedVersion: this.resolutionFor(field.code)?.currentVersionAtScope ?? null,
        reason: this.reason().trim(),
        ...(field.kind === 'minutes' ? { integerValue: minutes } : { stringValue: this.draft() }),
      });
      await this.reload(scope, field.code);
      this.editing.set(null);
      this.notice.set(this.i18n.t('settings.printing.policy.saved'));
    } catch (error) {
      this.error.set(this.describe(error));
    } finally {
      this.saving.set(false);
    }
  }

  /** Back to whatever the brand, the company or the platform says: an explicit "not set here" at this branch. */
  protected async revert(field: PolicyField): Promise<void> {
    const scope = this.scope();
    this.saving.set(true);
    this.error.set(null);
    this.notice.set(null);
    try {
      await this.config.setValue(scope.tenantId, field.code, {
        scopeType: 'LOCATION',
        brandId: scope.brandId,
        locationId: scope.locationId,
        explicitNull: true,
        expectedVersion: this.resolutionFor(field.code)?.currentVersionAtScope ?? null,
        reason: this.i18n.t('settings.printing.policy.revertReason'),
      });
      await this.reload(scope, field.code);
      this.notice.set(this.i18n.t('settings.printing.policy.reverted'));
    } catch (error) {
      this.error.set(this.describe(error));
    } finally {
      this.saving.set(false);
    }
  }

  private minutesDraft(field: PolicyField): number | null {
    const text = this.draft().trim();
    if (!/^\d+$/.test(text)) {
      return null;
    }
    const value = Number(text);
    return value >= (field.min ?? 0) && value <= (field.max ?? Number.MAX_SAFE_INTEGER)
      ? value
      : null;
  }

  private async reload(scope: LocationScope, code: string): Promise<void> {
    const fresh = await this.config.resolution(
      scope.tenantId,
      code,
      'LOCATION',
      scope.brandId,
      scope.locationId,
    );
    const next = new Map(this.resolutions());
    next.set(code, fresh);
    this.resolutions.set(next);
  }

  private async load(scope: LocationScope): Promise<void> {
    const ticket = ++this.seq;
    this.loading.set(true);
    this.loadError.set(null);
    try {
      const entries = await Promise.all(
        FIELDS.map(
          async (field) =>
            [
              field.code,
              await this.config.resolution(
                scope.tenantId,
                field.code,
                'LOCATION',
                scope.brandId,
                scope.locationId,
              ),
            ] as const,
        ),
      );
      if (ticket === this.seq) {
        this.resolutions.set(new Map(entries));
      }
    } catch (error) {
      if (ticket === this.seq) {
        this.loadError.set(this.describe(error));
      }
    } finally {
      if (ticket === this.seq) {
        this.loading.set(false);
      }
    }
  }

  private describe(error: unknown): string {
    return describePrintError(error, this.i18n, {
      off: 'settings.printing.error.off',
      noRoute: 'settings.printing.error.noRoute',
    });
  }
}
