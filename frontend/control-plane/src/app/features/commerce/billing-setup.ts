import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';

import { asDate } from '../../core/api/dates';
import { ApiError } from '../../core/api/problem';
import { SessionContextService } from '../../core/auth/session-context.service';
import { I18nService } from '../../core/i18n/i18n.service';
import {
  BankDetailsView,
  CardInstallationView,
  CommerceApi,
  WalletChangeResponse,
} from './commerce-api';

/** Which of the two forms is open; one at a time, like the wallet's. */
export type BillingForm = 'bankDetails' | 'installation';

/** What an installation may be moved to from where it is. */
export type InstallationMove = 'activation' | 'suspension';

/**
 * IA 5.5 Invoices & wallet, platform half -- what HorecaOS itself sets up so it can be paid (ADR 0095).
 *
 * Two things, and neither is per tenant:
 *
 * **The bank details every invoice carries.** They start as a placeholder, and no tenant is handed an
 * invoice until they are replaced: an invoice is a document a payer acts on without checking, so a
 * placeholder in that place is a refused request and not a document that tells a payer to pay a sentence.
 * Replacing them is proposed by one person and approved by a different one under Approvals, like every
 * other decision HorecaOS takes about money (decision 4): the first call answers "waiting", the approver
 * reads the whole proposal (the account number is what is being signed; only this person's reason is
 * withheld), and the identical proposal submitted again afterwards writes it. The form therefore stays
 * filled after a proposal, so that second step is one click. Invoices already issued keep the details of
 * their own moment; nothing on this screen rewrites one.
 *
 * **HorecaOS's own card merchant account**, as an ADR 0026 installation. Its credential is only ever a
 * reference to a secret in the secrets manager, never a value, and the server never returns even that
 * reference: `secretConfigured` is all there is to show. At most one is active; with none active every
 * card tenant is collected like an invoice tenant, which this screen says in so many words rather than
 * leaving a finance reader to infer it. Only a test double has an adapter in this build, and the server
 * refuses to activate it outside a local or test run, so nothing real can be switched on from here until
 * the owner's merchant account and its adapter exist (`docs/runbooks/connect-card-merchant-account.md`).
 *
 * Reads are `commercial.wallet.read` (bank details) and `integration.installation.manage` (the account);
 * a reader without the second sees the first only, and is told so.
 */
@Component({
  selector: 'app-billing-setup',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink],
  templateUrl: './billing-setup.html',
  styleUrls: ['./plan-catalog.css', './billing-setup.css'],
})
export class BillingSetup {
  protected readonly i18n = inject(I18nService);
  protected readonly asDate = asDate;
  protected readonly session = inject(SessionContextService);
  private readonly api = inject(CommerceApi);

  protected readonly loading = signal(true);
  protected readonly bankError = signal<string | null>(null);
  protected readonly installationsError = signal<string | null>(null);
  protected readonly bank = signal<BankDetailsView | null>(null);
  protected readonly installations = signal<readonly CardInstallationView[]>([]);

  protected readonly busy = signal(false);
  protected readonly actionError = signal<string | null>(null);
  protected readonly actionMessage = signal<string | null>(null);
  protected readonly form = signal<BillingForm | null>(null);

  protected readonly beneficiary = signal('');
  protected readonly bankName = signal('');
  protected readonly account = signal('');
  protected readonly mfo = signal('');
  protected readonly taxId = signal('');
  protected readonly reason = signal('');

  protected readonly providerType = signal('');
  protected readonly environmentCode = signal('');
  protected readonly displayName = signal('');
  protected readonly secretReference = signal('');
  protected readonly externalAccountReference = signal('');

  /** The installation a move is open on, with the move; the reason is typed beside it. */
  protected readonly moving = signal<{
    readonly id: string;
    readonly move: InstallationMove;
  } | null>(null);
  protected readonly moveReason = signal('');

  protected readonly mayReadAccounts = computed(() =>
    this.session.has('INTEGRATION_INSTALLATION_MANAGE'),
  );
  protected readonly active = computed(
    () => this.installations().find((installation) => installation.status === 'ACTIVE') ?? null,
  );

  constructor() {
    void this.load();
  }

  private async load(): Promise<void> {
    this.loading.set(true);
    this.bankError.set(null);
    this.installationsError.set(null);
    const [bank, installations] = await Promise.allSettled([
      this.api.bankDetails(),
      this.mayReadAccounts() ? this.api.cardInstallations() : Promise.resolve([]),
    ]);
    if (bank.status === 'fulfilled') {
      this.bank.set(bank.value);
    } else {
      this.bank.set(null);
      this.bankError.set(this.i18n.describe(bank.reason as ApiError));
    }
    if (installations.status === 'fulfilled') {
      this.installations.set(installations.value);
    } else {
      this.installations.set([]);
      this.installationsError.set(this.i18n.describe(installations.reason as ApiError));
    }
    this.loading.set(false);
  }

