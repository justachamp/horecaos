import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';

import { asDate } from '../../core/api/dates';
import { Money } from '../../core/api/money';
import { ApiError } from '../../core/api/problem';
import { SessionContextService } from '../../core/auth/session-context.service';
import { I18nService } from '../../core/i18n/i18n.service';
import { MessageKey, en } from '../../core/i18n/messages.en';
import { TenantDirectory } from '../../shared/tenant-directory';
import { AccessApi } from '../access/access-api';
import { PlatformPendingApproval, ResidencyApi } from './residency-api';

/** One component of a signed subject, as the approvals queue renders it. */
interface SubjectField {
  readonly key: string;
  readonly label: string;
  readonly value: string;
}

/**
 * IA 6.5 Approvals -- platform decisions waiting for a second signature, in
 * every tenant: a change of the country a tenant trades in, a tenant's
 * activation where a policy asks for one, and the wallet changes HorecaOS
 * proposes against a tenant's account -- a correction, a bonus grant, a refund
 * of paid money, or the reversal of a deposit recorded in error (ADR 0095).
 *
 * A wallet row is HorecaOS's own decision, so it carries no tenant of its own:
 * that null is what keeps it out of the tenant's worklist and off the tenant's
 * decision route. It would also have left the approver signing an action code
 * and a timestamp, so such a row carries its subject instead -- whose account
 * the money leaves and what is proposed -- taken from the same command the
 * parameters hash covers, and rendered here beside the action.
 *
 * Nobody can decide their own request, and each row says whether the reader
 * could. Bulk export and retention override are not actions the platform has
 * yet, so there is nothing of theirs to wait here.
 */
@Component({
  selector: 'app-platform-approvals',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink],
  templateUrl: './platform-approvals.html',
  styleUrl: './residency-hosting.css',
})
export class PlatformApprovals {
  protected readonly i18n = inject(I18nService);
  protected readonly asDate = asDate;
  protected readonly session = inject(SessionContextService);
  private readonly api = inject(ResidencyApi);
  private readonly access = inject(AccessApi);
  protected readonly directory = inject(TenantDirectory);

  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly waiting = signal<readonly PlatformPendingApproval[]>([]);

  protected readonly deciding = signal<{ id: string; decision: 'APPROVE' | 'DECLINE' } | null>(
    null,
  );
  protected readonly reason = signal('');
  protected readonly busy = signal(false);
  protected readonly actionError = signal<string | null>(null);
  protected readonly actionMessage = signal<string | null>(null);

  constructor() {
    void this.directory.load();
    void this.load();
  }

  private async load(): Promise<void> {
    this.loading.set(true);
    this.loadError.set(null);
    try {
      this.waiting.set(await this.api.platformApprovals());
    } catch (error) {
      this.loadError.set(this.i18n.describe(error as ApiError));
    } finally {
      this.loading.set(false);
    }
  }

  /**
   * The label for an action code, or the code itself when no catalogue has one.
   *
   * The key is built from the code and cast, which defeats the keyof-typeof
   * completeness check every other key gets -- so `commercial.wallet.deposit-reversal`
   * was added to the queue with no label in any of the three catalogues and its
   * Action cell rendered the empty string, for the one action that both removes
   * paid money and re-arms a subscription's deposit. `I18nService.t` has no
   * per-key English fallback on purpose (see messages.ru.ts), so the guard is
   * here: an unlabelled action shows its raw code, which is ugly and readable,
   * rather than nothing, which is neither.
   */
  protected actionLabel(code: string): string {
    const key = `platformApprovals.action.${code.replace(/\./g, '_')}`;
    return key in en ? this.i18n.t(key as MessageKey) : code;
  }

  /**
   * Whose money moves and how much, for a row that names no tenant of its own.
   *
   * Empty for a row whose tenant column already answers the question.
   *
   * The amount is rendered through `moneyOrRaw`, not `money`: the currency is
   * whatever the tenant holds, `money` throws on one this console has no scale
   * for, and a throw here truncates the whole queue at the offending row —
   * which, since the queue is served oldest first, hides everything newer than
   * one tenant billed in KZT.
   */
  protected subjectLine(row: PlatformPendingApproval): string {
    if (row.tenantId !== null) {
      return '';
    }
    const subject = row.request.subject;
    const tenant =
      row.request.subjectTenantName ??
      (row.request.subjectTenantId === null
        ? this.i18n.t('platformApprovals.subject.unknownTenant')
        : this.directory.nameOf(row.request.subjectTenantId));
    const amount = this.signedAmount(subject);
    if (amount === null) {
      return tenant;
    }
    return this.i18n.t('platformApprovals.subject.moves', {
      tenant,
      amount: this.i18n.moneyOrRaw(amount),
    });
  }

