import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../../core/api/api-client';
import { financePaths } from '../../../core/api/finance-paths';
import { Command, command } from '../../../core/api/idempotency';
import { Money } from '../../../core/format/money';

/** `PaymentMethod.java`: how HorecaOS collects what the wallet does not cover. */
export type PaymentMethod = 'INVOICE' | 'WALLET' | 'CARD';

export const PAYMENT_METHODS: readonly PaymentMethod[] = ['INVOICE', 'WALLET', 'CARD'];

/**
 * Which card, never which reference (ADR 0028): the provider's token for it is
 * stored on HorecaOS's side and is never returned. Mirrors
 * `CommercialOperationsWalletController.CardOnFileView`.
 */
export interface CardOnFileView {
  readonly last4: string | null;
  readonly brand: string | null;
  readonly expiryMonth: number | null;
  readonly expiryYear: number | null;
  /** Read from the expiry by the server, so no screen has to do date arithmetic on a card. */
  readonly lapsed: boolean;
  readonly lapsesSoon: boolean;
  readonly boundAt: string | null;
}

/** Bonus credit that is about to lapse with something left in it. */
export interface LapsingGrantView {
  readonly grantId: string;
  readonly remaining: Money;
  readonly expiresAt: string;
}

/** `CardTopUp.java`'s four outcomes. An unrecognised one is shown as itself, never blank. */
export type TopUpOutcome = 'PENDING' | 'SUCCEEDED' | 'FAILED' | 'NOT_CONFIGURED';

/** Mirrors `CommercialOperationsWalletController.TopUpView`. */
export interface TopUpView {
  readonly topUpId: string;
  readonly amount: Money;
  readonly outcome: TopUpOutcome | (string & {});
  /** The provider's reason code, on a decline only. */
  readonly reason: string | null;
  readonly walletEntryId: string | null;
  readonly requestedAt: string;
  readonly settledAt: string | null;
}

/** Mirrors `CommercialOperationsWalletController.TenantWalletView`. */
export interface TenantWalletView {
  /** Money the tenant paid. Never lapses and is refunded when the tenant leaves. */
  readonly paidBalance: Money;
  /** The ledger's sum of every BONUS entry; stands briefly above the spendable figure after a grant expires. */
  readonly bonusBalance: Money;
  /** What the live grants have left: the bonus money a statement can actually spend. */
  readonly bonusSpendableBalance: Money;
  readonly paymentMethod: PaymentMethod | (string & {});
  readonly card: CardOnFileView | null;
  readonly lapsingGrants: readonly LapsingGrantView[];
  readonly pendingTopUp: TopUpView | null;
  /** False while HorecaOS has no card merchant account connected: every card route would refuse. */
  readonly cardPaymentsAvailable: boolean;
  /** False while HorecaOS finance has not replaced the placeholder bank details. */
  readonly bankTransferAvailable: boolean;
}

/** `WalletEntry.java`'s entry types. */
export type LedgerEntryType =
  | 'TOP_UP'
  | 'DEPOSIT'
  | 'BONUS_GRANT'
  | 'BONUS_EXPIRY'
  | 'STATEMENT_PAYMENT'
  | 'STATEMENT_REVERSAL'
  | 'ADJUSTMENT'
  | 'REFUND'
  | 'DEPOSIT_REVERSAL';

/** No `reason`: what staff typed beside a correction is theirs, not the tenant's to read (ADR 0029). */
export interface LedgerEntryView {
  readonly entryId: string;
  readonly moneyKind: 'PAID' | 'BONUS' | (string & {});
  readonly entryType: LedgerEntryType | (string & {});
  readonly amount: Money;
  readonly statementId: string | null;
  readonly grantId: string | null;
  readonly expiresAt: string | null;
  readonly createdAt: string;
}

export interface LedgerPage {
  readonly items: readonly LedgerEntryView[];
  readonly nextCursor: string | null;
}

