import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { ActivatedRoute } from '@angular/router';

import { asDate } from '../../core/api/dates';
import { ApiError } from '../../core/api/problem';
import { SessionContextService } from '../../core/auth/session-context.service';
import { I18nService } from '../../core/i18n/i18n.service';
import { MessageKey } from '../../core/i18n/messages.en';
import { TenantDirectory } from '../../shared/tenant-directory';
import { TenantPicker } from '../../shared/tenant-picker';
import { LegalEntityView, TenantsApi } from '../tenants/tenants-api';
import {
  CommerceApi,
  EINVOICING_OPERATORS,
  EInvoiceView,
  EInvoicingAccountView,
  EInvoicingClassificationView,
  StatementView,
} from './commerce-api';

/** The seller's public identity and the login language: what an account carries besides its secret reference. */
export const ACCOUNT_CONFIG_FIELDS = [
  'sellerTaxpayerNumber',
  'sellerName',
  'sellerAddress',
  'sellerVatRegistrationCode',
  'sellerBankAccount',
  'sellerBankCode',
  'sellerBankName',
  'locale',
] as const;

export type AccountConfigField = (typeof ACCOUNT_CONFIG_FIELDS)[number];

interface AccountForm {
  readonly displayName: string;
  /** Blank keeps the reference on file: the server never tells this screen what it is. */
  readonly secretReference: string;
  readonly clearSecret: boolean;
  readonly config: Readonly<Record<AccountConfigField, string>>;
  readonly reason: string;
}

interface ClassificationForm {
  readonly itemLabel: string;
  readonly catalogCode: string;
  readonly catalogName: string;
  readonly packageCode: string;
  readonly packageName: string;
  readonly vatPercent: string;
  readonly confirmed: boolean;
  readonly reason: string;
}

interface SendForm {
  readonly provider: string;
  readonly legalEntityId: string;
  readonly reason: string;
}

/** A statement the tenant has been issued, with every attempt to send it to an operator. */
interface SentStatement {
  readonly statement: StatementView;
  readonly attempts: readonly EInvoiceView[];
}

/** Whether a reason has been given: every change here is audited, and an audit entry needs one. */
function given(text: string): boolean {
  return text.trim().length > 0;
}

/** A VAT percentage as typed ("12", "2.5") in basis points, or null when it is not one between 0 and 100. */
export function basisPoints(text: string): number | null {
  const trimmed = text.trim().replace(',', '.');
  if (!/^\d{1,3}(\.\d{1,2})?$/.test(trimmed)) {
    return null;
  }
  const value = Math.round(Number(trimmed) * 100);
  return value >= 0 && value <= 10_000 ? value : null;
}

/** Basis points as the percentage a person types: 1200 is "12", 250 is "2.5". */
export function percentOf(basisPoints: number): string {
  return String(basisPoints / 100);
}

/**
 * E-invoicing (ADR 0096) -- HorecaOS's own accounts with Didox and Faktura.uz, what each kind of
 * statement line is invoiced as, and the statements sent to an operator.
 *
 * HorecaOS has no account with either operator yet, and this screen says so rather than hiding
 * the action: an account is shown as not connected, with what it still needs, and sending through
 * it is refused until it is bound and connected. The login itself is put in the secrets manager by
 * an operator of the platform; this screen only ever takes the reference to it, and never shows
 * one back.
 *
 * Sending is a deliberate act per statement, never automatic at issue. A document goes out as a
 * draft; somebody at HorecaOS signs and sends it in the operator's own product, and what the
 * operator reports (sent, signed by the buyer, refused) is shown here. A send whose answer was
 * lost is resolved by asking the operator, never by sending again.
 */
@Component({
  selector: 'app-e-invoicing',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TenantPicker],
  templateUrl: './e-invoicing.html',
  styleUrls: ['./plan-catalog.css', './e-invoicing.css'],
})
export class EInvoicing {
  protected readonly i18n = inject(I18nService);
  protected readonly asDate = asDate;
  protected readonly session = inject(SessionContextService);
  private readonly api = inject(CommerceApi);
  private readonly tenantsApi = inject(TenantsApi);
  private readonly directory = inject(TenantDirectory);
  private readonly route = inject(ActivatedRoute);

