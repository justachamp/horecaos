import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  inject,
  input,
  output,
  signal,
} from '@angular/core';

import { ConfigurationResolutionView, ConfigurationScopeType } from '../../core/api/configuration';
import { LocationScope } from '../../core/api/operations-paths';
import { ApiError } from '../../core/api/problem-details';
import { I18n } from '../../core/i18n/i18n';
import { MessageKey } from '../../core/i18n/messages.en';
import { TPipe } from '../../core/i18n/t.pipe';
import { InheritedField } from '../../shared/ui/inherited-field/inherited-field';
import { describeApiError } from '../orders/order-errors';
import { PolicyScope } from './headcount-api';
import {
  PAID_HOLD_MODES,
  PAID_HOLD_OVERDUE_MAX,
  PAID_HOLD_OVERDUE_MIN,
  PaidHoldApi,
  PaidHoldMode,
  PaidHoldPolicy,
} from './paid-hold-api';

const MODE_LABELS: Readonly<Record<PaidHoldMode, MessageKey>> = {
  SHADOW: 'kitchen.paidHold.mode.SHADOW',
  HOLD_UNTIL_PAID: 'kitchen.paidHold.mode.HOLD_UNTIL_PAID',
  OFF: 'kitchen.paidHold.mode.OFF',
};

/**
 * Whether a buffered ticket waits for the money its order requires (ADR 0168 Decision 8), as a
 * `q-inherited-field` (set here, inherited from, trace) edited as one whole document.
 *
 * **The platform starts in trial.** With nothing published anywhere the policy is `SHADOW`: the sweep counts
 * what it would hold and holds nothing, so the card shows the mode as a trial and says so. The platform's own
 * level is never written from here; the owner publishes it by an operator-run start-up, and the ladder shows
 * whether a level was authored.
 *
 * **Replace, not merge** (ADR 0030). A document at a level replaces the one above it as a whole, so the form
 * opens pre-filled with what is in force at the chosen level and saves all of it. A branch that serves regulars
 * on trust states `OFF` at its own level and keeps it whatever the platform value becomes.
 *
 * **Concurrency.** The form is opened at the `versionAtScope` the read reported and sends it back as `If-Match`;
 * a second manager publishing underneath gets `STALE_VERSION` here and the only way forward is to reopen.
 */