/** One issued statement's paid and due amounts, derived from the ledger. */
export interface StatementPaymentView {
  readonly statementId: string;
  readonly number: string;
  readonly periodKey: string;
  readonly total: Money;
  readonly paid: Money;
  readonly due: Money;
}

/**
 * Where to send a bank transfer. `configured` is false while HorecaOS finance
 * has not replaced the placeholder, and the other fields then hold placeholder
 * text no screen should present as an account.
 */
export interface PaymentDetailsView {
  readonly configured: boolean;
  readonly beneficiary: string;
  readonly bankName: string;
  readonly account: string;
  readonly mfo: string;
  readonly taxId: string;
}

/** `PrepaymentInvoice.java`'s statuses; read from the ledger, never stored. */
export type InvoiceStatus = 'OPEN' | 'PARTIALLY_PAID' | 'PAID' | 'EXPIRED' | 'CANCELLED';

/** A request for payment of money to be held in the wallet. Before tax, and not a tax invoice. */
export interface PrepaymentInvoiceView {
  readonly invoiceId: string;
  readonly number: string;
  readonly status: InvoiceStatus | (string & {});
  readonly amount: Money;
  readonly paid: Money;
  readonly due: Money;
  readonly validUntil: string;
  readonly issuedAt: string;
  readonly cancelledAt: string | null;
  /** The bank details of the moment it was issued; they never change afterwards. */
  readonly paymentDetails: PaymentDetailsView;
  /** What the payer writes in the transfer's purpose, so finance can tell whose money arrived. */
  readonly paymentPurpose: string;
  readonly beforeTax: boolean;
}

/** What the provider needs the browser to show: its own form, never ours. */
export interface EnrolmentStartedView {
  readonly sessionReference: string;
  /** A page the provider hosts, when its form is a page rather than a script. */
  readonly hostedFormUrl: string | null;
  readonly clientParameters: Readonly<Record<string, string>>;
  readonly expiresAt: string;
}

export interface ConfirmCardBody {
  readonly sessionReference: string;
  /** What the provider's own form handed the browser. Never a card number. */
  readonly providerToken: string;
  /** The code the cardholder's bank texted. */
  readonly verificationCode: string;
}

/**
 * The merchant's own prepaid wallet and the ways to put money into it (ADR 0095,
 * Finance 8.6 / 8/X.3): `CommercialOperationsWalletController`.
 *
 * **The card number never reaches HorecaOS**, and so never reaches this client:
 * a tenant types it into the provider's own form and this client only forwards
 * what that form returned ({@link confirmCard}). Nor is the stored reference
 * ever read back — {@link CardOnFileView} is the last four digits, the brand
 * and the expiry.
 *
 * **Money-moving writes take a {@link Command}**, minted by the caller and held
 * for as long as the intent stands, so a retry after a lost answer goes out
 * under the same `Idempotency-Key` and the server replays its answer instead of
 * charging the card a second time. That is the one reason these methods take a
 * command and not a bare body.
 */
@Injectable({ providedIn: 'root' })
export class WalletApi {
  private readonly api = inject(ApiClient);

  async overview(tenantId: string): Promise<TenantWalletView> {
    const result = await firstValueFrom(
      this.api.get<TenantWalletView>(financePaths.commercialWallet(tenantId)),
    );
    return result.value;
  }

  /** Newest first; pass the previous page's `nextCursor` for the next one. */
  async ledger(tenantId: string, cursor?: string): Promise<LedgerPage> {
    const result = await firstValueFrom(
      this.api.get<LedgerPage>(
        financePaths.commercialWalletLedger(tenantId),
        cursor === undefined ? {} : { params: { cursor } },
      ),
    );
    return { items: result.value?.items ?? [], nextCursor: result.value?.nextCursor ?? null };
  }

  async statementPayments(tenantId: string): Promise<readonly StatementPaymentView[]> {
    const result = await firstValueFrom(
      this.api.get<readonly StatementPaymentView[]>(
        financePaths.commercialWalletStatements(tenantId),
      ),
    );
    return result.value ?? [];
  }

