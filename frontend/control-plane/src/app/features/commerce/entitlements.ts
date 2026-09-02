import { KeyValuePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { ActivatedRoute } from '@angular/router';

import { asDate } from '../../core/api/dates';
import { ApiError } from '../../core/api/problem';
import { SessionContextService } from '../../core/auth/session-context.service';
import { I18nService } from '../../core/i18n/i18n.service';
import {
  CommerceApi,
  EntitlementSnapshot,
  SubscriptionView,
  UsageView,
} from './commerce-api';

/**
 * IA 5.3 Entitlements -- what each tenant is entitled to, its grants, module
 * locks, and overrides (ADR 0021's entitlement state, the
 * `permission x entitlement x business type` composition).
 *
 * Cross-tenant by nature, unlike most IA §2 screens: a tenant id is typed or
 * arrives via `?tenantId=` from IA 2.2's own "Open in Entitlements" link,
 * because there is no single browsable list of subscriptions here (only a
 * per-tenant read).
 */
@Component({
  selector: 'app-entitlements',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [KeyValuePipe],
  templateUrl: './entitlements.html',
  styleUrl: './entitlements.css',
})
export class Entitlements {
  protected readonly i18n = inject(I18nService);
  protected readonly asDate = asDate;
  private readonly commerceApi = inject(CommerceApi);
  private readonly route = inject(ActivatedRoute);
  protected readonly session = inject(SessionContextService);

  protected readonly tenantId = signal(this.route.snapshot.queryParamMap.get('tenantId') ?? '');

  protected readonly loading = signal(false);
  protected readonly loadError = signal<string | null>(null);
  protected readonly snapshot = signal<EntitlementSnapshot | null>(null);
  protected readonly subscription = signal<SubscriptionView | null>(null);
  protected readonly usage = signal<UsageView | null>(null);

  protected readonly overrideKey = signal('');
  protected readonly overrideLimit = signal('');
  protected readonly overrideValidUntil = signal('');
  protected readonly overrideApprovedBy = signal('');
  protected readonly overrideSubmitting = signal(false);
  protected readonly overrideMessage = signal<string | null>(null);

  constructor() {
    if (this.tenantId().length > 0) {
      void this.load();
    }
  }

  protected async load(): Promise<void> {
    const tenantId = this.tenantId().trim();
    if (tenantId.length === 0) {
      return;
    }
    this.loading.set(true);
    this.loadError.set(null);
    try {
      const [snapshot, subscription, usage] = await Promise.all([
        this.commerceApi.getEntitlements(tenantId),
        this.commerceApi.getSubscription(tenantId),
        this.commerceApi.getUsage(tenantId),
      ]);
      this.snapshot.set(snapshot);
      this.subscription.set(subscription);
      this.usage.set(usage);
    } catch (error) {
      this.loadError.set(this.i18n.describe(error as ApiError));
    } finally {
      this.loading.set(false);
    }
  }

  protected canSubmitOverride(): boolean {
    return (
      !this.overrideSubmitting() &&
      this.overrideKey().trim().length > 0 &&
      this.overrideValidUntil().trim().length > 0 &&
      this.overrideApprovedBy().trim().length > 0
    );
  }

  protected async submitOverride(event: Event): Promise<void> {
    event.preventDefault();
    if (!this.canSubmitOverride()) {
      return;
    }
    this.overrideSubmitting.set(true);
    this.overrideMessage.set(null);
    try {
      const limit = this.overrideLimit().trim();
      await this.commerceApi.grantOverride(this.tenantId().trim(), {
        entitlementKey: this.overrideKey().trim(),
        limit: limit.length > 0 ? Number(limit) : undefined,
        enabled: limit.length === 0 ? true : undefined,
        validUntil: new Date(this.overrideValidUntil()).toISOString(),
        approvedBy: this.overrideApprovedBy().trim(),
        reason: this.i18n.t('entitlements.override.reason'),
      });
      this.overrideMessage.set(this.i18n.t('entitlements.override.success'));
      await this.load();
    } catch (error) {
      this.overrideMessage.set(this.i18n.describe(error as ApiError));
    } finally {
      this.overrideSubmitting.set(false);
    }
  }
}