  /**
   * Every remaining component of the subject the signature covers, laid out
   * beneath the amount.
   *
   * <p>The subject is not two keys. `ApprovalParameters.sign()` puts each
   * covered, non-withheld component of the command into it in one pass, and
   * the console used to render `amountMinor` and `currency` and drop the rest
   * on the floor — so a correction of real, refundable paid money and one of
   * promotional credit that lapses with a named grant read identically at the
   * same amount under the same action label, and a bonus grant's own expiry,
   * which is half of what is being given away, reached no screen at all. A
   * component the signature covers and the console does not show is the exact
   * failure V0212 and ADR 0095 were written to close.
   *
   * <p>So this renders whatever is there rather than a list somebody has to
   * remember to extend: a key with no label shows its raw key, the way {@link
   * actionLabel} shows a raw action code. Never dropped, never blank.
   *
   * <p>`tenantId` is left out because the tenant column already links it, and
   * the amount and its currency are left out only when the amount line above
   * actually rendered them.
   */
  protected subjectDetail(row: PlatformPendingApproval): readonly SubjectField[] {
    if (row.tenantId !== null) {
      return [];
    }
    const subject = row.request.subject;
    const alreadyOnTheRow =
      this.signedAmount(subject) === null
        ? new Set(['tenantId'])
        : new Set(['tenantId', 'amountMinor', 'currency']);
    return Object.entries(subject)
      .filter(([key]) => !alreadyOnTheRow.has(key))
      .map(([key, value]) => ({
        key,
        label: this.labelled(`platformApprovals.subject.field.${key}`, key),
        value: this.subjectValue(key, value),
      }));
  }

  /** The signed amount and its currency, or null when the subject carries no readable one. */
  private signedAmount(subject: Readonly<Record<string, string>>): Money | null {
    const minor = subject['amountMinor'];
    const currency = subject['currency'];
    if (minor === undefined || currency === undefined) {
      return null;
    }
    const amountMinor = Number(minor);
    return Number.isFinite(amountMinor) ? { amountMinor, currency } : null;
  }

  /**
   * One subject value as a person reads it, through the catalogue the rest of
   * the console already uses for that value, and verbatim when there is none.
   *
   * An identifier stays an identifier: a truncated-but-present grant id is
   * honest about what the signature covers, and an omitted one is not.
   */
  private subjectValue(key: string, value: string): string {
    switch (key) {
      case 'moneyKind':
        return this.labelled(`wallet.kind.${value}`, value);
      case 'entryType':
        return this.labelled(`wallet.entry.${value}`, value);
      case 'expiresAt': {
        const lapsesOn = asDate(value);
        return Number.isNaN(lapsesOn.getTime()) ? value : this.i18n.day(lapsesOn);
      }
      default:
        return value;
    }
  }

  /** A catalogued label, or the raw string when no catalogue has one — never the empty string. */
  private labelled(key: string, raw: string): string {
    return key in en ? this.i18n.t(key as MessageKey) : raw;
  }

  /** The tenant a platform row concerns, for the link back to its wallet. */
  protected subjectTenantId(row: PlatformPendingApproval): string | null {
    return row.tenantId ?? row.request.subjectTenantId;
  }

  /**
   * Whose account the decision concerns, as far as this queue can say. A
   * PLATFORM-scope request names no tenant on purpose: it is HorecaOS's own
   * decision, and the console that raised it holds the detail behind its own
   * capability (ADR 0095, ADR 0029).
   */
  protected tenantName(row: PlatformPendingApproval): string {
    return row.tenantId === null
      ? this.i18n.t('platformApprovals.platformScoped')
      : this.directory.nameOf(row.tenantId);
  }

  protected open(row: PlatformPendingApproval, decision: 'APPROVE' | 'DECLINE'): void {
    const current = this.deciding();
    this.deciding.set(
      current?.id === row.request.id && current.decision === decision
        ? null
        : { id: row.request.id, decision },
    );
    this.reason.set('');
    this.actionError.set(null);
  }

  protected async confirm(row: PlatformPendingApproval): Promise<void> {
    const action = this.deciding();
    const reason = this.reason().trim();
    if (action === null || reason.length === 0 || this.busy()) {
      return;
    }
    this.busy.set(true);
    this.actionError.set(null);
    this.actionMessage.set(null);
    try {
      // A PLATFORM-scope request carries no tenant, and the tenant decision
      // route is keyed on one: sending it there answers 404. Which route a row
      // takes is decided by the row, not by its action code, so a decision
      // HorecaOS adds later needs nothing here.
      if (row.tenantId === null) {
        await this.api.decidePlatform(row.request.id, action.decision, reason);
      } else {
        await this.access.decide(row.tenantId, row.request.id, action.decision, reason);
      }
      this.deciding.set(null);
      this.actionMessage.set(
        this.i18n.t(
          action.decision === 'APPROVE'
            ? 'platformApprovals.approved'
            : 'platformApprovals.declined',
          {
            tenant: this.tenantName(row),
          },
        ),
      );
      await this.load();
    } catch (error) {
      this.actionError.set(this.i18n.describe(error as ApiError));
    } finally {
      this.busy.set(false);
    }
  }
}
