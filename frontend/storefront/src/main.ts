import { bootstrapApplication } from '@angular/platform-browser';

import { App } from './app/app';
import { appConfig } from './app/app.config';
import { APP_CONFIG } from './app/core/config/app-config';
import { applyBrand } from './app/core/config/apply-brand';
import { loadAppConfig } from './app/core/config/load-config';
import { showStartFailure } from './start-failure';

/**
 * Reads the deployment's configuration, then starts.
 *
 * The fetch is awaited rather than run inside an initializer so that
 * {@link APP_CONFIG} is a value by the time the first injector is built. An
 * initializer would let a service that injects it construct against a
 * half-populated token, and the failure would be a null tenant id in a URL
 * rather than an error anybody can read.
 *
 * A failure here is terminal and is shown as itself. Falling back to a default
 * tenant would produce an application that renders and then answers 404 to
 * every call — which is precisely the failure this replaces.
 */
const config = await loadAppConfig();
applyBrand(config.brand);

bootstrapApplication(App, {
  ...appConfig,
  providers: [
    ...appConfig.providers,
    { provide: APP_CONFIG, useValue: config },
  ],
}).catch((failure) => showStartFailure(failure));
