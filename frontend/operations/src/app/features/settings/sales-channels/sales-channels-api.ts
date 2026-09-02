import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../../core/api/api-client';
import { command } from '../../../core/api/idempotency';
import { LocationScope } from '../../../core/api/operations-paths';
import { settingsPaths } from '../../../core/api/settings-paths';

/** Mirrors uz.horecaos.platform.tenancy.web.SalesChannelController.ChannelView. */
export interface ChannelView {
  readonly id: string;
  readonly code: string;
  readonly systemType: string;
  readonly displayName: string;
  readonly status: string;
  readonly pricePlaneChannelId: string | null;
  readonly externallyPriced: boolean;
  readonly guestOrdersAllowed: boolean;
  readonly providerInstallationId: string | null;
  readonly version: number;
}

/** Mirrors uz.horecaos.platform.tenancy.application.SalesChannelService.ChannelMatrices. */
export interface ChannelMatrices {
  readonly paymentMethods: Readonly<Record<string, boolean>>;
  readonly fulfillmentModes: Readonly<Record<string, boolean>>;
  readonly locationIds: readonly string[];
}

export interface CreateChannelRequest {
  readonly code: string;
  readonly systemType: string;
  readonly displayName: string;
  readonly pricePlaneChannelId?: string | null;
  readonly externallyPriced: boolean;
  readonly guestOrdersAllowed: boolean;
  readonly providerInstallationId?: string | null;
}

/**
 * 10.4 Sales channels (`SalesChannelController`, ADR 0036). Still on the
 * control-plane path — see `settings-paths.ts`'s own doc comment for why
 * this wave calls it cross-surface rather than moving it.
 */
@Injectable({ providedIn: 'root' })
export class SalesChannelsApi {
  private readonly api = inject(ApiClient);

  async list(scope: LocationScope): Promise<readonly ChannelView[]> {
    const result = await firstValueFrom(
      this.api.get<readonly ChannelView[]>(settingsPaths.salesChannels(scope)),
    );
    return result.value ?? [];
  }

  async create(scope: LocationScope, request: CreateChannelRequest): Promise<ChannelView> {
    return firstValueFrom(
      this.api.post<CreateChannelRequest, ChannelView>(
        settingsPaths.salesChannels(scope),
        command(request),
      ),
    );
  }

  async matrices(scope: LocationScope, channelId: string): Promise<ChannelMatrices> {
    const result = await firstValueFrom(
      this.api.get<ChannelMatrices>(settingsPaths.salesChannelMatrices(scope, channelId)),
    );
    return result.value;
  }

  async replacePaymentMethods(
    scope: LocationScope,
    channelId: string,
    matrix: Readonly<Record<string, boolean>>,
    expectedVersion: number,
  ): Promise<void> {
    await firstValueFrom(
      this.api.send<Readonly<Record<string, boolean>>, void>(
        'PUT',
        settingsPaths.salesChannelPaymentMethods(scope, channelId),
        command(matrix),
        { params: { expectedVersion } },
      ),
    );
  }

  async replaceFulfillmentModes(
    scope: LocationScope,
    channelId: string,
    matrix: Readonly<Record<string, boolean>>,
    expectedVersion: number,
  ): Promise<void> {
    await firstValueFrom(
      this.api.send<Readonly<Record<string, boolean>>, void>(
        'PUT',
        settingsPaths.salesChannelFulfillmentModes(scope, channelId),
        command(matrix),
        { params: { expectedVersion } },
      ),
    );
  }

  async archive(
    scope: LocationScope,
    channelId: string,
    expectedVersion: number,
  ): Promise<ChannelView> {
    return firstValueFrom(
      this.api.post<null, ChannelView>(
        settingsPaths.salesChannelArchive(scope, channelId),
        command(null),
        {
          params: { expectedVersion },
        },
      ),
    );
  }
}