@Component({
  selector: 'q-paid-hold-card',
  imports: [TPipe, InheritedField],
  templateUrl: './paid-hold-card.html',
  styleUrl: './paid-hold-card.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PaidHoldCard implements OnInit {
  private readonly api = inject(PaidHoldApi);
  protected readonly i18n = inject(I18n);

  readonly scope = input.required<LocationScope>();
  /** Holds `kitchen.paid-hold.manage`: without it the mode is shown and not editable. */
  readonly canManage = input(false);

  /** A publication landed. */
  readonly saved = output<void>();

  protected readonly modes: readonly PaidHoldMode[] = PAID_HOLD_MODES;
  protected readonly editableScopes: readonly ConfigurationScopeType[] = [
    'TENANT',
    'BRAND',
    'LOCATION',
  ];
  protected readonly levels: readonly PolicyScope[] = ['LOCATION', 'BRAND', 'TENANT'];
  protected readonly overdueMin = PAID_HOLD_OVERDUE_MIN;
  protected readonly overdueMax = PAID_HOLD_OVERDUE_MAX;

  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  /** What is in force at this branch. */
  protected readonly view = signal<PaidHoldPolicy | null>(null);

  protected readonly editing = signal(false);
  protected readonly level = signal<PolicyScope>('LOCATION');
  /** The document at the level being edited, and so the version the save is checked against. */
  protected readonly editView = signal<PaidHoldPolicy | null>(null);
  protected readonly modeDraft = signal<PaidHoldMode>('SHADOW');
  protected readonly overdueDraft = signal('10');
  protected readonly reason = signal('');
  protected readonly saving = signal(false);
  protected readonly saveError = signal<string | null>(null);
  protected readonly stale = signal(false);
  protected readonly justSaved = signal(false);

  protected readonly resolution = computed<ConfigurationResolutionView | null>(() => {
    const current = this.view();
    if (!current) {
      return null;
    }
    return {
      keyCode: 'kitchen.paid_hold',
      value: this.summaryOf(current),
      cameFromDefault: current.isDefault,
      source: current.isDefault ? 'CODE_DEFAULT' : 'SCOPED_VALUE',
      winningScope: current.winningScope,
      inspectedLevels: current.levels.map((level) => ({
        scopeType: level.scopeType,
        outcome: level.authored ? ('VALUE' as const) : ('NOT_SET' as const),
        version: null,
        changedByName: null,
        changedAt: null,
      })),
      describe: `kitchen.paid_hold -> ${current.winningScope ?? 'DEFAULT'}`,
      currentVersionAtScope: current.versionAtScope > 0 ? current.versionAtScope : null,
    };
  });

  protected readonly overdueValid = computed(() => {
    const raw = this.overdueDraft().trim();
    if (!/^\d+$/.test(raw)) {
      return false;
    }
    const minutes = Number(raw);
    return minutes >= PAID_HOLD_OVERDUE_MIN && minutes <= PAID_HOLD_OVERDUE_MAX;
  });

  protected readonly canSave = computed(
    () =>
      !this.saving() &&
      !this.stale() &&
      this.editView() !== null &&
      this.overdueValid() &&
      this.reason().trim().length > 0,
  );

  protected readonly formatValue = (value: unknown): string =>
    typeof value === 'string' ? value : '';

  async ngOnInit(): Promise<void> {
    await this.load();
  }

  protected modeLabel(mode: PaidHoldMode): string {
    return this.i18n.t(MODE_LABELS[mode]);
  }

  protected levelLabel(level: PolicyScope): string {
    switch (level) {
      case 'LOCATION':
        return this.i18n.t('kitchen.paidHold.scope.location');
      case 'BRAND':
        return this.i18n.t('kitchen.paidHold.scope.brand');
      case 'TENANT':
        return this.i18n.t('kitchen.paidHold.scope.tenant');
    }
  }

  protected async startEditing(): Promise<void> {
    this.level.set('LOCATION');
    this.reason.set('');
    this.saveError.set(null);
    this.stale.set(false);
    this.justSaved.set(false);
    this.editing.set(true);
    await this.openLevel('LOCATION');
  }

  protected cancelEditing(): void {
    this.editing.set(false);
    this.saveError.set(null);
    this.stale.set(false);
  }

  protected async onLevelChange(level: PolicyScope): Promise<void> {
    this.level.set(level);
    this.saveError.set(null);
    this.stale.set(false);
    await this.openLevel(level);
  }

  protected async save(): Promise<void> {
    const document = this.editView();
    if (!document || !this.canSave()) {
      return;
    }
    this.saving.set(true);
    this.saveError.set(null);
    try {
      await this.api.publish(
        this.scope(),
        this.level(),
        {
          mode: this.modeDraft(),
          overdueAfterMinutes: Number(this.overdueDraft().trim()),
          reason: this.reason().trim(),
        },
        document.versionAtScope,
      );
      this.editing.set(false);
      this.justSaved.set(true);
      await this.load();
      this.saved.emit();
    } catch (error) {
      if (error instanceof ApiError && error.code === 'STALE_VERSION') {
        this.stale.set(true);
      } else if (error instanceof ApiError) {
        this.saveError.set(describeApiError(error, (key, values) => this.i18n.t(key, values)));
      } else {
        throw error;
      }
    } finally {
      this.saving.set(false);
    }
  }

  private summaryOf(policy: PaidHoldPolicy): string {
    return this.i18n.t('kitchen.paidHold.summary', {
      mode: this.modeLabel(policy.mode),
      minutes: policy.overdueAfterMinutes,
    });
  }

  private async load(): Promise<void> {
    this.loading.set(true);
    try {
      this.view.set(await this.api.policy(this.scope(), 'LOCATION'));
      this.loadError.set(null);
    } catch (error) {
      if (error instanceof ApiError) {
        this.loadError.set(describeApiError(error, (key, values) => this.i18n.t(key, values)));
      } else {
        throw error;
      }
    } finally {
      this.loading.set(false);
    }
  }

  /** Reads the document at the chosen level and pre-fills the form with it: a save replaces it whole. */
  private async openLevel(level: PolicyScope): Promise<void> {
    this.editView.set(null);
    try {
      const document = await this.api.policy(this.scope(), level);
      this.editView.set(document);
      this.modeDraft.set(document.mode);
      this.overdueDraft.set(String(document.overdueAfterMinutes));
    } catch (error) {
      if (error instanceof ApiError) {
        this.saveError.set(describeApiError(error, (key, values) => this.i18n.t(key, values)));
      } else {
        throw error;
      }
    }
  }
}
