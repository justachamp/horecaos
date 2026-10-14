import { InjectionToken } from '@angular/core';

/**
 * Everything that differs between a developer's machine, pre-production and production.
 *
 * Read at bootstrap from `/config.json` rather than compiled in, so one built image runs in every
 * environment: the Keycloak issuer, the API prefix and the tenant below are all environment
 * facts, and a build-time constant would mean one artifact per environment and a rebuild to move
 * a deployment. See `load-config.ts` for how it arrives and what happens when it does not.
 */
export interface AppConfig {
  /** The platform API prefix: `/api/v1` when the page and the API share an origin (the default). */
  readonly apiBaseUrl: string;

  /**
   * The Keycloak realm this app signs in against, for example
   * `https://auth.horecaos.uz/realms/horecaos`. The OIDC discovery document is read from here and
   * its `issuer` must equal this value exactly.
   */
  readonly issuerUrl: string;

  /** The public Authorization Code + PKCE client registered in that realm. No secret, ever. */
  readonly clientId: string;

  /**
   * The tenant this deployment's couriers work for.
   *
   * ADR 0031 keeps tenant identity in the path of every call. A courier is a courier of one
   * tenant, found by their own token subject within it, so this is configuration and not routing
   * state: pointing a build at a tenant the account is not a courier of answers 404, which the
   * app shows as "this account is not registered here" rather than as an error.
   */
  readonly tenantId: string;

  /** The IANA zone times are shown in. A courier's phone may be anywhere; the branch is in one place. */
  readonly displayTimeZone: string;
}

export const APP_CONFIG = new InjectionToken<AppConfig>('APP_CONFIG');
