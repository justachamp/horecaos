import { InjectionToken } from '@angular/core';

/**
 * Deployment configuration.
 *
 * Read at runtime from `public/config.js` rather than baked in at build time.
 * One artifact is promoted from staging to production unchanged; a build-time
 * environment file means the thing tested in staging is not the thing that
 * ships, and the difference is exactly the Keycloak issuer, which is the field
 * most likely to be wrong.
 */
export interface AppConfig {
  /** Origin of the platform API, no trailing slash. Same origin in production. */
  readonly apiBaseUrl: string;

  /** Keycloak realm issuer, e.g. https://auth.qoida.uz/realms/qoida. */
  readonly issuerUrl: string;

  /** Public client identifier. A public client holds no secret (ADR 0003). */
  readonly clientId: string;

  /**
   * The timezone every instant in this console is rendered in. Staff work to
   * one clock regardless of where the browser thinks it is; a manager in
   * Bukhara and one in Tashkent must read the same timestamp on the same row.
   */
  readonly displayTimeZone: string;
}

export const APP_CONFIG = new InjectionToken<AppConfig>('Qoida control-plane configuration');

/** The global `public/config.js` writes, if it has been deployed. */
interface ConfiguredWindow {
  qoidaControlPlaneConfig?: Partial<AppConfig>;
}

/**
 * Local development values. Deliberately pointing at localhost so a missing
 * `config.js` fails against a machine that is not there, rather than silently
 * authenticating against production.
 */
const DEVELOPMENT_DEFAULTS: AppConfig = {
  apiBaseUrl: 'http://localhost:8080',
  issuerUrl: 'http://localhost:8081/realms/qoida',
  clientId: 'qoida-control-plane',
  displayTimeZone: 'Asia/Tashkent',
};

export function resolveAppConfig(host: ConfiguredWindow = globalThis as ConfiguredWindow): AppConfig {
  const supplied = host.qoidaControlPlaneConfig ?? {};
  const resolved: AppConfig = { ...DEVELOPMENT_DEFAULTS, ...supplied };

  // A trailing slash produces `//api/v1/...` once a path is appended, which
  // some gateways route and others reject. Normalise rather than debug it.
  return { ...resolved, apiBaseUrl: resolved.apiBaseUrl.replace(/\/+$/, '') };
}
