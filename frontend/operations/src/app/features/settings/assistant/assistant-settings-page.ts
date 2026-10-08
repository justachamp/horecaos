import { NgTemplateOutlet } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  signal,
} from '@angular/core';
import { RouterLink } from '@angular/router';

import { ConfigurationResolutionView, EditableScopeType } from '../../../core/api/configuration';
import { ApiError } from '../../../core/api/problem-details';
import { CurrentTenant } from '../../../core/auth/current-tenant';
import { I18n } from '../../../core/i18n/i18n';
import { MessageKey } from '../../../core/i18n/messages.en';
import { TPipe } from '../../../core/i18n/t.pipe';
import { InheritedField } from '../../../shared/ui/inherited-field/inherited-field';
import { describeApiError } from '../../orders/order-errors';
import { ConfigurationApi } from '../configuration-api';
import { SettingsSaved } from '../settings-saved';
import { SettingsScope } from '../settings-scope';
import { AssistantApi, AssistantUsageResponse, formatUsdCents, spendCents } from './assistant-api';

/** The per-brand switch (ADR 0069: "ships behind an entitlement and a per-tenant switch, defaulting off"). */
export const ASSISTANT_ENABLED_CODE = 'assistant.enabled';

/**
 * The longest disclosure the platform accepts (`AssistantConfigurationKeys.DISCLOSURE_TEXT_MAXIMUM_CHARACTERS`).
 * The server refuses more with a 400; the counter under the box says so before it comes to that.
 */
export const DISCLOSURE_MAXIMUM_CHARACTERS = 500;

type DisclosureLocale = 'en' | 'ru' | 'uz';

interface DisclosureField {
  readonly locale: DisclosureLocale;
  readonly code: string;
  readonly labelKey: MessageKey;
}

/** One key per reply language the assistant speaks; Russian first, the language most of its customers write in. */
const DISCLOSURE_FIELDS: readonly DisclosureField[] = [
  {
    locale: 'ru',
    code: 'assistant.disclosure_text_ru',
    labelKey: 'settings.assistant.disclosure.field.ru',
  },
  {
    locale: 'uz',
    code: 'assistant.disclosure_text_uz',
    labelKey: 'settings.assistant.disclosure.field.uz',
  },
  {
    locale: 'en',
    code: 'assistant.disclosure_text_en',
    labelKey: 'settings.assistant.disclosure.field.en',
  },
];

/** Both keys are `settableAt(PLATFORM, TENANT, BRAND)`: a branch has no setting of its own to edit. */
const SETTABLE_SCOPES = ['PLATFORM', 'TENANT', 'BRAND'] as const;

/**
 * Chat assistant (ADR 0069): whether the assistant answers customers in the Telegram bot, what it
 * has cost this month against the ceiling HorecaOS set for the account, and what it says before its
 * first answer.
 *
 * **Follows the scope bar, down to a brand.** The switch and the wording resolve company, then brand
 * (ADR 0030), so a pilot can be one brand's bot first. A branch has no setting of its own: with the
 * bar on a branch the page reads and writes the brand it belongs to, and says so.
 *
 * **The ceiling is shown and not edited.** `assistant.monthly_spend_ceiling_usd_cents` caps what
 * HorecaOS pays the AI provider for this account, so it is the platform's to set and deliberately not
 * a tenant-visible key: a tenant cannot read or raise it through the configuration endpoints, and this
 * screen reads it from the usage report instead.
 *
 * **The disclosure can be replaced and cannot be removed.** Blank means "HorecaOS's default wording",
 * and the default is read from the same place the assistant reads it, never copied into this app.
 *
 * Two reads can be refused independently: the configuration (`tenant.configuration.read`) and the
 * usage report (`assistant.read`). Each says so on its own card rather than blanking the page.
 */