  protected readonly operators = EINVOICING_OPERATORS;
  protected readonly configFields = ACCOUNT_CONFIG_FIELDS;

  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly accounts = signal<readonly EInvoicingAccountView[]>([]);
  protected readonly classifications = signal<readonly EInvoicingClassificationView[]>([]);

  protected readonly busy = signal(false);
  protected readonly message = signal<string | null>(null);
  protected readonly failure = signal<string | null>(null);

  // ------------------------------------------------------------ accounts

  protected readonly editingAccount = signal<string | null>(null);
  protected readonly accountForm = signal<AccountForm>(blankAccountForm());
  /** An activation or suspension waiting for its reason. */
  protected readonly moving = signal<{
    readonly id: string;
    readonly to: 'activate' | 'suspend';
  } | null>(null);
  protected readonly moveReason = signal('');

  protected readonly canManage = computed(() => this.session.has('COMMERCIAL_EINVOICING_MANAGE'));
  protected readonly canSend = computed(() => this.session.has('COMMERCIAL_EINVOICE_SEND'));

  // ----------------------------------------------------- classifications

  protected readonly editingKind = signal<string | null>(null);
  protected readonly classForm = signal<ClassificationForm>(blankClassificationForm());

  // ---------------------------------------------------------- statements

  protected readonly tenantId = signal(
    this.route.snapshot.queryParamMap.get('tenantId') ?? this.directory.selected(),
  );
  protected readonly tenantLoading = signal(false);
  protected readonly tenantError = signal<string | null>(null);
  protected readonly statements = signal<readonly SentStatement[]>([]);
  protected readonly legalEntities = signal<readonly LegalEntityView[]>([]);
  protected readonly sending = signal<string | null>(null);
  protected readonly sendForm = signal<SendForm>({
    provider: 'DIDOX',
    legalEntityId: '',
    reason: '',
  });
  protected readonly detail = signal<EInvoiceView | null>(null);

  constructor() {
    void this.load();
  }

  // ------------------------------------------------------------- loading

  protected async load(): Promise<void> {
    this.loading.set(true);
    this.loadError.set(null);
    try {
      const [accounts, classifications] = await Promise.all([
        this.api.einvoicingAccounts(),
        this.api.einvoicingClassifications(),
      ]);
      this.accounts.set(accounts);
      this.classifications.set(classifications);
    } catch (error) {
      this.loadError.set(this.describe(error));
    } finally {
      this.loading.set(false);
    }
    if (this.tenantId().length > 0) {
      await this.loadTenant();
    }
  }

  protected chooseTenant(tenantId: string): void {
    this.tenantId.set(tenantId);
    this.sending.set(null);
    this.detail.set(null);
    this.statements.set([]);
    if (tenantId.length > 0) {
      void this.loadTenant();
    }
  }

  private async loadTenant(): Promise<void> {
    this.tenantLoading.set(true);
    this.tenantError.set(null);
    const tenantId = this.tenantId();
    try {
      const [statements, einvoices, entities] = await Promise.all([
        this.api.listStatements(tenantId),
        this.api.tenantEInvoices(tenantId),
        this.tenantsApi.getLegalEntities(tenantId),
      ]);
      this.statements.set(
        statements
          .filter((statement) => statement.status === 'ISSUED')
          .map((statement) => ({
            statement,
            attempts: einvoices.filter((attempt) => attempt.statementId === statement.statementId),
          })),
      );
      this.legalEntities.set(entities.filter((entity) => entity.status === 'ACTIVE'));
    } catch (error) {
      this.statements.set([]);
      this.tenantError.set(this.describe(error));
    } finally {
      this.tenantLoading.set(false);
    }
  }

  // ------------------------------------------------------------ accounts

  protected accountOf(provider: string): EInvoicingAccountView | null {
    return this.accounts().find((account) => account.provider === provider) ?? null;
  }

