import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';

import { CurrentTenant } from '../../../core/auth/current-tenant';
import { formatMoney } from '../../../core/format/money';
import { I18n } from '../../../core/i18n/i18n';
import { MessageKey } from '../../../core/i18n/messages.en';
import { TPipe } from '../../../core/i18n/t.pipe';
import { ApiError } from '../../../core/api/problem-details';
import { describeApiError } from '../../orders/order-errors';
import { CommercialApi, EntitlementSnapshotView, SubscriptionView, UsageView } from '../commercial-api';

type LoadState = 'loading' | 'ready' | 'denied' | 'error';

const STATUS_KEYS: Readonly<Record<SubscriptionView['status'], MessageKey>> = {
  DRAFT: 'finance.subscription.status.DRAFT',
  TRIALING: 'finance.subscription.status.TRIALING',
  ACTIVE: 'finance.subscription.status.ACTIVE',
  PAST_DUE: 'finance.subscription.status.PAST_DUE',
  SUSPENDED: 'finance.subscription.status.SUSPENDED',
  CANCELLATION_SCHEDULED: 'finance.subscription.status.CANCELLATION_SCHEDULED',
  EXPIRED: 'finance.subscription.status.EXPIRED',
  TERMINATED: 'finance.subscription.status.TERMINATED',
};

/**
 * 8.6 Subscription & billing (`frontend-information-architecture.md` §8.6) —
 * tier 2. "The merchant's own HorecaOS account: current plan and term;
 * purchasable modules …; prepaid wallet …; credit-expiry warning; arrears
 * state …; invoices."
 *
 * **What is real here.** Plan, term and status (`CommercialOperationsController
 * .subscription`), every entitled module with where its value came from
 * (`.entitlements` — the locked-by-plan half of IA 9.1's locked-vs-denied
 * distinction), and metered usage, measured and adjusted kept apart
 * (`.usage`) — the same three reads `CommercialControlPlaneController`
 * already serves platform staff, reachable from this console for the first
 * time.
 *
 * **What is honestly not.** ADR 0021's own status line: no period close, no
 * invoice export, no wallet. Purchasable modules with inline purchase needs
 * the platform-wide plan catalogue, a `ScopeType.PLATFORM` read no tenant
 * grant can satisfy — this screen names that rather than shipping a
 * catalogue with nothing to buy from it.
 */
@Component({
  selector: 'q-subscription-page',
  imports: [TPipe],
  templateUrl: './subscription-page.html',
  styleUrl: './subscription-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SubscriptionPage {
  private readonly tenant = inject(CurrentTenant);
  private readonly api = inject(CommercialApi);
  protected readonly i18n = inject(I18n);

  protected readonly state = signal<LoadState>('loading');
  protected readonly loadErrorText = signal<string | null>(null);

  protected readonly subscription = signal<SubscriptionView | null>(null);
  protected readonly subscriptionDenied = signal(false);
  protected readonly entitlements = signal<EntitlementSnapshotView | null>(null);
  protected readonly usage = signal<readonly UsageView[]>([]);

  constructor() {
    void this.load();
  }

  protected retry(): void {
    void this.load();
  }

  private async load(): Promise<void> {
    this.state.set('loading');
    await this.tenant.ensureLoaded();
    const tenantId = this.tenant.tenantId();
    if (!tenantId) {
      this.state.set(this.tenant.denied() ? 'denied' : 'error');
      return;
    }
    try {
      const [subscription, entitlements, usage] = await Promise.all([
        this.api.subscription(tenantId).catch((error) => {
          if (error instanceof ApiError && error.status === 403) {
            this.subscriptionDenied.set(true);
          }
          return null;
        }),
        this.api.entitlements(tenantId),
        this.api.usage(tenantId),
      ]);
      this.subscription.set(subscription);
      this.entitlements.set(entitlements);
      this.usage.set(usage);
      this.state.set('ready');
    } catch (error) {
      if (error instanceof ApiError && error.status === 403) {
        this.state.set('denied');
      } else {
        this.loadErrorText.set(this.describe(error));
        this.state.set('error');
      }
    }
  }

  protected money(value: { amountMinor: number; currency: string } | null): string {
    if (!value) {
      return '—';
    }
    return formatMoney(value, this.i18n.locale(), { withUnit: true });
  }

  protected statusLabel(status: SubscriptionView['status']): string {
    return this.i18n.t(STATUS_KEYS[status]);
  }

  private describe(error: unknown): string {
    if (error instanceof ApiError) {
      return describeApiError(error, (key, values) => this.i18n.t(key, values));
    }
    return this.i18n.t('error.unknown.noReference');
  }
}
