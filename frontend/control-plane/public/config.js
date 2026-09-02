/* Deployment configuration, replaced per environment.
 *
 * This file is served as a static asset and read before the application
 * bootstraps, so one build artifact runs in every environment. Overwrite it at
 * deploy time; do not edit it to configure a developer machine, because the
 * defaults in src/app/core/config/app-config.ts already point at localhost.
 *
 * No secret belongs here, and there is nothing secret left to put in it:
 * ADR 0062 removes the Keycloak issuer and client id that used to live here
 * along with the redirect flow they configured — this console no longer
 * talks to Keycloak at all.
 */
window.horecaosControlPlaneConfig = {
  apiBaseUrl: '',
  displayTimeZone: 'Asia/Tashkent',
};
