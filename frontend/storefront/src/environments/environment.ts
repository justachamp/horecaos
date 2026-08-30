// `ApiClient` reads `APP_CONFIG` (see core/config/app-config.ts and
// load-config.ts), fetched at bootstrap from /config.json -- not this file.
// One build serves many tenants, and a base URL compiled in here would defeat
// that. `yandexMapsApiKey` below is the one build-time value this still owns:
// `provideYaConfig` needs it before `APP_CONFIG` exists.
export const environment = {
  yandexMapsApiKey: '99847472-f185-464c-b2cb-7b28dd285a8c'
};