  protected installationStatusLabel(status: string): string {
    const key = `billing.installation.status.${status}`;
    return this.i18n.hasMessage(key) ? this.i18n.t(key) : status;
  }

  // -------------------------------------------------------- bank details

  protected openForm(form: BillingForm): void {
    this.form.set(this.form() === form ? null : form);
    this.actionError.set(null);
    this.actionMessage.set(null);
    this.moving.set(null);
  }

  protected canProposeBankDetails(): boolean {
    return (
      !this.busy() &&
      [
        this.beneficiary(),
        this.bankName(),
        this.account(),
        this.mfo(),
        this.taxId(),
        this.reason(),
      ].every((value) => value.trim().length > 0)
    );
  }

  protected async proposeBankDetails(event: Event): Promise<void> {
    event.preventDefault();
    if (!this.canProposeBankDetails()) {
      return;
    }
    await this.run(async () => {
      const outcome = await this.api.proposeBankDetails({
        beneficiary: this.beneficiary().trim(),
        bankName: this.bankName().trim(),
        account: this.account().trim(),
        mfo: this.mfo().trim(),
        taxId: this.taxId().trim(),
        reason: this.reason().trim(),
      });
      return this.describeChange(outcome);
    });
  }

  /**
   * Nothing has moved while a proposal is waiting: the form stays as typed, so that once a different
   * person has approved it the same person submits the identical proposal again and it is applied.
   */
  private describeChange(outcome: WalletChangeResponse): string {
    if (outcome.status === 'CHANGED') {
      this.form.set(null);
      this.reason.set('');
      return this.i18n.t('billing.bank.changed');
    }
    if (outcome.status === 'DECLINED') {
      return this.i18n.t('billing.bank.declined');
    }
    return this.i18n.t('billing.bank.awaiting');
  }

  // ------------------------------------------------------ card account

  protected canCreateInstallation(): boolean {
    return (
      !this.busy() &&
      this.providerType().trim().length > 0 &&
      this.displayName().trim().length > 0 &&
      this.reason().trim().length > 0
    );
  }

  protected async createInstallation(event: Event): Promise<void> {
    event.preventDefault();
    if (!this.canCreateInstallation()) {
      return;
    }
    await this.run(async () => {
      await this.api.createCardInstallation({
        providerType: this.providerType().trim(),
        displayName: this.displayName().trim(),
        reason: this.reason().trim(),
        // Absent, not empty: the server reads an empty string as a value to validate.
        ...(this.environmentCode().trim()
          ? { environmentCode: this.environmentCode().trim() }
          : {}),
        ...(this.secretReference().trim()
          ? { secretReference: this.secretReference().trim() }
          : {}),
        ...(this.externalAccountReference().trim()
          ? { externalAccountReference: this.externalAccountReference().trim() }
          : {}),
      });
      this.form.set(null);
      this.reason.set('');
      return this.i18n.t('billing.installation.created');
    });
  }

  /** Activate from DRAFT or SUSPENDED; suspend from ACTIVE. Anything else has no move. */
  protected moveFor(installation: CardInstallationView): InstallationMove | null {
    switch (installation.status) {
      case 'DRAFT':
      case 'SUSPENDED':
        return 'activation';
      case 'ACTIVE':
        return 'suspension';
      default:
        return null;
    }
  }

  protected openMove(installation: CardInstallationView, move: InstallationMove): void {
    const current = this.moving();
    this.moving.set(
      current?.id === installation.installationId && current.move === move
        ? null
        : { id: installation.installationId, move },
    );
    this.moveReason.set('');
    this.actionError.set(null);
    this.actionMessage.set(null);
    this.form.set(null);
  }

  protected canMove(): boolean {
    return this.moving() !== null && !this.busy() && this.moveReason().trim().length > 0;
  }

  protected async confirmMove(installation: CardInstallationView): Promise<void> {
    const move = this.moving();
    if (move === null || !this.canMove()) {
      return;
    }
    const reason = this.moveReason().trim();
    await this.run(async () => {
      if (move.move === 'activation') {
        await this.api.activateCardInstallation(
          installation.installationId,
          installation.version,
          reason,
        );
      } else {
        await this.api.suspendCardInstallation(
          installation.installationId,
          installation.version,
          reason,
        );
      }
      this.moving.set(null);
      return this.i18n.t(
        move.move === 'activation'
          ? 'billing.installation.activated'
          : 'billing.installation.suspended',
        { name: installation.displayName },
      );
    });
  }

  private async run(write: () => Promise<string>): Promise<void> {
    this.busy.set(true);
    this.actionError.set(null);
    this.actionMessage.set(null);
    try {
      this.actionMessage.set(await write());
      await this.load();
    } catch (error) {
      this.actionError.set(this.i18n.describe(error as ApiError));
    } finally {
      this.busy.set(false);
    }
  }
}
