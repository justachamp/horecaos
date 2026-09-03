import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';

import { ApiError } from '../../core/api/problem-details';
import { CurrentLocation } from '../../core/auth/current-location';
import { I18n } from '../../core/i18n/i18n';
import { TPipe } from '../../core/i18n/t.pipe';
import { CouriersApi, CourierPolicyView } from '../couriers/couriers-api';
import { describeApiError } from '../orders/order-errors';

/**
 * IA 3.9 — Courier policy.
 *
 * **Built, read-only.** `CourierPolicyResolver.resolveWithIdentity` already
 * existed and resolved ADR 0042's `CourierCompensationPolicy` document
 * through ADR 0030; this wave adds the one endpoint that reads it
 * (`GET .../courier-policy`). The resolved scope and policy identity are
 * shown so a manager can tell "the tenant default" from "an override" —
 * couriers.md §16's own requirement — even though authoring one is not
 * built (see below).
 *
 * **Not built, honestly.** couriers.md §16 lists ten switches; this
 * document backs eight of its fields (shift enforcement, cash ceiling,
 * penalty-approval threshold, reverification/warning/settlement windows,
 * grace seconds, confirmation-point retention) and none of the other six
 * named in the spec's own table — GPS gates, show-only-kitchen-ready,
 * reveal-customer-location timing, the telemetry collection-gate default,
 * and post-delivery payment check have no policy-document field anywhere
 * in ADR 0042 or ADR 0045 yet. Authoring (a write endpoint) is also not
 * built — `CourierPolicyResolver` had no writer before this wave and
 * building one from scratch, the way `OrderAcceptancePolicyController` did
 * for its own domain, is a larger, separate change than a read surface.
 * Both gaps are named inline rather than silently rendering fewer rows than
 * the spec's own table.
 */
@Component({
  selector: 'q-courier-policy-page',
  imports: [TPipe],
  templateUrl: './courier-policy-page.html',
  styleUrl: './courier-policy-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CourierPolicyPage implements OnInit {
  private readonly api = inject(CouriersApi);
  private readonly location = inject(CurrentLocation);
  protected readonly i18n = inject(I18n);

  protected readonly loading = signal(true);
  protected readonly denied = signal(false);
  protected readonly loadError = signal<string | null>(null);
  protected readonly policy = signal<CourierPolicyView | null>(null);

  async ngOnInit(): Promise<void> {
    await this.load();
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
      const policy = await this.api.policy(scope.tenantId, scope.brandId, scope.locationId);
      this.policy.set(policy);
      this.denied.set(false);
    } catch (error) {
      if (error instanceof ApiError && error.status === 403) {
        this.denied.set(true);
      } else {
        this.loadError.set(
          error instanceof ApiError
            ? describeApiError(error, (key, values) => this.i18n.t(key, values))
            : this.i18n.t('error.unknown.noReference'),
        );
      }
    } finally {
      this.loading.set(false);
    }
  }

  protected shiftEnforcementLabel(value: string): string {
    switch (value) {
      case 'ENFORCED':
        return this.i18n.t('delivery.policy.shiftEnforcement.ENFORCED');
      case 'ADVISORY':
        return this.i18n.t('delivery.policy.shiftEnforcement.ADVISORY');
      case 'OFF':
        return this.i18n.t('delivery.policy.shiftEnforcement.OFF');
      default:
        return value;
    }
  }

  protected sumLabel(minor: number): string {
    return new Intl.NumberFormat('ru-RU').format(minor);
  }
}
