/* Deployment configuration, replaced per environment.
 *
 * Same runtime-config pattern as `frontend/storefront/config.template.json`:
 * this template is not served directly. `docker-entrypoint.sh` renders it
 * into `config.js`, which `public/config.js`'s own comment already
 * documents as the file "overwrite at deploy time" — this is that overwrite,
 * automated. One built image runs in every environment.
 *
 * No secret belongs here. `horecaos-control-plane` is a public OAuth client
 * that holds no credential (ADR 0003).
 */
window.horecaosControlPlaneConfig = {
  apiBaseUrl: '${APP_API_BASE_URL}',
  issuerUrl: '${APP_ISSUER_URL}',
  clientId: '${APP_CLIENT_ID}',
  displayTimeZone: '${APP_DISPLAY_TIME_ZONE}',
};
