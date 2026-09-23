import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../../core/api/api-client';
import { command } from '../../../core/api/idempotency';
import { LocationScope } from '../../../core/api/operations-paths';
import { settingsPaths } from '../../../core/api/settings-paths';

/** Mirrors `ChannelSetupController.HostnameView`. */
export interface ChannelHostnameView {
  readonly configured: boolean;
  readonly hostname: string | null;
  readonly verified: boolean;
}

/** Mirrors `ChannelSetupController.PresentationView`. */
export interface ChannelPresentationView {
  readonly seoTitle: string | null;
  readonly seoDescription: string | null;
  readonly ogImageAssetId: string | null;
}

export interface ChannelPresentationRequest {
  readonly seoTitle?: string | null;
  readonly seoDescription?: string | null;
  readonly ogImageAssetId?: string | null;
}

/** Mirrors `ChannelPagesController.PageVersionView`. */
export interface ChannelPageVersionView {
  readonly published: boolean;
  readonly slug: string;
  readonly id: string | null;
  readonly version: number | null;
  readonly contentsByLocale: Readonly<Record<string, string>>;
  readonly publishedBy: string | null;
  readonly publishedAt: string | null;
}

/** Row 10.5's closed set — `ChannelPageSlug` (Java) mirrored one to one. */
export const CHANNEL_PAGE_SLUGS: readonly string[] = ['about', 'contacts', 'delivery-terms', 'privacy-offer'];

/**
 * Row 10.5's channel setup hub: `ChannelSetupController` and
 * `ChannelPagesController`, both control-plane, both nested under one
 * channel's own path — see `sales-channels-api.ts`'s own doc for why this
 * app still calls the control-plane surface for a channel's own state.
 */
@Injectable({ providedIn: 'root' })
export class ChannelSetupApi {
  private readonly api = inject(ApiClient);

  // ------------------------------------------------------------ hostname

  async hostname(scope: LocationScope, channelId: string): Promise<ChannelHostnameView> {
    const result = await firstValueFrom(
      this.api.get<ChannelHostnameView>(settingsPaths.channelHostname(scope, channelId)),
    );
    return result.value;
  }

  async setSubdomain(
    scope: LocationScope,
    channelId: string,
    slug: string,
    expectedVersion: number,
  ): Promise<ChannelHostnameView> {
    return firstValueFrom(
      this.api.put<{ slug: string }, ChannelHostnameView>(
        settingsPaths.channelHostnameSubdomain(scope, channelId),
        command({ slug }),
        { params: { expectedVersion } },
      ),
    );
  }

  async setCustomHostname(
    scope: LocationScope,
    channelId: string,
    hostname: string,
    expectedVersion: number,
  ): Promise<ChannelHostnameView> {
    return firstValueFrom(
      this.api.put<{ hostname: string }, ChannelHostnameView>(
        settingsPaths.channelHostnameCustom(scope, channelId),
        command({ hostname }),
        { params: { expectedVersion } },
      ),
    );
  }

  async verifyHostname(
    scope: LocationScope,
    channelId: string,
    expectedVersion: number,
  ): Promise<ChannelHostnameView> {
    return firstValueFrom(
      this.api.post<null, ChannelHostnameView>(
        settingsPaths.channelHostnameVerify(scope, channelId),
        command(null),
        { params: { expectedVersion } },
      ),
    );
  }

  async clearHostname(scope: LocationScope, channelId: string, expectedVersion: number): Promise<void> {
    await firstValueFrom(
      this.api.send<null, void>('DELETE', settingsPaths.channelHostname(scope, channelId), command(null), {
        params: { expectedVersion },
      }),
    );
  }

  // --------------------------------------------------------- presentation

  async presentation(scope: LocationScope, channelId: string): Promise<ChannelPresentationView> {
    const result = await firstValueFrom(
      this.api.get<ChannelPresentationView>(settingsPaths.channelPresentation(scope, channelId)),
    );
    return result.value;
  }

  async setPresentation(
    scope: LocationScope,
    channelId: string,
    request: ChannelPresentationRequest,
    expectedVersion: number,
  ): Promise<ChannelPresentationView> {
    return firstValueFrom(
      this.api.put<ChannelPresentationRequest, ChannelPresentationView>(
        settingsPaths.channelPresentation(scope, channelId),
        command(request),
        { params: { expectedVersion } },
      ),
    );
  }

  // -------------------------------------------------------------- pages

  async currentPage(scope: LocationScope, channelId: string, slug: string): Promise<ChannelPageVersionView> {
    const result = await firstValueFrom(
      this.api.get<ChannelPageVersionView>(settingsPaths.channelPageCurrent(scope, channelId, slug)),
    );
    return result.value;
  }

  async publishPage(
    scope: LocationScope,
    channelId: string,
    slug: string,
    contentsByLocale: Readonly<Record<string, string>>,
  ): Promise<ChannelPageVersionView> {
    return firstValueFrom(
      this.api.post<{ contentsByLocale: Readonly<Record<string, string>> }, ChannelPageVersionView>(
        settingsPaths.channelPagePublish(scope, channelId, slug),
        command({ contentsByLocale }),
      ),
    );
  }
}
