import { InjectionToken } from '@angular/core';

/**
 * Everything that differs between a development build, a tenant's own domain,
 * and the Mini App served to a bot.
 *
 * Read at bootstrap from `/config.json` rather than compiled in, because this
 * application is one build serving many tenants: the tenant and brand below are
 * in the path of every call it makes, and a build-time constant would mean one
 * artifact per brand and a rebuild to move a brand between environments. See
 * `load-config.ts` for how it arrives and what happens when it does not.
 */
export interface AppConfig {
  /** Origin plus `/api/v1`, or just `/api/v1` when the page is same-origin. */
  readonly apiBaseUrl: string;

  /**
   * The tenant and brand this deployment serves.
   *
   * ADR 0031 keeps tenant identity in the path and matches it against the
   * token's signed organization claim; it is never taken from a header or a
   * subdomain. A storefront deployment serves exactly one brand, so these are
   * configuration rather than routing state.
   */
  readonly tenantId: string;
  readonly brandId: string;

  /**
   * The branch this deployment opens on when nothing else has chosen one.
   *
   * A *default* rather than an identity: the location is part of the menu's
   * identity, a cart cannot be carried across locations, and a customer may
   * change branch. A `location` query parameter and a remembered choice both
   * beat it.
   */
  readonly defaultLocationId?: string;

  /**
   * The sales channel this build reports on carts (ADR 0036), and the channel
   * whose price plane the menu is priced against.
   *
   * **A tenant-defined channel *code*, not a `SalesChannelSystemType`.** The
   * platform looks it up with `SalesChannelLookup.byCode(tenantId, channel)`,
   * and a code naming no row of this tenant answers `CHANNEL_NOT_ENABLED`.
   */
  readonly channel: string;

  /** Yandex Maps browser key, for the address picker. Not a platform secret. */
  readonly yandexMapsApiKey: string;
}

export const APP_CONFIG = new InjectionToken<AppConfig>('APP_CONFIG');
