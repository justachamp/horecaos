import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';

import { ApiError } from '../../../core/api/problem-details';
import { CurrentLocation } from '../../../core/auth/current-location';
import { I18n } from '../../../core/i18n/i18n';
import { LocaleSet } from '../../../core/i18n/locale-set';
import { MessageKey } from '../../../core/i18n/messages.en';
import { TPipe } from '../../../core/i18n/t.pipe';
import { LocalizedFieldGroup } from '../../../shared/ui/localized-field-group';
import { InstallationView, IntegrationsApi } from '../integrations/integrations-api';
import { describeApiError } from '../../orders/order-errors';
import {
  CreatePaymentMethodRequest,
  PAYMENT_METHOD_RESPONSIBILITIES,
  PaymentMethodResponsibility,
  PaymentMethodView,
  PaymentMethodsApi,
  UpdatePaymentMethodRequest,
} from './payment-methods-api';

/**
 * 10.6 Payment methods — `docs/operations-spec/settings.md` §10.6, ADR 0038.
 *
 * `payments.payment_methods` has never had a caller on any surface: every row
 * that exists today was created lazily, mid-checkout, from whatever a tenant
 * happened to tender ("the registry only ever grows by accident"). This is
 * the door — register, rename, localize, icon, order, activate, disable, and
 * bind an acquirer installation — and the tenant-scoped list row 10.4b's
 * channel matrix reads its columns from instead of a frontend constant.
 *
 * **Row 10.12 — the localized names are per the brand's own languages, not a
 * fixed ru/uz/en triple.** The tabs are {@link LocaleSet}'s locales (default
 * first, the platform triple for a brand that has chosen none), the default
 * one is marked and is where the editor opens — never the operator's own
 * console language, which says nothing about the brand. `PUT .../translations`
 * replaces the whole set, so a name in a language the tabs do not show is sent
 * back unchanged with every save ({@link editTranslations} starts from every
 * stored name, and only the visible fields are ever edited): narrowing a
 * brand's languages later cannot delete a translation. The registry is
 * tenant-wide, so a tenant with several brands sees the operator's own
 * brand's languages here — a menu, not a constraint, for the reason above.
 */
