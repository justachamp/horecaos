import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';

import { ApiError } from '../../../core/api/problem-details';
import { CurrentLocation } from '../../../core/auth/current-location';
import { I18n } from '../../../core/i18n/i18n';
import { TPipe } from '../../../core/i18n/t.pipe';
import { describeApiError } from '../../orders/order-errors';
import {
  AcceptanceMode,
  AcceptancePolicyResponse,
  ApprovalChannel,
  ApprovalTimeoutAction,
  OrderPolicyApi,
} from './order-policy-api';

/**
 * 10.3 Order policy — `docs/operations-spec/settings.md` §10.3.
 *
 * **Card 1 (Приём заказа) is real**, wired to `OrderAcceptancePolicyController`
 * — the one card the spec's own gap table does not list as missing. It
 * publishes directly rather than through the spec's draft → diff → activate
 * flow: the endpoint has one `author` call, no draft state, no version
 * history read, so this screen states the current version and reason inline
 * rather than performing a review step the server cannot back.
 *
 * **Cards 2–5 are not built.** Every field in them needs a `ConfigurationKeys`
 * entry that does not exist today (late threshold, average/maximum order
 * time, business-day boundary, auto-accept channel/order-count gates, and
 * more) — content this spec explicitly assigns to ADR 0002/0019/0037, not to
 * this wave. Rendering them as inputs that resolve to nothing would be the
 * mocked screen the wave's own scope rules warn against, so each renders the
 * shared not-built note instead, naming the field it is missing.
 */
@Component({
  selector: 'q-order-policy-page',
  imports: [TPipe],
  templateUrl: './order-policy-page.html',
  styleUrl: './order-policy-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class OrderPolicyPage {
  private readonly api = inject(OrderPolicyApi);
  private readonly location = inject(CurrentLocation);
  protected readonly i18n = inject(I18n);

  protected readonly loading = signal(true);
  protected readonly denied = signal(false);
  protected readonly loadError = signal<string | null>(null);
  protected readonly policy = signal<AcceptancePolicyResponse | null>(null);

  protected readonly editing = signal(false);
  protected readonly saving = signal(false);
  protected readonly saveError = signal<string | null>(null);

  protected readonly draftMode = signal<AcceptanceMode>('RESTAURANT_APPROVAL');
  protected readonly draftApprovalChannel = signal<ApprovalChannel>('HORECAOS_OPERATIONS');
  protected readonly draftTimeoutSeconds = signal(300);
  protected readonly draftTimeoutAction = signal<ApprovalTimeoutAction>('AUTO_REJECT');
  protected readonly draftRejectionReasonRequired = signal(true);
  protected readonly draftNotifyCustomer = signal(true);
  protected readonly draftReason = signal('');

  constructor() {
    void this.load();
  }

  protected modeLabel(mode: AcceptanceMode): string {
    return mode === 'AUTO_CONFIRM'
      ? this.i18n.t('settings.orderPolicy.mode.AUTO_CONFIRM')
      : this.i18n.t('settings.orderPolicy.mode.RESTAURANT_APPROVAL');
  }

  protected approvalChannelLabel(channel: ApprovalChannel): string {
    switch (channel) {
      case 'NONE':
        return this.i18n.t('settings.orderPolicy.approvalChannel.NONE');
      case 'HORECAOS_OPERATIONS':
        return this.i18n.t('settings.orderPolicy.approvalChannel.HORECAOS_OPERATIONS');
      case 'POS':
        return this.i18n.t('settings.orderPolicy.approvalChannel.POS');
      case 'EITHER':
        return this.i18n.t('settings.orderPolicy.approvalChannel.EITHER');
      default:
        return channel;
    }
  }

  protected timeoutActionLabel(action: ApprovalTimeoutAction): string {
    return action === 'AUTO_REJECT'
      ? this.i18n.t('settings.orderPolicy.timeoutAction.AUTO_REJECT')
      : this.i18n.t('settings.orderPolicy.timeoutAction.AUTO_CONFIRM');
  }

  protected yesNo(value: boolean): string {
    return value ? this.i18n.t('settings.orderPolicy.yes') : this.i18n.t('settings.orderPolicy.no');
  }

  protected startEditing(): void {
    const current = this.policy();
    if (current) {
      this.draftMode.set(current.mode);
      this.draftApprovalChannel.set(current.approvalChannel);
      this.draftTimeoutSeconds.set(current.approvalTimeoutSeconds);
      this.draftTimeoutAction.set(current.timeoutAction);
      this.draftRejectionReasonRequired.set(current.rejectionReasonRequired);
      this.draftNotifyCustomer.set(current.notifyCustomerWhilePending);
    }
    this.draftReason.set('');
    this.saveError.set(null);
    this.editing.set(true);
  }

  protected cancelEditing(): void {
    this.editing.set(false);
  }

  protected canPublish(): boolean {
    return !this.saving() && this.draftReason().trim().length > 0;
  }

  protected async publish(): Promise<void> {
    const scope = this.location.scope();
    if (!scope || !this.canPublish()) {
      return;
    }
    this.saving.set(true);
    this.saveError.set(null);
    try {
      const updated = await this.api.publish(scope, {
        mode: this.draftMode(),
        approvalChannel: this.draftApprovalChannel(),
        approvalTimeoutSeconds: this.draftTimeoutSeconds(),
        timeoutAction: this.draftTimeoutAction(),
        rejectionReasonRequired: this.draftRejectionReasonRequired(),
        notifyCustomerWhilePending: this.draftNotifyCustomer(),
        reason: this.draftReason().trim(),
      });
      this.policy.set(updated);
      this.editing.set(false);
    } catch (error) {
      this.saveError.set(this.describe(error));
    } finally {
      this.saving.set(false);
    }
  }

  private async load(): Promise<void> {
    this.loading.set(true);
    await this.location.ensureLoaded();
    const scope = this.location.scope();
    if (!scope) {
      this.denied.set(this.location.denied());
      this.loading.set(false);
      return;
    }
    try {
      this.policy.set(await this.api.getEffective(scope));
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

  private describe(error: unknown): string {
    if (error instanceof ApiError) {
      return describeApiError(error, (key, values) => this.i18n.t(key, values));
    }
    return this.i18n.t('error.unknown.noReference');
  }
}