  protected providerLabel(provider: string): string {
    const key = `einvoicing.account.provider.${provider}`;
    return this.i18n.hasMessage(key) ? this.i18n.t(key) : provider;
  }

  protected statusKey(status: string): MessageKey {
    return `einvoicing.account.status.${status}` as MessageKey;
  }

  protected needKey(need: string): MessageKey {
    return `einvoicing.account.needs.${need}` as MessageKey;
  }

  protected fieldKey(field: AccountConfigField): MessageKey {
    return `einvoicing.account.field.${field}` as MessageKey;
  }

  protected editAccount(account: EInvoicingAccountView): void {
    if (this.editingAccount() === account.installationId) {
      this.editingAccount.set(null);
      return;
    }
    this.editingAccount.set(account.installationId);
    this.accountForm.set({
      displayName: account.displayName,
      secretReference: '',
      clearSecret: false,
      config: Object.fromEntries(
        ACCOUNT_CONFIG_FIELDS.map((field) => [field, account.config[field] ?? '']),
      ) as Record<AccountConfigField, string>,
      reason: '',
    });
    this.moving.set(null);
    this.resetNotices();
  }

  protected setAccountField(
    field: 'displayName' | 'secretReference' | 'reason',
    value: string,
  ): void {
    this.accountForm.update((form) => ({ ...form, [field]: value }));
  }

  protected setClearSecret(clear: boolean): void {
    this.accountForm.update((form) => ({ ...form, clearSecret: clear }));
  }

  protected setConfigField(field: AccountConfigField, value: string): void {
    this.accountForm.update((form) => ({ ...form, config: { ...form.config, [field]: value } }));
  }

  protected canSaveAccount(): boolean {
    const form = this.accountForm();
    return !this.busy() && given(form.reason) && given(form.displayName);
  }

  protected async saveAccount(event: Event, account: EInvoicingAccountView): Promise<void> {
    event.preventDefault();
    if (!this.canSaveAccount()) {
      return;
    }
    const form = this.accountForm();
    const config: Record<string, string> = {};
    for (const field of ACCOUNT_CONFIG_FIELDS) {
      const value = form.config[field].trim();
      if (value.length > 0) {
        config[field] = value;
      }
    }
    await this.run(async () => {
      await this.api.saveEInvoicingAccount(account.installationId, account.version, {
        displayName: form.displayName.trim(),
        // Absent keeps the reference on file; blank clears it; text replaces it.
        secretReference: form.clearSecret
          ? ''
          : given(form.secretReference)
            ? form.secretReference.trim()
            : null,
        config,
        reason: form.reason.trim(),
      });
      this.editingAccount.set(null);
      return this.i18n.t('einvoicing.account.saved', {
        operator: this.providerLabel(account.provider),
      });
    });
  }

  protected startMove(account: EInvoicingAccountView, to: 'activate' | 'suspend'): void {
    const current = this.moving();
    this.moving.set(
      current?.id === account.installationId && current.to === to
        ? null
        : { id: account.installationId, to },
    );
    this.moveReason.set('');
    this.editingAccount.set(null);
    this.resetNotices();
  }

  protected async confirmMove(account: EInvoicingAccountView): Promise<void> {
    const move = this.moving();
    const reason = this.moveReason().trim();
    if (move === null || move.id !== account.installationId || reason.length === 0 || this.busy()) {
      return;
    }
    await this.run(async () => {
      if (move.to === 'activate') {
        await this.api.activateEInvoicingAccount(account.installationId, account.version, reason);
      } else {
        await this.api.suspendEInvoicingAccount(account.installationId, account.version, reason);
      }
      this.moving.set(null);
      return this.i18n.t(
        move.to === 'activate' ? 'einvoicing.account.activated' : 'einvoicing.account.suspended',
        { operator: this.providerLabel(account.provider) },
      );
    });
  }

  // ------------------------------------------------------ classifications

  protected kindKey(kind: string): MessageKey {
    return `statements.kind.${kind}` as MessageKey;
  }

