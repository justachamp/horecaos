import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';

import { BrandScope } from '../../../core/api/catalog-paths';
import { ApiError } from '../../../core/api/problem-details';
import { CurrentBrand } from '../../../core/auth/current-brand';
import { formatMoney } from '../../../core/format/money';
import { I18n } from '../../../core/i18n/i18n';
import { MessageKey } from '../../../core/i18n/messages.en';
import { TPipe } from '../../../core/i18n/t.pipe';
import { describeApiError } from '../../orders/order-errors';
import {
  PosSyncApi,
  PosSyncBindingView,
  PosSyncDiscrepancyKind,
  PosSyncDiscrepancyView,
  PosSyncOverview,
  PosSyncResolutionCode,
  PosSyncRunReport,
  PosSyncRunView,
} from './pos-sync-api';

/** What each kind of difference may be settled with — the server refuses the rest with the same list. */
const ALLOWED: Readonly<Record<PosSyncDiscrepancyKind, readonly PosSyncResolutionCode[]>> = {
  TILL_HIGHER: ['ACCEPT_TILL', 'ACCEPT_PLATFORM', 'ABSORB', 'SUSPEND'],
  TILL_LOWER: ['ACCEPT_TILL', 'ACCEPT_PLATFORM', 'ABSORB', 'SUSPEND'],
  CREDIT_HELD: ['ACCEPT_TILL', 'ACCEPT_PLATFORM', 'SUSPEND'],
  SPEND_EXCEEDS_BALANCE: ['ABSORB', 'SUSPEND'],
  TILL_ONLY: ['ABSORB'],
  PLATFORM_ONLY: ['ABSORB'],
  AMBIGUOUS_MATCH: ['ABSORB'],
  RATIO_MISMATCH: ['ABSORB'],
  NOT_CONSENTED: ['ABSORB'],
  UNVERIFIABLE: ['ABSORB'],
};

/** The two balances are shown for these; the others name no pair of numbers. */
const SHOWS_BALANCES: ReadonlySet<PosSyncDiscrepancyKind> = new Set([
  'TILL_HIGHER',
  'TILL_LOWER',
  'SPEND_EXCEEDS_BALANCE',
  'CREDIT_HELD',
  'PLATFORM_ONLY',
  'UNVERIFIABLE',
]);

/**
 * Marketing §6.3b — POS balance sync (ADR 0171): the tab on the loyalty page that tells a tenant the truth about its
 * till.
 *
 * **What it says.** The points ledger is the one record of what a customer holds; a till's number is compared with it,
 * never copied over it, and a difference is a row a person settles — never silently corrected. A vendor that cannot do
 * this is shown as unable (`UNASSESSED`, `UNSUPPORTED`), not as working, and a tenant that says its till keeps a
 * balance of its own with no binding that mirrors it gets a standing notice that the two can differ. Redemption is not
 * blocked; the notice is the honest statement.
 *
 * **What it shows.** Per binding: the capability state at its vendor, its mode (report-only until somebody stages it
 * up), how many accounts are matched and how many differences are open. The reconciliation report — each pass with the
 * counts it named. The open differences with the two balances and the delta, each with only the resolutions that fit
 * its kind and a reason that is required. A credit above the manual-adjustment threshold answers *waiting for a second
 * signature* and leaves the difference open.
 *
 * Nothing here names a customer: a difference carries a points account's opaque id and the till's own customer id.
 */
