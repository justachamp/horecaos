import { bootstrapApplication } from '@angular/platform-browser';

import { App } from './app/app';
import { appConfig } from './app/app.config';
import { APP_CONFIG } from './app/core/config/app-config';
import { loadAppConfig } from './app/core/config/load-config';
import { CATALOGUE_LOADERS } from './app/core/i18n/catalogue-loaders';
import { registerCatalogueLoaders } from './app/core/i18n/i18n';
import { showStartFailure } from './start-failure';

/**
 * Reads the deployment's configuration, then starts.
 *
 * The fetch is awaited here rather than inside an initializer so that {@link APP_CONFIG} is a
 * value by the time the first injector is built. A failure is terminal and is shown as itself:
 * falling back to a placeholder tenant would produce an app that renders and then answers 404 to
 * every call.
 */
registerCatalogueLoaders(CATALOGUE_LOADERS);

try {
  const config = await loadAppConfig();
  await bootstrapApplication(App, {
    ...appConfig,
    providers: [...appConfig.providers, { provide: APP_CONFIG, useValue: config }],
  });
} catch (failure) {
  showStartFailure(failure);
}