  protected editClassification(row: EInvoicingClassificationView): void {
    if (this.editingKind() === row.lineKind) {
      this.editingKind.set(null);
      return;
    }
    this.editingKind.set(row.lineKind);
    this.classForm.set({
      itemLabel: row.itemLabel,
      catalogCode: row.catalogCode,
      catalogName: row.catalogName,
      packageCode: row.packageCode,
      packageName: row.packageName,
      vatPercent: percentOf(row.vatRateBp),
      confirmed: false,
      reason: '',
    });
    this.resetNotices();
  }

  protected setClassField(field: keyof ClassificationForm, value: string | boolean): void {
    this.classForm.update((form) => ({ ...form, [field]: value }));
  }

  protected canSaveClassification(): boolean {
    const form = this.classForm();
    return (
      !this.busy() &&
      given(form.reason) &&
      given(form.itemLabel) &&
      given(form.catalogCode) &&
      given(form.catalogName) &&
      given(form.packageCode) &&
      given(form.packageName) &&
      basisPoints(form.vatPercent) !== null
    );
  }

  protected async saveClassification(
    event: Event,
    row: EInvoicingClassificationView,
  ): Promise<void> {
    event.preventDefault();
    const form = this.classForm();
    const vatRateBp = basisPoints(form.vatPercent);
    if (!this.canSaveClassification() || vatRateBp === null) {
      return;
    }
    await this.run(async () => {
      await this.api.saveEInvoicingClassification(row.lineKind, row.version, {
        itemLabel: form.itemLabel.trim(),
        catalogCode: form.catalogCode.trim(),
        catalogName: form.catalogName.trim(),
        packageCode: form.packageCode.trim(),
        packageName: form.packageName.trim(),
        vatRateBp,
        confirmed: form.confirmed,
        reason: form.reason.trim(),
      });
      this.editingKind.set(null);
      return this.i18n.t('einvoicing.classification.saved', {
        kind: this.i18n.t(this.kindKey(row.lineKind)),
      });
    });
  }

  // ------------------------------------------------------------ statements

  protected deliveryKey(delivery: string): MessageKey {
    return `einvoicing.delivery.${delivery}` as MessageKey;
  }

  protected stateKey(state: string): MessageKey {
    return `einvoicing.state.${state}` as MessageKey;
  }

  /** Whether the operator has settled this document: signed, refused or cancelled is final, and asking again is refused. */
  protected settled(attempt: EInvoiceView): boolean {
    return (
      attempt.operatorState === 'SIGNED' ||
      attempt.operatorState === 'REFUSED' ||
      attempt.operatorState === 'CANCELLED'
    );
  }

  /** The newest attempt that is standing as the statement's invoice, if any. */
  protected live(entry: SentStatement): EInvoiceView | null {
    return entry.attempts.find((attempt) => attempt.live) ?? null;
  }

  /** Whether any line kind is still invoiced under a classification finance has not confirmed. */
  protected classificationsProvisional(): boolean {
    return this.classifications().some((row) => row.provisional);
  }

  protected percentOf = percentOf;

  protected openSend(entry: SentStatement): void {
    const id = entry.statement.statementId;
    if (id === null || this.sending() === id) {
      this.sending.set(null);
      return;
    }
    this.sending.set(id);
    const connected =
      this.operators.find((operator) => this.accountOf(operator)?.connected) ?? 'DIDOX';
    this.sendForm.set({
      provider: connected,
      legalEntityId: this.legalEntities().length === 1 ? this.legalEntities()[0].id : '',
      reason: '',
    });
    this.resetNotices();
  }

  protected setSendField(field: keyof SendForm, value: string): void {
    this.sendForm.update((form) => ({ ...form, [field]: value }));
  }

  /** Whether the chosen operator has a connected account: a send through one that has not is refused. */
  protected providerConnected(provider: string): boolean {
    return this.accountOf(provider)?.connected === true;
  }

