import { NgTemplateOutlet } from '@angular/common';
import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';

import { ApiError } from '../../core/api/problem';
import { I18nService } from '../../core/i18n/i18n.service';
import { MessageKey } from '../../core/i18n/messages.en';
import { TenantDirectory } from '../../shared/tenant-directory';
import { TenantPicker } from '../../shared/tenant-picker';
import { ConfigurationApi, FeatureFlagTenantSetting, FeatureFlagView } from './configuration-api';

/** Words for the flags this console knows by name; a flag it does not know shows its code. */
const FLAG_WORDS: Readonly<Record<string, { readonly name: MessageKey; readonly description: MessageKey }>> = {
  'feature.support_visits': {
    name: 'featureFlags.flag.supportVisits',
    description: 'featureFlags.flag.supportVisits.description',
  },
};

type PendingChange =
  | { readonly kind: 'platform'; readonly flag: FeatureFlagView; readonly value: boolean }
  | { readonly kind: 'tenant'; readonly flag: FeatureFlagView; readonly tenantId: string; readonly value: boolean | null; readonly version: number | null };

/**
 * IA 8.1 Feature flags -- what is being rolled out, and to whom.
 *
 * A flag is off until turned on. It can be turned on for everyone at the
 * platform, or for chosen tenants, and a tenant can be held back from a flag
 * that is on for everyone. Every change states a reason and is kept in the
 * audit log like any other setting.
 */
@Component({
  selector: 'app-feature-flags',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TenantPicker, NgTemplateOutlet],
  templateUrl: './feature-flags.html',
  styleUrl: './feature-flags.css',
})
export class FeatureFlags {
  protected readonly i18n = inject(I18nService);
  protected readonly directory = inject(TenantDirectory);
  private readonly api = inject(ConfigurationApi);

  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly flags = signal<readonly FeatureFlagView[]>([]);
  protected readonly pending = signal<PendingChange | null>(null);
  protected readonly reason = signal('');
  protected readonly busy = signal(false);
  protected readonly actionError = signal<string | null>(null);
  protected readonly actionMessage = signal<string | null>(null);

  protected readonly addingTo = signal<string | null>(null);
  protected readonly addTenantId = signal('');
  protected readonly addValue = signal(true);

  constructor() {
    void this.directory.load();
    void this.load();
  }

  private async load(): Promise<void> {
    this.loading.set(true);
    this.loadError.set(null);
    try {
      this.flags.set(await this.api.featureFlags());
    } catch (error) {
      this.loadError.set(this.i18n.describe(error as ApiError));
    } finally {
      this.loading.set(false);
    }
  }

  /** The operator's name for a flag, falling back to its code for one this build has no words for. */
  protected title(flag: FeatureFlagView): string {
    const words = FLAG_WORDS[flag.code];
    return words ? this.i18n.t(words.name) : flag.code;
  }

  protected description(flag: FeatureFlagView): string {
    const words = FLAG_WORDS[flag.code];
    return words ? this.i18n.t(words.description) : flag.description;
  }

  protected platformOn(flag: FeatureFlagView): boolean {
    return flag.platformValue ?? flag.defaultValue;
  }

  /** One line saying where the flag is on right now. */
  protected summary(flag: FeatureFlagView): string {
    const on = flag.tenants.filter((setting) => setting.value === true).length;
    const off = flag.tenants.filter((setting) => setting.value === false).length;
    if (this.platformOn(flag)) {
      return off === 0
        ? this.i18n.t('featureFlags.summary.everyone')
        : this.i18n.t('featureFlags.summary.everyoneExcept', { count: off });
    }
    return on === 0
      ? this.i18n.t('featureFlags.summary.nobody')
      : this.i18n.t('featureFlags.summary.some', { count: on });
  }

  protected settingLabel(setting: FeatureFlagTenantSetting): string {
    if (setting.value === null) {
      return this.i18n.t('featureFlags.tenant.follows');
    }
    return this.i18n.t(setting.value ? 'featureFlags.tenant.on' : 'featureFlags.tenant.off');
  }

  protected tenantName(setting: FeatureFlagTenantSetting): string {
    return setting.tenantName ?? this.directory.nameOf(setting.tenantId);
  }

  protected ask(change: PendingChange): void {
    this.pending.set(change);
    this.reason.set('');
    this.actionError.set(null);
  }

  protected isPending(flag: FeatureFlagView, tenantId: string | null): boolean {
    const change = this.pending();
    if (change === null || change.flag.code !== flag.code) {
      return false;
    }
    return change.kind === 'platform' ? tenantId === null : change.tenantId === tenantId;
  }

  protected openAdd(flag: FeatureFlagView): void {
    this.addingTo.set(this.addingTo() === flag.code ? null : flag.code);
    this.addTenantId.set('');
    this.addValue.set(!this.platformOn(flag));
    this.pending.set(null);
  }

  protected askAdd(flag: FeatureFlagView): void {
    const tenantId = this.addTenantId();
    if (tenantId.length === 0) {
      return;
    }
    const existing = flag.tenants.find((setting) => setting.tenantId === tenantId);
    this.ask({ kind: 'tenant', flag, tenantId, value: this.addValue(), version: existing?.version ?? null });
  }

  /** Writes the pending change through the ordinary configuration write, with the version last read. */
  protected async confirm(): Promise<void> {
    const change = this.pending();
    const reason = this.reason().trim();
    if (change === null || reason.length === 0 || this.busy()) {
      return;
    }
    this.busy.set(true);
    this.actionError.set(null);
    this.actionMessage.set(null);
    try {
      if (change.kind === 'platform') {
        await this.api.setValue(change.flag.code, {
          scopeType: 'PLATFORM',
          explicitNull: false,
          booleanValue: change.value,
          expectedVersion: change.flag.platformVersion,
          reason,
        });
      } else {
        await this.api.setValue(change.flag.code, {
          scopeType: 'TENANT',
          tenantId: change.tenantId,
          explicitNull: change.value === null,
          booleanValue: change.value ?? undefined,
          expectedVersion: change.version,
          reason,
        });
      }
      this.pending.set(null);
      this.addingTo.set(null);
      this.actionMessage.set(this.i18n.t('featureFlags.saved'));
      await this.load();
    } catch (error) {
      this.actionError.set(this.i18n.describe(error as ApiError));
      await this.load();
    } finally {
      this.busy.set(false);
    }
  }

  protected isListed(flag: FeatureFlagView, tenantId: string): boolean {
    return flag.tenants.some((setting) => setting.tenantId === tenantId);
  }
}