@Component({
  selector: 'q-assistant-settings-page',
  imports: [TPipe, InheritedField, RouterLink, NgTemplateOutlet],
  templateUrl: './assistant-settings-page.html',
  styleUrl: './assistant-settings-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AssistantSettingsPage {
  private readonly configApi = inject(ConfigurationApi);
  private readonly assistantApi = inject(AssistantApi);
  private readonly tenant = inject(CurrentTenant);
  protected readonly scope = inject(SettingsScope);
  private readonly saved = inject(SettingsSaved);
  protected readonly i18n = inject(I18n);

  protected readonly disclosureFields = DISCLOSURE_FIELDS;
  protected readonly settableScopes = SETTABLE_SCOPES;
  protected readonly maximumCharacters = DISCLOSURE_MAXIMUM_CHARACTERS;
  protected readonly enabledCode = ASSISTANT_ENABLED_CODE;

  /** The level written to: the bar's, except that a branch is the brand it belongs to. */
  protected readonly level = computed<EditableScopeType>(() =>
    this.scope.level() === 'LOCATION' ? 'BRAND' : this.scope.level(),
  );
  protected readonly brandStandsInForBranch = computed(() => this.scope.level() === 'LOCATION');

  protected readonly loading = signal(true);
  protected readonly denied = signal(false);
  protected readonly loadError = signal<string | null>(null);
  protected readonly resolutions = signal<ReadonlyMap<string, ConfigurationResolutionView>>(
    new Map(),
  );

  protected readonly usage = signal<AssistantUsageResponse | null>(null);
  protected readonly usageDenied = signal(false);
  protected readonly usageError = signal<string | null>(null);

  protected readonly editingCode = signal<string | null>(null);
  protected readonly draftSwitch = signal(false);
  protected readonly draftText = signal('');
  protected readonly draftReason = signal('');
  protected readonly saving = signal(false);
  protected readonly saveError = signal<string | null>(null);

  /** What the ceiling and the spend say, in dollars, from integer cents. */
  protected readonly ceilingText = computed(() => {
    const usage = this.usage();
    return usage ? formatUsdCents(usage.ceilingUsdCents) : '';
  });
  protected readonly spendText = computed(() => {
    const usage = this.usage();
    return usage ? formatUsdCents(spendCents(usage.costUsdMicros)) : '';
  });
  protected readonly handedOver = computed(() => {
    const usage = this.usage();
    return usage ? usage.refused + usage.escalated : 0;
  });

  protected readonly draftLength = computed(() => this.draftText().length);
  protected readonly draftTooLong = computed(
    () => this.draftText().length > DISCLOSURE_MAXIMUM_CHARACTERS,
  );

  constructor() {
    void this.loadUsage();
    // Re-reads the four keys whenever the scope bar's own brand or level changes -- the same
    // "effect keyed on the resolved input" idiom `OrderPolicyPage` uses.
    effect(() => {
      const tenantId = this.tenant.tenantId();
      const brandId = this.scope.brandId();
      this.level();
      if (tenantId && brandId) {
        void this.loadFields(tenantId, brandId);
      } else if (this.scope.denied()) {
        this.denied.set(true);
        this.loading.set(false);
      }
    });
  }

  // ------------------------------------------------------------------ reads

  protected resolutionFor(code: string): ConfigurationResolutionView | null {
    return this.resolutions().get(code) ?? null;
  }

  /** Bound once: a fresh closure each change-detection pass would defeat `InheritedField`'s memoization. */
  protected readonly formatOnOff = (value: unknown): string =>
    value ? this.i18n.t('settings.assistant.on') : this.i18n.t('settings.assistant.off');

  private readonly disclosureFormatters = new Map<string, (value: unknown) => string>(
    DISCLOSURE_FIELDS.map((field) => [
      field.code,
      (value: unknown) =>
        typeof value === 'string' && value.trim().length > 0
          ? value
          : this.i18n.t('settings.assistant.disclosure.blank'),
    ]),
  );

  protected disclosureFormatter(field: DisclosureField): (value: unknown) => string {
    return this.disclosureFormatters.get(field.code) ?? ((value) => String(value));
  }

  /** The platform's own wording for this language, from the report: what a blank value falls back to. */
  protected defaultWording(field: DisclosureField): string | null {
    return this.usage()?.defaultDisclosure[field.locale] ?? null;
  }

  protected noProviderNotice(): boolean {
    const usage = this.usage();
    return usage !== null && !usage.providerConfigured;
  }

  protected notEntitledNotice(): boolean {
    const usage = this.usage();
    return usage !== null && !usage.entitled;
  }

  // ------------------------------------------------------------ editing

  protected startEditingSwitch(): void {
    this.draftSwitch.set(Boolean(this.resolutionFor(ASSISTANT_ENABLED_CODE)?.value));
    this.beginEditing(ASSISTANT_ENABLED_CODE);
  }

  protected startEditingDisclosure(field: DisclosureField): void {
    const current = this.resolutionFor(field.code)?.value;
    this.draftText.set(typeof current === 'string' ? current : '');
    this.beginEditing(field.code);
  }

  private beginEditing(code: string): void {
    this.draftReason.set('');
    this.saveError.set(null);
    this.editingCode.set(code);
  }

  protected cancelEditing(): void {
    this.editingCode.set(null);
  }

  protected canSave(): boolean {
    if (this.saving() || this.draftReason().trim().length === 0) {
      return false;
    }
    return this.editingCode() === ASSISTANT_ENABLED_CODE || !this.draftTooLong();
  }

  protected async save(): Promise<void> {
    const code = this.editingCode();
    const tenantId = this.tenant.tenantId();
    const brandId = this.scope.brandId();
    if (!code || !tenantId || !brandId || !this.canSave()) {
      return;
    }
    const isSwitch = code === ASSISTANT_ENABLED_CODE;
    this.saving.set(true);
    this.saveError.set(null);
    const level = this.level();
    try {
      await this.configApi.setValue(tenantId, code, {
        scopeType: level,
        brandId: level === 'BRAND' ? brandId : null,
        locationId: null,
        explicitNull: false,
        expectedVersion: this.resolutionFor(code)?.currentVersionAtScope ?? null,
        reason: this.draftReason().trim(),
        ...(isSwitch
          ? { booleanValue: this.draftSwitch() }
          : { stringValue: this.draftText().trim() }),
      });
      await this.reload(tenantId, brandId, code);
      this.editingCode.set(null);
      this.announce('set', code);
      if (isSwitch) {
        void this.loadUsage();
      }
    } catch (error) {
      this.saveError.set(this.describe(error));
    } finally {
      this.saving.set(false);
    }
  }

  /** Hands the value back to whatever the next level up resolves — no separate reason prompt. */
  protected async revert(code: string): Promise<void> {
    const tenantId = this.tenant.tenantId();
    const brandId = this.scope.brandId();
    if (!tenantId || !brandId) {
      return;
    }
    this.saving.set(true);
    this.saveError.set(null);
    const level = this.level();
    try {
      await this.configApi.setValue(tenantId, code, {
        scopeType: level,
        brandId: level === 'BRAND' ? brandId : null,
        locationId: null,
        explicitNull: true,
        expectedVersion: this.resolutionFor(code)?.currentVersionAtScope ?? null,
        reason: this.i18n.t('settings.assistant.revertReason'),
      });
      await this.reload(tenantId, brandId, code);
      this.announce('reverted', code);
      if (code === ASSISTANT_ENABLED_CODE) {
        void this.loadUsage();
      }
    } catch (error) {
      this.saveError.set(this.describe(error));
    } finally {
      this.saving.set(false);
    }
  }

  private announce(kind: 'set' | 'reverted', code: string): void {
    const isSwitch = code === ASSISTANT_ENABLED_CODE;
    const field = DISCLOSURE_FIELDS.find((candidate) => candidate.code === code);
    const label = isSwitch
      ? this.i18n.t('settings.assistant.switch.field')
      : field
        ? this.i18n.t(field.labelKey)
        : code;
    const target = this.scope.targetFor(this.level(), this.scope.brandId(), null);
    this.saved.announce(
      kind,
      label,
      target,
      kind === 'set' && isSwitch
        ? this.saved.onOff(Boolean(this.resolutionFor(code)?.value))
        : undefined,
    );
  }

  // ------------------------------------------------------------- loading

  private async reload(tenantId: string, brandId: string, code: string): Promise<void> {
    const resolution = await this.resolve(tenantId, brandId, code);
    this.resolutions.set(new Map(this.resolutions()).set(code, resolution));
  }

  private resolve(
    tenantId: string,
    brandId: string,
    code: string,
  ): Promise<ConfigurationResolutionView> {
    const level = this.level();
    return this.configApi.resolution(tenantId, code, level, level === 'BRAND' ? brandId : null);
  }

  private async loadFields(tenantId: string, brandId: string): Promise<void> {
    this.loading.set(true);
    this.loadError.set(null);
    this.editingCode.set(null);
    try {
      const codes = [ASSISTANT_ENABLED_CODE, ...DISCLOSURE_FIELDS.map((field) => field.code)];
      const entries = await Promise.all(
        codes.map(async (code) => [code, await this.resolve(tenantId, brandId, code)] as const),
      );
      this.resolutions.set(new Map(entries));
      this.denied.set(false);
    } catch (error) {
      if (error instanceof ApiError && error.status === 403) {
        this.denied.set(true);
      } else {
        this.loadError.set(this.describe(error));
      }
    } finally {
      this.loading.set(false);
    }
  }

  private async loadUsage(): Promise<void> {
    await this.tenant.ensureLoaded();
    const tenantId = this.tenant.tenantId();
    if (!tenantId) {
      return;
    }
    try {
      this.usage.set(await this.assistantApi.usage(tenantId));
      this.usageDenied.set(false);
      this.usageError.set(null);
    } catch (error) {
      if (error instanceof ApiError && error.status === 403) {
        this.usageDenied.set(true);
      } else {
        this.usageError.set(this.describe(error));
      }
    }
  }

  private describe(error: unknown): string {
    if (error instanceof ApiError) {
      return describeApiError(error, (key, values) => this.i18n.t(key, values));
    }
    return this.i18n.t('error.unknown.noReference');
  }
}