@Component({
  selector: 'q-payment-methods-page',
  imports: [TPipe, LocalizedFieldGroup],
  templateUrl: './payment-methods-page.html',
  styleUrl: './payment-methods-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PaymentMethodsPage {
  private readonly api = inject(PaymentMethodsApi);
  private readonly integrations = inject(IntegrationsApi);
  private readonly location = inject(CurrentLocation);
  private readonly localeSet = inject(LocaleSet);
  protected readonly i18n = inject(I18n);

  protected readonly loading = signal(true);
  protected readonly denied = signal(false);
  protected readonly loadError = signal<string | null>(null);
  protected readonly methods = signal<readonly PaymentMethodView[]>([]);
  protected readonly installations = signal<readonly InstallationView[]>([]);

  protected readonly responsibilities = PAYMENT_METHOD_RESPONSIBILITIES;
  /** Row 10.12: the brand's own languages, default first (the platform triple when it has chosen none). */
  protected readonly locales = computed(() => this.localeSet.locales());
  protected readonly defaultLocale = computed(() => this.localeSet.defaultLocale());

  protected readonly showCreateForm = signal(false);
  protected readonly createSubmitting = signal(false);
  protected readonly createError = signal<string | null>(null);
  protected readonly newCode = signal('');
  protected readonly newDisplayName = signal('');
  protected readonly newResponsibility = signal<PaymentMethodResponsibility>('PARTNER');
  protected readonly newIcon = signal('');
  protected readonly newInstallationId = signal('');

  protected readonly selectedId = signal<string | null>(null);
  protected readonly editDisplayName = signal('');
  protected readonly editIcon = signal('');
  protected readonly editSortOrder = signal(0);
  protected readonly editInstallationId = signal('');
  protected readonly editContractReference = signal('');
  protected readonly editLocale = signal<string>(this.localeSet.defaultLocale());
  protected readonly editTranslations = signal<Record<string, string>>({});
  protected readonly rowSaving = signal(false);
  protected readonly rowError = signal<string | null>(null);

  constructor() {
    void this.load();
  }

  protected readonly installationName = computed(() => {
    const byId = new Map(
      this.installations().map((installation) => [installation.id, installation.displayName]),
    );
    return (id: string | null) => (id ? (byId.get(id) ?? id) : null);
  });

  protected readonly translationCompleteness = computed<Readonly<Record<string, boolean>>>(() => {
    const values = this.editTranslations();
    const result: Record<string, boolean> = {};
    for (const locale of this.locales()) {
      result[locale] = (values[locale] ?? '').trim().length > 0;
    }
    return result;
  });

  /** Languages this method has a name in that the tabs do not offer — kept on save, never shown. */
  protected readonly hiddenLocales = computed<readonly string[]>(() => {
    const offered = new Set<string>(this.locales());
    return Object.entries(this.editTranslations())
      .filter(([locale, value]) => !offered.has(locale) && value.trim().length > 0)
      .map(([locale]) => locale);
  });

  protected responsibilityKey(responsibility: PaymentMethodResponsibility): MessageKey {
    return `settings.paymentMethods.responsibility.${responsibility}` as MessageKey;
  }

  protected canCreate(): boolean {
    return (
      !this.createSubmitting() &&
      this.newCode().trim().length > 0 &&
      this.newDisplayName().trim().length > 0
    );
  }

  protected async submitCreate(): Promise<void> {
    const scope = this.location.scope();
    if (!scope || !this.canCreate()) {
      return;
    }
    this.createSubmitting.set(true);
    this.createError.set(null);
    const request: CreatePaymentMethodRequest = {
      code: this.newCode().trim().toUpperCase(),
      displayName: this.newDisplayName().trim(),
      responsibility: this.newResponsibility(),
      icon: this.newIcon().trim() || null,
      sortOrder: this.methods().length,
      providerInstallationId: this.newInstallationId() || null,
    };
    try {
      await this.api.create(scope, request);
      this.showCreateForm.set(false);
      this.newCode.set('');
      this.newDisplayName.set('');
      this.newIcon.set('');
      this.newInstallationId.set('');
      await this.reload(scope);
    } catch (error) {
      this.createError.set(this.describe(error));
    } finally {
      this.createSubmitting.set(false);
    }
  }

  protected selectMethod(method: PaymentMethodView): void {
    if (this.selectedId() === method.id) {
      this.selectedId.set(null);
      return;
    }
    this.selectedId.set(method.id);
    this.editDisplayName.set(method.displayName);
    this.editIcon.set(method.icon ?? '');
    this.editSortOrder.set(method.sortOrder);
    this.editInstallationId.set(method.providerInstallationId ?? '');
    this.editContractReference.set(method.contractReference ?? '');
    this.editTranslations.set({ ...method.localizedNames });
    this.editLocale.set(this.defaultLocale());
    this.rowError.set(null);
  }

  protected async saveEdit(method: PaymentMethodView): Promise<void> {
    const scope = this.location.scope();
    if (!scope) {
      return;
    }
    this.rowSaving.set(true);
    this.rowError.set(null);
    const request: UpdatePaymentMethodRequest = {
      displayName: this.editDisplayName().trim(),
      icon: this.editIcon().trim() || null,
      sortOrder: this.editSortOrder(),
      providerInstallationId: this.editInstallationId() || null,
      contractReference: this.editContractReference().trim() || null,
    };
    try {
      await this.api.update(scope, method.id, request, method.version);
      const translations = this.editTranslations();
      const nonEmpty = Object.fromEntries(
        Object.entries(translations).filter(([, value]) => value.trim().length > 0),
      );
      await this.api.replaceTranslations(scope, method.id, nonEmpty);
      await this.reload(scope);
      this.selectedId.set(null);
    } catch (error) {
      this.rowError.set(this.describe(error));
    } finally {
      this.rowSaving.set(false);
    }
  }

  protected setTranslation(locale: string, value: string): void {
    this.editTranslations.update((current) => ({ ...current, [locale]: value }));
  }

  protected async toggleStatus(method: PaymentMethodView): Promise<void> {
    const scope = this.location.scope();
    if (!scope) {
      return;
    }
    try {
      if (method.status === 'ACTIVE') {
        await this.api.disable(scope, method.id, method.version);
      } else {
        await this.api.activate(scope, method.id, method.version);
      }
      await this.reload(scope);
    } catch (error) {
      this.loadError.set(this.describe(error));
    }
  }

  private async load(): Promise<void> {
    this.loading.set(true);
    // The brand's language set is independent of the location scope, so it
    // resolves alongside it; without this the tabs never advance past
    // `LocaleSet`'s platform fallback (`locale-set.ts`'s own doc).
    await Promise.all([this.location.ensureLoaded(), this.localeSet.ensureLoaded()]);
    const scope = this.location.scope();
    if (!scope) {
      this.denied.set(this.location.denied());
      this.loading.set(false);
      return;
    }
    try {
      const [methods, installations] = await Promise.all([
        this.api.list(scope),
        this.integrations.listInstallations(scope),
      ]);
      this.methods.set(methods);
      this.installations.set(installations);
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

  private async reload(scope: NonNullable<ReturnType<CurrentLocation['scope']>>): Promise<void> {
    this.methods.set(await this.api.list(scope));
  }

  private describe(error: unknown): string {
    if (error instanceof ApiError) {
      return describeApiError(error, (key, values) => this.i18n.t(key, values));
    }
    return this.i18n.t('error.unknown.noReference');
  }
}