  async paymentDetails(tenantId: string): Promise<PaymentDetailsView> {
    const result = await firstValueFrom(
      this.api.get<PaymentDetailsView>(financePaths.commercialWalletPaymentDetails(tenantId)),
    );
    return result.value;
  }

  /** The most recent card top-ups, newest first. */
  async topUps(tenantId: string): Promise<readonly TopUpView[]> {
    const result = await firstValueFrom(
      this.api.get<readonly TopUpView[]>(financePaths.commercialWalletTopUps(tenantId)),
    );
    return result.value ?? [];
  }

  /**
   * Charges the card on file and, once the provider has answered, credits the
   * wallet. The answer's `outcome` says which of four things happened; a
   * decline is a 200 with `FAILED`, not an error.
   */
  async topUp(tenantId: string, intent: Command<{ amountMinor: number }>): Promise<TopUpView> {
    return firstValueFrom(this.api.post(financePaths.commercialWalletTopUps(tenantId), intent));
  }

  async invoices(tenantId: string): Promise<readonly PrepaymentInvoiceView[]> {
    const result = await firstValueFrom(
      this.api.get<readonly PrepaymentInvoiceView[]>(
        financePaths.commercialWalletInvoices(tenantId),
      ),
    );
    return result.value ?? [];
  }

  /** Asks for an invoice to pay money in advance by bank transfer. Refused until HorecaOS has published bank details. */
  async issueInvoice(
    tenantId: string,
    intent: Command<{ amountMinor: number }>,
  ): Promise<PrepaymentInvoiceView> {
    return firstValueFrom(this.api.post(financePaths.commercialWalletInvoices(tenantId), intent));
  }

  /** The invoice as CSV, for the accounting system a payment is made from. */
  async invoiceExport(tenantId: string, invoiceId: string): Promise<string> {
    return firstValueFrom(
      this.api.text(financePaths.commercialWalletInvoiceExport(tenantId, invoiceId)),
    );
  }

  /** Withdraws an invoice nothing has paid. */
  async cancelInvoice(tenantId: string, invoiceId: string): Promise<PrepaymentInvoiceView> {
    return firstValueFrom(
      this.api.post(financePaths.commercialWalletInvoiceCancel(tenantId, invoiceId), command(null)),
    );
  }

  /** Opens a session with the card provider. Refused with `CARDS_NOT_AVAILABLE` while no merchant account is connected. */
  async beginCardEnrolment(tenantId: string): Promise<EnrolmentStartedView> {
    return firstValueFrom(
      this.api.post(financePaths.commercialWalletCardEnrolments(tenantId), command(null)),
    );
  }

  /**
   * Finishes putting a card on file. The card replaces any other; how the tenant is collected does not change.
   *
   * Takes a {@link Command} for the reason the class doc gives: a retry after a lost answer must replay
   * the success, not meet a session the first call already spent.
   */
  async confirmCard(tenantId: string, intent: Command<ConfirmCardBody>): Promise<CardOnFileView> {
    return firstValueFrom(
      this.api.post(financePaths.commercialWalletCardConfirmations(tenantId), intent),
    );
  }

  /** Takes the card off file. A tenant collected by CARD becomes INVOICE in the same step, and the answer says so. */
  async removeCard(
    tenantId: string,
  ): Promise<{ readonly paymentMethod: PaymentMethod | (string & {}) }> {
    return firstValueFrom(
      this.api.post(financePaths.commercialWalletCardRemoval(tenantId), command(null)),
    );
  }

  /** CARD needs a card on file and is the tenant's own consent to be charged for every statement's remainder. */
  async choosePaymentMethod(
    tenantId: string,
    paymentMethod: PaymentMethod,
  ): Promise<{ readonly paymentMethod: PaymentMethod | (string & {}) }> {
    return firstValueFrom(
      this.api.post(
        financePaths.commercialWalletPaymentMethod(tenantId),
        command({ paymentMethod }),
      ),
    );
  }
}