  protected canSubmitSend(entry: SentStatement): boolean {
    const form = this.sendForm();
    const needsBuyer = this.legalEntities().length > 1 && form.legalEntityId === '';
    return (
      !this.busy() && entry.statement.statementId !== null && given(form.reason) && !needsBuyer
    );
  }

  protected async send(event: Event, entry: SentStatement): Promise<void> {
    event.preventDefault();
    const statementId = entry.statement.statementId;
    if (statementId === null || !this.canSubmitSend(entry)) {
      return;
    }
    const form = this.sendForm();
    await this.run(async () => {
      const sent = await this.api.sendEInvoice(this.tenantId(), statementId, {
        provider: form.provider,
        legalEntityId: form.legalEntityId === '' ? null : form.legalEntityId,
        reason: form.reason.trim(),
      });
      this.sending.set(null);
      await this.loadTenant();
      return this.describeSend(sent);
    });
  }

  private describeSend(sent: EInvoiceView): string {
    const operator = this.providerLabel(sent.provider);
    if (sent.delivery === 'FAILED') {
      return this.i18n.t('einvoicing.send.failed', { code: sent.failureCode ?? '' });
    }
    if (sent.delivery === 'UNCERTAIN') {
      return this.i18n.t('einvoicing.send.uncertain');
    }
    return this.i18n.t('einvoicing.send.done', {
      operator,
      state: this.i18n.t(this.stateKey(sent.operatorState ?? 'UNKNOWN')),
    });
  }

  protected async refresh(attempt: EInvoiceView): Promise<void> {
    await this.run(async () => {
      const answer = await this.api.refreshEInvoice(this.tenantId(), attempt.einvoiceId);
      await this.loadTenant();
      return answer.unavailableCode === null
        ? this.i18n.t('einvoicing.refreshed')
        : this.i18n.t('einvoicing.refresh.unavailable', { code: answer.unavailableCode });
    });
  }

  protected async showDetail(attempt: EInvoiceView): Promise<void> {
    if (this.detail()?.einvoiceId === attempt.einvoiceId) {
      this.detail.set(null);
      return;
    }
    try {
      this.detail.set(await this.api.eInvoice(this.tenantId(), attempt.einvoiceId));
    } catch (error) {
      this.failure.set(this.describe(error));
    }
  }

  // --------------------------------------------------------------- common

  private resetNotices(): void {
    this.message.set(null);
    this.failure.set(null);
  }

  private async run(action: () => Promise<string>): Promise<void> {
    this.busy.set(true);
    this.resetNotices();
    try {
      this.message.set(await action());
      const [accounts, classifications] = await Promise.all([
        this.api.einvoicingAccounts(),
        this.api.einvoicingClassifications(),
      ]);
      this.accounts.set(accounts);
      this.classifications.set(classifications);
    } catch (error) {
      this.failure.set(this.describe(error));
    } finally {
      this.busy.set(false);
    }
  }

  /**
   * A refusal says why in the words of this screen: the server names the reason ("the operator
   * is not connected", "the tenant has no legal entity") beside its error code, and a generic
   * "cannot be done in this state" would leave the person who pressed send guessing.
   */
  private describe(error: unknown): string {
    const failed = error as ApiError;
    const reason = failed.problem?.['reason'];
    if (typeof reason === 'string') {
      const key = `einvoicing.error.${reason}`;
      if (this.i18n.hasMessage(key)) {
        return this.i18n.t(key);
      }
    }
    return this.i18n.describe(failed);
  }
}

function blankAccountForm(): AccountForm {
  return {
    displayName: '',
    secretReference: '',
    clearSecret: false,
    config: Object.fromEntries(ACCOUNT_CONFIG_FIELDS.map((field) => [field, ''])) as Record<
      AccountConfigField,
      string
    >,
    reason: '',
  };
}

function blankClassificationForm(): ClassificationForm {
  return {
    itemLabel: '',
    catalogCode: '',
    catalogName: '',
    packageCode: '',
    packageName: '',
    vatPercent: '',
    confirmed: false,
    reason: '',
  };
}
