import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../../core/api/api-client';
import { command } from '../../../core/api/idempotency';
import { LocationScope } from '../../../core/api/operations-paths';
import { settingsPaths } from '../../../core/api/settings-paths';

/** {@code ck_payment_method_responsibility}'s own closed set — a method's "base type". */
export type PaymentMethodResponsibility = 'PARTNER' | 'TERMINAL' | 'MARKETPLACE' | 'OPERATOR';

export const PAYMENT_METHOD_RESPONSIBILITIES: readonly PaymentMethodResponsibility[] = [
  'PARTNER',
  'TERMINAL',
  'MARKETPLACE',
  'OPERATOR',
];

/** Mirrors uz.horecaos.platform.payments.web.PaymentMethodController.PaymentMethodView. */
export interface PaymentMethodView {
  readonly id: string;
  readonly code: string;
  readonly displayName: string;
  readonly localizedNames: Readonly<Record<string, string>>;
  readonly responsibility: PaymentMethodResponsibility;
  readonly settlesFromBalance: boolean;
  readonly status: 'ACTIVE' | 'DISABLED';
  readonly icon: string | null;
  readonly sortOrder: number;
  readonly providerInstallationId: string | null;
  readonly contractReference: string | null;
  readonly version: number;
}

export interface CreatePaymentMethodRequest {
  readonly code: string;
  readonly displayName: string;
  readonly responsibility: PaymentMethodResponsibility;
  readonly icon?: string | null;
  readonly sortOrder: number;
  readonly providerInstallationId?: string | null;
  readonly contractReference?: string | null;
}

export interface UpdatePaymentMethodRequest {
  readonly displayName: string;
  readonly icon?: string | null;
  readonly sortOrder: number;
  readonly providerInstallationId?: string | null;
  readonly contractReference?: string | null;
}

/**
 * 10.6 Payment methods (`PaymentMethodController`, ADR 0038, wave P33).
 *
 * The registry `payments.payment_methods` has never had a caller anywhere —
 * every row until this wave was created lazily, mid-checkout, from whatever a
 * tenant happened to tender. This is the tenant-scoped registry the sales
 * channel matrix's columns (10.4b) read instead of a frontend constant.
 */
@Injectable({ providedIn: 'root' })
export class PaymentMethodsApi {
  private readonly api = inject(ApiClient);

  async list(scope: LocationScope): Promise<readonly PaymentMethodView[]> {
    const result = await firstValueFrom(
      this.api.get<readonly PaymentMethodView[]>(settingsPaths.paymentMethods(scope)),
    );
    return result.value ?? [];
  }

  async create(
    scope: LocationScope,
    request: CreatePaymentMethodRequest,
  ): Promise<PaymentMethodView> {
    return firstValueFrom(
      this.api.post<CreatePaymentMethodRequest, PaymentMethodView>(
        settingsPaths.paymentMethods(scope),
        command(request),
      ),
    );
  }

  async update(
    scope: LocationScope,
    methodId: string,
    request: UpdatePaymentMethodRequest,
    expectedVersion: number,
  ): Promise<PaymentMethodView> {
    return firstValueFrom(
      this.api.put<UpdatePaymentMethodRequest, PaymentMethodView>(
        settingsPaths.paymentMethod(scope, methodId),
        command(request),
        { params: { expectedVersion } },
      ),
    );
  }

  async replaceTranslations(
    scope: LocationScope,
    methodId: string,
    byLocale: Readonly<Record<string, string>>,
  ): Promise<PaymentMethodView> {
    return firstValueFrom(
      this.api.put<{ byLocale: Readonly<Record<string, string>> }, PaymentMethodView>(
        settingsPaths.paymentMethodTranslations(scope, methodId),
        command({ byLocale }),
      ),
    );
  }

  async activate(
    scope: LocationScope,
    methodId: string,
    expectedVersion: number,
  ): Promise<PaymentMethodView> {
    return firstValueFrom(
      this.api.post<null, PaymentMethodView>(
        settingsPaths.paymentMethodActivate(scope, methodId),
        command(null),
        {
          params: { expectedVersion },
        },
      ),
    );
  }

  async disable(
    scope: LocationScope,
    methodId: string,
    expectedVersion: number,
  ): Promise<PaymentMethodView> {
    return firstValueFrom(
      this.api.post<null, PaymentMethodView>(
        settingsPaths.paymentMethodDisable(scope, methodId),
        command(null),
        {
          params: { expectedVersion },
        },
      ),
    );
  }
}