@Component({
  selector: 'q-loyalty-pos-sync',
  imports: [TPipe],
  templateUrl: './pos-sync-panel.html',
  styleUrl: './pos-sync-panel.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PosSyncPanel implements OnInit {
  private readonly api = inject(PosSyncApi);
  private readonly brand = inject(CurrentBrand);
  protected readonly i18n = inject(I18n);

  protected readonly loading = signal(true);
  protected readonly denied = signal(false);
  protected readonly loadError = signal<string | null>(null);
  protected readonly actionError = signal<string | null>(null);

  protected readonly overview = signal<PosSyncOverview | null>(null);
  protected readonly runs = signal<readonly PosSyncRunView[]>([]);
  protected readonly discrepancies = signal<readonly PosSyncDiscrepancyView[]>([]);
  protected readonly openedRun = signal<PosSyncRunReport | null>(null);

  protected readonly reconcilingBindingId = signal<string | null>(null);
  protected readonly resolvingId = signal<string | null>(null);
  protected readonly reasons = signal<Readonly<Record<string, string>>>({});
  protected readonly pendingApproval = signal<ReadonlySet<string>>(new Set());

  protected readonly bindings = computed(() => this.overview()?.bindings ?? []);
  protected readonly standingNotice = computed(() => this.overview()?.standingNotice === true);

  private scope: BrandScope | null = null;

  async ngOnInit(): Promise<void> {
    await this.load();
  }

  protected capabilityLabelKey(state: PosSyncBindingView['capabilityState']): MessageKey {
    return `marketing.loyalty.posSync.capability.${state}` as MessageKey;
  }

  protected capabilityNoteKey(state: PosSyncBindingView['capabilityState']): MessageKey | null {
    return state === 'SUPPORTED'
      ? null
      : (`marketing.loyalty.posSync.capability.${state}.note` as MessageKey);
  }

  protected modeLabelKey(mode: PosSyncBindingView['mode']): MessageKey {
    return `marketing.loyalty.posSync.mode.${mode}` as MessageKey;
  }

  protected runKindLabelKey(kind: PosSyncRunView['kind']): MessageKey {
    return `marketing.loyalty.posSync.run.kind.${kind}` as MessageKey;
  }

  protected runStatusLabelKey(status: PosSyncRunView['status']): MessageKey {
    return `marketing.loyalty.posSync.run.status.${status}` as MessageKey;
  }

  protected kindLabelKey(kind: PosSyncDiscrepancyKind): MessageKey {
    return `marketing.loyalty.posSync.kind.${kind}` as MessageKey;
  }

  protected kindNoteKey(kind: PosSyncDiscrepancyKind): MessageKey {
    return `marketing.loyalty.posSync.kind.${kind}.note` as MessageKey;
  }

  protected resolutionLabelKey(resolution: PosSyncResolutionCode): MessageKey {
    return `marketing.loyalty.posSync.resolution.${resolution}` as MessageKey;
  }

  protected resolutionHintKey(resolution: PosSyncResolutionCode): MessageKey {
    return `marketing.loyalty.posSync.resolution.${resolution}.hint` as MessageKey;
  }

  protected allowedResolutions(row: PosSyncDiscrepancyView): readonly PosSyncResolutionCode[] {
    return ALLOWED[row.kind];
  }

  protected showsBalances(row: PosSyncDiscrepancyView): boolean {
    return SHOWS_BALANCES.has(row.kind);
  }

  /** Whole points in the account's currency, signed for a delta. */
  protected formatPoints(amountMinor: number | null, signed = false): string {
    if (amountMinor === null) {
      return '—';
    }
    const text = formatMoney(
      { amountMinor: Math.abs(amountMinor), currency: 'UZS' },
      this.i18n.locale(),
      {
        withUnit: false,
      },
    );
    if (!signed || amountMinor === 0) {
      return amountMinor < 0 ? `−${text}` : text;
    }
    return amountMinor > 0 ? `+${text}` : `−${text}`;
  }

  protected reasonOf(row: PosSyncDiscrepancyView): string {
    return this.reasons()[row.discrepancyId] ?? '';
  }

  protected setReason(row: PosSyncDiscrepancyView, value: string): void {
    this.reasons.update((current) => ({ ...current, [row.discrepancyId]: value }));
  }

  protected canResolve(row: PosSyncDiscrepancyView): boolean {
    return this.resolvingId() !== row.discrepancyId && this.reasonOf(row).trim().length > 0;
  }

  protected isAwaitingSignature(row: PosSyncDiscrepancyView): boolean {
    return this.pendingApproval().has(row.discrepancyId);
  }

  protected async reconcile(binding: PosSyncBindingView): Promise<void> {
    if (!this.scope) {
      return;
    }
    this.actionError.set(null);
    this.reconcilingBindingId.set(binding.bindingId);
    try {
      await this.api.reconcile(this.scope, binding.bindingId);
      await this.refresh();
    } catch (error) {
      this.actionError.set(this.describe(error));
    } finally {
      this.reconcilingBindingId.set(null);
    }
  }

  protected async resolve(
    row: PosSyncDiscrepancyView,
    resolution: PosSyncResolutionCode,
  ): Promise<void> {
    if (!this.scope || !this.canResolve(row)) {
      return;
    }
    this.actionError.set(null);
    this.resolvingId.set(row.discrepancyId);
    try {
      const result = await this.api.resolve(this.scope, row, resolution, this.reasonOf(row).trim());
      if (!result.applied && result.approval === 'PENDING') {
        // A credit above the manual-adjustment threshold waits for a second person; the difference stays open.
        this.pendingApproval.update((current) => new Set([...current, row.discrepancyId]));
      } else {
        this.pendingApproval.update((current) => {
          const next = new Set(current);
          next.delete(row.discrepancyId);
          return next;
        });
        await this.refresh();
      }
    } catch (error) {
      this.actionError.set(this.describe(error));
    } finally {
      this.resolvingId.set(null);
    }
  }

  protected async openRun(run: PosSyncRunView): Promise<void> {
    if (!this.scope) {
      return;
    }
    this.actionError.set(null);
    try {
      this.openedRun.set(await this.api.run(this.scope, run.runId));
    } catch (error) {
      this.actionError.set(this.describe(error));
    }
  }

  protected closeRun(): void {
    this.openedRun.set(null);
  }

  private async load(): Promise<void> {
    this.loading.set(true);
    await this.brand.ensureLoaded();
    const scope = this.brand.scope();
    if (!scope) {
      this.denied.set(this.brand.denied());
      this.loading.set(false);
      return;
    }
    this.scope = scope;
    try {
      await this.refresh();
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

  private async refresh(): Promise<void> {
    const scope = this.scope;
    if (!scope) {
      return;
    }
    const [overview, runs, discrepancies] = await Promise.all([
      this.api.overview(scope),
      this.api.runs(scope),
      this.api.discrepancies(scope, 'OPEN'),
    ]);
    this.overview.set(overview);
    this.runs.set(runs);
    this.discrepancies.set(discrepancies);
  }

  private describe(error: unknown): string {
    return error instanceof ApiError
      ? describeApiError(error, (key, values) => this.i18n.t(key, values))
      : this.i18n.t('marketing.loyalty.posSync.error');
  }
}
