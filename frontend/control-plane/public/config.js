/* Deployment configuration, replaced per environment.
 *
 * This file is served as a static asset and read before the application
 * bootstraps, so one build artifact runs in every environment. Overwrite it at
 * deploy time; do not edit it to configure a developer machine, because the
 * defaults in src/app/core/config/app-config.ts already point at localhost.
 *
 * No secret belongs here. It is public, and the client is a public OAuth client
 * that holds no credential (ADR 0003).
 */
window.horecaosControlPlaneConfig = {
  apiBaseUrl: 'http://localhost:8080',
  issuerUrl: 'http://localhost:8081/realms/horecaos',
  clientId: 'horecaos-control-plane',
  displayTimeZone: 'Asia/Tashkent',
};
