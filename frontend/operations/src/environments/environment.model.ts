/**
 * The shape of the build-time configuration.
 *
 * In its own file because `environment.development.ts` is *substituted for*
 * `environment.ts` by the build's `fileReplacements`. A development file that
 * imported its type from the file it replaces is a cycle, and esbuild reports it
 * as a confusing "environment is declared here" error rather than as a cycle.
 */
export interface Environment {
  readonly production: boolean;

  /**
   * Origin of the platform API. Empty means same-origin, which is what a
   * reverse-proxied production deployment wants.
   */
  readonly apiBaseUrl: string;

  readonly auth: {
    /** Keycloak realm issuer, e.g. https://id.horecaos.uz/realms/horecaos */
    readonly issuer: string;
    /** Public client id. Public clients hold no secret; PKCE replaces it. */
    readonly clientId: string;
    /**
     * Must match an allowlisted redirect URI on the Keycloak client exactly.
     * Keycloak wildcards are a documented open-redirect footgun (ADR 0003).
     */
    readonly redirectUri: string;
    readonly postLogoutRedirectUri: string;
    /** Space-separated scopes. `openid` is mandatory. */
    readonly scope: string;
  };
}
